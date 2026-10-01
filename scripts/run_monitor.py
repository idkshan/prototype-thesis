"""Run the driver-monitoring detection model on a camera, video, folder or ESP32-CAM.

Examples
--------
    # laptop webcam, paper-baseline detector (YOLOv8n)
    python scripts/run_monitor.py --source 0

    # the same, with YOLO26n as the phone detector
    python scripts/run_monitor.py --source 0 --yolo models/yolo26n.pt

    # ESP32-CAM stream, context from the ESP32, alerts to its buzzer
    python scripts/run_monitor.py --source http://192.168.4.1:81/stream \
        --context-url http://192.168.4.1/sensors --alert-url http://192.168.4.1/alert

    # recorded video, no window, save annotated copy
    python scripts/run_monitor.py --source drive.mp4 --no-show --save-video out.mp4

Keys (window mode): q / Esc = quit, c = recalibrate (new driver).
"""

import argparse
import json
import sys
import time

import cv2

import _bootstrap  # noqa: F401
from dms.alerts import ConsoleAlertSink, HttpAlertSink
from dms.config import EYE_MODES, MOUTH_MODES, apply_evidence_modes, load_config
from dms.context import HttpContext, StaticContext
from dms.monitor import DriverMonitor
from dms.overlay import draw
from dms.session_log import SessionLogger
from dms.sources import open_source


def parse_args():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", default="0", help="camera index, video file, image folder, or http(s) URL")
    ap.add_argument("--config", default=None, help="YAML config (defaults: configs/default.yaml values)")
    ap.add_argument("--yolo", default=None, help="YOLO weights/export for phone detection (e.g. models/yolo11n.pt)")
    ap.add_argument("--imgsz", type=int, default=None, help="YOLO inference size (e.g. 320 for mobile-like speed)")
    ap.add_argument("--yolo-every", type=int, default=None, help="run YOLO every N frames")
    ap.add_argument("--no-yolo", action="store_true", help="disable the phone detector")
    ap.add_argument("--eye-model", default=None, help="optional eye-state classifier (YOLO-cls weights)")
    ap.add_argument("--eye-mode", choices=sorted(EYE_MODES), default=None, help="eye evidence sources (ablation)")
    ap.add_argument("--mouth-mode", choices=sorted(MOUTH_MODES), default=None, help="mouth evidence sources (ablation)")
    ap.add_argument("--speed", type=float, default=None, help="fixed speed in km/h (default: assume moving)")
    ap.add_argument("--motion", type=float, default=None, help="fixed motion level in g")
    ap.add_argument("--driving-minutes", type=float, default=0.0, help="pretend the trip started this many minutes ago")
    ap.add_argument("--context-url", default=None, help="ESP32 JSON endpoint with speed_kmh / motion_g")
    ap.add_argument("--alert-url", default=None, help="ESP32 endpoint that drives the buzzer")
    ap.add_argument("--no-calibration", action="store_true", help="use population default thresholds")
    ap.add_argument("--fps", type=float, default=10.0, help="frame rate for image folders / JPEG polling")
    ap.add_argument("--max-frames", type=int, default=None)
    ap.add_argument("--log-dir", default="results/sessions")
    ap.add_argument("--session-name", default=None)
    ap.add_argument("--no-log", action="store_true")
    ap.add_argument("--show", dest="show", action="store_true", default=True)
    ap.add_argument("--no-show", dest="show", action="store_false")
    ap.add_argument("--save-video", default=None, help="write the annotated video here")
    ap.add_argument("--quiet", action="store_true", help="do not print alerts to the console")
    return ap.parse_args()


def main() -> int:
    args = parse_args()
    overrides = {}
    if args.yolo:
        overrides.setdefault("detector", {})["model_path"] = args.yolo
    if args.imgsz:
        overrides.setdefault("detector", {})["imgsz"] = args.imgsz
    if args.yolo_every:
        overrides.setdefault("detector", {})["every_n_frames"] = args.yolo_every
    if args.eye_model:
        overrides["eye_model"] = {"model_path": args.eye_model}
    if args.no_calibration:
        overrides["calibration"] = {"enabled": False}
    cfg = load_config(args.config, overrides)
    apply_evidence_modes(cfg, args.eye_mode, args.mouth_mode)

    context = HttpContext(args.context_url) if args.context_url else StaticContext(args.speed, args.motion, args.driving_minutes * 60)
    sinks = [] if args.quiet else [ConsoleAlertSink()]
    if args.alert_url:
        sinks.append(HttpAlertSink(args.alert_url))

    monitor = DriverMonitor(cfg, context=context, use_detector=not args.no_yolo, alert_sinks=sinks)
    logger = None
    if not args.no_log:
        meta = {
            "source": args.source,
            "detector": None if args.no_yolo else cfg.detector.model_path,
            "imgsz": cfg.detector.imgsz,
            "eye_model": cfg.eye_model.model_path,
            "evidence_weights": cfg.evidence.__dict__,
        }
        logger = SessionLogger(args.log_dir, args.session_name, meta)
        print(f"Logging to {logger.dir}")
    writer = None
    fps_ema = None
    last = time.perf_counter()

    print("Calibrating: look straight ahead with a relaxed face for a few seconds..." if cfg.calibration.enabled else "Calibration disabled")
    try:
        for frame in open_source(args.source, fps=args.fps, max_frames=args.max_frames):
            result = monitor.process(frame.image, frame.t, frame.index)
            if logger:
                logger.log(result)
            now = time.perf_counter()
            inst = 1.0 / max(1e-6, now - last)
            last = now
            fps_ema = inst if fps_ema is None else 0.9 * fps_ema + 0.1 * inst

            if args.show or args.save_video:
                vis = draw(frame.image, result, fps_ema)
                if args.save_video:
                    if writer is None:
                        h, w = vis.shape[:2]
                        writer = cv2.VideoWriter(args.save_video, cv2.VideoWriter_fourcc(*"mp4v"), args.fps if args.fps else 10, (w, h))
                    writer.write(vis)
                if args.show:
                    cv2.imshow("Driver Monitor v1", vis)
                    key = cv2.waitKey(1) & 0xFF
                    if key in (ord("q"), 27):
                        break
                    if key == ord("c"):
                        monitor.recalibrate()
                        print("Recalibrating...")
    except KeyboardInterrupt:
        pass
    finally:
        cal = monitor.engine.calibrator
        calibration = {"status": cal.status, "baseline": cal.baseline.__dict__, "last_failure": cal.last_failure}
        monitor.close()
        if writer is not None:
            writer.release()
        if args.show:
            cv2.destroyAllWindows()
        if logger:
            summary = logger.close(calibration)
            print(json.dumps({k: summary[k] for k in ("frames", "processing_fps", "alert_episodes", "calibration")}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
