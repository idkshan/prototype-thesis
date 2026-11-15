"""Context (GPS staleness, driving duration) and camera helpers (frame reader, MJPEG, stream health)."""

import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import cv2
import numpy as np
import pytest

from dms.camera import LatestFrameReader, StreamHealth, iter_mjpeg
from dms.camera.sources import Frame
from dms.config import ContextConfig
from dms.context import DrivingDurationTracker, GpsFix, GpsTracker, HttpContext, StaticContext


def test_driving_duration_pauses_when_stopped_and_resets_after_a_break():
    d = DrivingDurationTracker(moving_speed_kmh=5.0, break_reset_s=900.0)
    t = 0.0
    for _ in range(600):  # 10 min at 40 km/h
        t += 1.0
        d.update(t, 40.0)
    assert d.continuous_s == pytest.approx(599.0)
    for _ in range(300):  # 5 min traffic-light / short stop: paused, not reset
        t += 1.0
        d.update(t, 0.0)
    assert d.continuous_s == pytest.approx(599.0)
    for _ in range(1000):  # > 15 min stop: a rest break
        t += 1.0
        d.update(t, 0.0)
    assert d.continuous_s == 0.0
    t += 1.0
    d.update(t, 30.0)
    assert len(d.breaks) == 1 and d.total_driving_s == pytest.approx(600.0)


def test_unknown_speed_counts_as_driving():
    d = DrivingDurationTracker()
    for i in range(11):
        d.update(float(i), None)
    assert d.continuous_s == pytest.approx(10.0)


def test_gps_staleness_and_update_rate():
    g = GpsTracker(stale_s=3.0)
    for i in range(10):
        g.add(GpsFix(t=float(i), lat=14.5, lon=121.0, speed_kmh=30.0))
    assert g.latest_valid(9.5) is not None and g.latest_valid(13.0) is None
    g.add(GpsFix(t=40.0, lat=14.5, lon=121.0, speed_kmh=30.0))  # 31 s outage
    st = g.stats()
    assert st["outages"] == 1 and st["longest_outage_s"] == pytest.approx(31.0)
    assert st["share_within_1hz_10pct"] == pytest.approx(0.9)


def test_http_context_flags_stale_gps_as_assumed():
    clock = {"t": 100.0}
    ctx = HttpContext("http://127.0.0.1:9/never", poll_s=3600, cfg=ContextConfig(), clock=lambda: clock["t"])
    ctx.close()
    ctx.ingest({"speed_kmh": 52.0, "lat": 14.56, "lon": 120.99, "motion_g": 0.05}, t=0.0)
    r = ctx.read(0.0)
    assert r.speed_source == "gps" and r.speed_kmh == 52.0
    clock["t"] += 10.0  # ten seconds without a fix
    r = ctx.read(10.0)
    assert r.speed_source == "assumed" and r.speed_kmh is None


def test_http_context_reads_the_sensor_board_json():
    clock = {"t": 0.0}
    ctx = HttpContext("http://127.0.0.1:9/never", poll_s=3600, cfg=ContextConfig(), clock=lambda: clock["t"])
    ctx.close()
    board = {"fix": True, "lat": 14.564912, "lng": 120.993201, "kmh": 31.5, "sats": 8, "hdop": 1.1,
             "ax": 0.01, "ay": -0.02, "az": 1.0, "gx": 0.1, "gy": 0.0, "gz": 0.2, "jolt": 0.12, "tier": 0}
    ctx.ingest(board, t=0.0)
    r = ctx.read(0.0)
    assert r.speed_source == "gps" and r.speed_kmh == 31.5 and r.longitude == pytest.approx(120.993201)
    assert r.motion_g == pytest.approx(0.12)
    ctx.ingest({**board, "jolt": 0.9, "motion_g": 0.2}, t=0.0)  # horizontal motion_g wins over jolt
    assert ctx.read(0.0).motion_g == pytest.approx(0.2)

    clock["t"] = 1.0  # no satellite fix: the board sends 0.0 km/h, which must NOT read as "stopped"
    ctx.ingest({**board, "fix": False, "lat": 0.0, "lng": 0.0, "kmh": 0.0}, t=1.0)
    clock["t"] = 5.0
    r = ctx.read(5.0)
    assert r.speed_source == "assumed" and r.speed_kmh is None


def test_tier_alert_sink_maps_commands_to_levels():
    from dms.alerts import HttpAlertSink
    from dms.types import AlertCommand

    sink = HttpAlertSink("http://127.0.0.1:9/tier", style="tier")

    def cmd(event, tier, action):
        return AlertCommand(t=0.0, tier=tier, action=action, push=False, reason="", new_episode=event == "start", event=event)

    assert sink.query(cmd("start", "LOW", "soft_beep")) == {"level": 1}
    assert sink.query(cmd("escalate", "HIGH", "alarm")) == {"level": 3}
    assert sink.query(cmd("keepalive", "MEDIUM", "repeating_buzzer")) == {"level": 2}
    assert sink.query(cmd("stop", "HIGH", "off")) == {"level": 0}
    assert sink.query(cmd("unavailable", "MEDIUM", "off")) == {"level": 0}


def test_static_context_reports_its_source():
    assert StaticContext(40.0).read(0.0).speed_source == "fixed"
    assert StaticContext().read(0.0).speed_source == "assumed"


def test_latest_frame_reader_drops_stale_frames():
    def slow_camera():
        for i in range(20):
            yield Frame(np.zeros((2, 2, 3), np.uint8), i * 0.01, i)
            time.sleep(0.01)

    reader = LatestFrameReader(slow_camera())
    got = []
    while True:
        f = reader.read(timeout_s=1.0)
        if f is None:
            break
        got.append(f.index)
        time.sleep(0.05)  # processing is slower than the camera
    assert got[-1] == 19 and len(got) < 20 and reader.dropped > 0
    assert got == sorted(got)


def test_stream_health_uptime():
    h = StreamHealth(timeout_s=3.0)
    for i in range(100):
        h.frame(i * 0.1)
    h.no_frame(15.0)  # the link dies at ~9.9 s
    for i in range(50):
        h.frame(20.0 + i * 0.1)
    s = h.stats()
    assert s["downtime_intervals"] == 1 and s["downtime_s"] == pytest.approx(20.0 - 9.9, abs=0.01)
    assert s["uptime"] == pytest.approx(1 - 10.1 / 24.9, abs=0.01)


class _Mjpeg(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "multipart/x-mixed-replace;boundary=frame")
        self.end_headers()
        ok, jpg = cv2.imencode(".jpg", np.full((24, 32, 3), 128, np.uint8))
        for i in range(5):
            part = (b"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\nX-Timestamp: %d.%06d\r\n\r\n"
                    % (len(jpg), 100 + i, 500000))
            self.wfile.write(part + jpg.tobytes() + b"\r\n")
            self.wfile.flush()

    def log_message(self, *args):
        pass


def test_mjpeg_reader_parses_frames_and_camera_timestamps():
    server = ThreadingHTTPServer(("127.0.0.1", 0), _Mjpeg)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        url = f"http://127.0.0.1:{server.server_address[1]}/stream"
        frames = list(iter_mjpeg(url, timeout_s=2.0, reconnect=False))
    finally:
        server.shutdown()
    assert len(frames) == 5 and frames[0].image.shape == (24, 32, 3)
    assert [f.device_t for f in frames] == pytest.approx([100.5, 101.5, 102.5, 103.5, 104.5])


def test_run_monitor_dms_net_fills_the_fixed_addresses():
    import run_monitor

    a = run_monitor.parse_args(["--dms-net"])
    assert (a.source, a.context_url, a.alert_url, a.alert_style) == (
        "http://192.168.4.200:81/stream", "http://192.168.4.1/data", "http://192.168.4.1/tier", "tier")
    a = run_monitor.parse_args(["--dms-net", "--source", "data/recordings/S01.mp4"])  # an explicit value wins
    assert a.source == "data/recordings/S01.mp4" and a.alert_url == "http://192.168.4.1/tier"
    a = run_monitor.parse_args([])  # without the flag: webcam, no sensor board, old buzzer style
    assert (a.source, a.context_url, a.alert_url, a.alert_style) == ("0", None, None, "beeps")
