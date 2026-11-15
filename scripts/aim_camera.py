"""Aim the camera: live angle between the camera and the driver's face, with the way to move it.

    python scripts/aim_camera.py --dms-net          # ESP32-CAM on DMS-NET (192.168.4.200)
    python scripts/aim_camera.py --source http://<camera IP>:81/stream
    python scripts/aim_camera.py --source 0         # laptop webcam

The driver sits normally and looks at the ROAD (straight ahead), not at the
camera. The window shows the camera angle against the calibration limit
(calibration.max_camera_offset_deg, 25 deg): GREEN = fine, RED = move the
camera the way the arrow points (the way the driver's nose points in the
picture). The terminal prints the same once per second.

Keys: q / Esc = quit, s = save a screenshot (results/aim/).
"""

import argparse
import sys
import time
from pathlib import Path

import cv2
import numpy as np

import _bootstrap  # noqa: F401
from dms.camera import LatestFrameReader, open_source
from dms.camera.aiming import CameraAimer
from dms.camera.sources import is_live
from dms.config import load_config
from dms.monitor import DriverMonitor
from dms.overlay import _text

DMS_NET_CAMERA = "http://192.168.4.200:81/stream"
GREEN, RED, YELLOW = (60, 200, 60), (40, 40, 230), (0, 200, 255)


def draw_aim(frame: np.ndarray, adv, nose=None) -> np.ndarray:
    img = frame.copy()
    h, w = img.shape[:2]
    band = np.zeros((172, max(w, 560), 3), np.uint8)
    color = GREEN if adv.ok else RED
    if adv.offset is None:
        _text(band, "NO FACE - put the whole face in the picture", (10, 40), YELLOW, 0.7, 2)
    else:
        _text(band, f"CAMERA ANGLE {adv.offset:.0f} deg   (limit {adv.limit:.0f})   {'OK' if adv.ok else 'MOVE THE CAMERA'}",
              (10, 34), color, 0.7, 2)
        # gauge 0..60 deg with the limit marked
        x0, x1, y = 10, band.shape[1] - 10, 52
        cv2.rectangle(band, (x0, y), (x1, y + 14), (70, 70, 70), -1)
        fill = x0 + int((x1 - x0) * min(adv.offset, 60.0) / 60.0)
        cv2.rectangle(band, (x0, y), (fill, y + 14), color, -1)
        xl = x0 + int((x1 - x0) * adv.limit / 60.0)
        cv2.line(band, (xl, y - 4), (xl, y + 18), (255, 255, 255), 2)
        _text(band, f"left/right {adv.yaw:+.0f} deg: {adv.horizontal}", (10, 92),
              GREEN if adv.horizontal == "good" else YELLOW, 0.6, 1)
        _text(band, f"up/down    {adv.pitch:+.0f} deg: {adv.vertical}", (10, 116),
              GREEN if adv.vertical == "good" else YELLOW, 0.6, 1)
        _text(band, "LEFT/RIGHT = the driver's own left/right", (10, 140), (200, 200, 200), 0.45, 1)
        if nose is not None and not adv.ok:
            # arrow from the nose: the way the nose points in the picture = the way to move the camera
            dx = np.sign(adv.yaw) * min(abs(adv.yaw), 60.0) * 2.0
            dy = -np.sign(adv.pitch) * min(abs(adv.pitch), 60.0) * 2.0
            tip = (int(nose[0] + dx), int(nose[1] + dy))
            cv2.arrowedLine(img, (int(nose[0]), int(nose[1])), tip, YELLOW, 4, tipLength=0.3)
    _text(band, "Driver: sit normally and look at the ROAD, not at the camera", (10, 164), (255, 255, 255), 0.5, 1)
    out = np.zeros((h + band.shape[0], max(w, band.shape[1]), 3), np.uint8)
    out[:h, :w] = img
    out[h:, :band.shape[1]] = band
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter, allow_abbrev=False)
    ap.add_argument("--source", default=None, help="camera stream URL, webcam index or video (default 0)")
    ap.add_argument("--dms-net", action="store_true", help=f"ESP32-CAM on DMS-NET ({DMS_NET_CAMERA})")
    ap.add_argument("--config", default=None)
    args = ap.parse_args()
    source_spec = args.source or (DMS_NET_CAMERA if args.dms_net else "0")

    cfg = load_config(args.config)
    monitor = DriverMonitor(cfg, use_detector=False)  # face landmarks only: fast
    aimer = CameraAimer(cfg.calibration.max_camera_offset_deg)
    source = open_source(source_spec)
    reader = LatestFrameReader(source) if is_live(source_spec) else None
    frames = iter(source) if reader is None else None
    shots = Path("results/aim")
    last_print = 0.0
    print("Driver: sit normally and look at the ROAD (not at the camera). q = quit, s = screenshot.")
    try:
        while True:
            frame = reader.read(timeout_s=3.0) if reader is not None else next(frames, None)
            if frame is None:
                if reader is not None and not reader.finished:
                    print("no picture from the camera (check the Wi-Fi / close other viewers)")
                    continue
                break
            perception = monitor.perceive(frame.image, frame.t)
            features = monitor.extractor.extract(perception, frame.t)
            adv = aimer.update(features.yaw if features.face_present else None,
                               features.pitch if features.face_present else None)
            nose = perception.face.landmarks[1, :2] if perception.face is not None else None
            if time.monotonic() - last_print >= 1.0:
                last_print = time.monotonic()
                print(adv.summary())
            vis = draw_aim(frame.image, adv, nose)
            cv2.imshow("Aim the camera", vis)
            key = cv2.waitKey(1) & 0xFF
            if key in (ord("q"), 27):
                break
            if key == ord("s"):
                shots.mkdir(parents=True, exist_ok=True)
                path = shots / f"aim_{time.strftime('%Y%m%d_%H%M%S')}.png"
                cv2.imwrite(str(path), vis)
                print(f"saved {path}")
    except KeyboardInterrupt:
        pass
    finally:
        monitor.close()
        cv2.destroyAllWindows()
    return 0


if __name__ == "__main__":
    sys.exit(main())
