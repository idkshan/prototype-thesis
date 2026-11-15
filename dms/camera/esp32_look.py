"""Make pictures look like the prototype ESP32-CAM's (firmware/07_esp32cam_prototype).

The ESP32-CAM sends 480 x 320 JPEG frames (FRAMESIZE_HVGA, jpeg_quality 10 on
the OV2640's 0-63 scale, about 10-15 KB per frame) at roughly 12-20 frames per
second. Webcams and dataset photos are larger, sharper and less compressed, so
a model trained or tested on them can look better than it will be on the car.

    esp32_frame()       one live frame, deterministic: crop to 3:2, resize to
                        480 x 320, JPEG round trip (run_monitor.py --esp32-like)
    esp32_augment()     random ESP32-style degradation of a training picture
                        (scripts/make_esp32_look.py): smaller, compressed,
                        sometimes darker, noisier or slightly blurred
    Esp32LikeSource     wraps a frame source: esp32_frame() + frame-rate cap

What it cannot copy: the OV2640's own colours and sensor noise, its lens, how
badly it sees in the dark, and Wi-Fi freezes. Final results must still come
from the real camera.
"""

from __future__ import annotations

from dataclasses import replace
from typing import Iterator, Optional

import cv2
import numpy as np

from .sources import Frame

ESP32_SIZE = (480, 320)  # FRAMESIZE_HVGA
# OpenCV JPEG quality giving about the ESP32-CAM's frame size at jpeg_quality 10
# (10-15 KB per 480 x 320 frame; the two quality scales are not the same, so this
# is an approximation: compare the KB per frame of both if you need it closer).
ESP32_JPEG_QUALITY = 65
ESP32_FPS = 15.0


def _jpeg(img: np.ndarray, quality: int) -> np.ndarray:
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, int(quality)])
    if not ok:  # pragma: no cover - OpenCV always encodes BGR images
        return img
    return cv2.imdecode(buf, cv2.IMREAD_COLOR)


def crop_to_aspect(img: np.ndarray, aspect: float = ESP32_SIZE[0] / ESP32_SIZE[1]) -> np.ndarray:
    """Centre crop to the camera's 3:2 shape (a 4:3 webcam loses a little top and bottom)."""
    h, w = img.shape[:2]
    if w / h > aspect:
        nw = int(round(h * aspect))
        x0 = (w - nw) // 2
        return img[:, x0:x0 + nw]
    nh = int(round(w / aspect))
    y0 = (h - nh) // 2
    return img[y0:y0 + nh]


def esp32_frame(img: np.ndarray, quality: int = ESP32_JPEG_QUALITY) -> np.ndarray:
    """One frame as the ESP32-CAM would send it (deterministic)."""
    small = cv2.resize(crop_to_aspect(img), ESP32_SIZE, interpolation=cv2.INTER_AREA)
    return _jpeg(small, quality)


def esp32_augment(img: np.ndarray, rng: np.random.Generator, dark_prob: float = 0.35) -> np.ndarray:
    """Random ESP32-style version of a training picture.

    The picture keeps its shape (no crop), so YOLO labels (normalised x, y, w, h)
    stay valid. It is shrunk so that it fits in 480 x 320 (ESP32 resolution;
    bigger pictures only), sometimes darkened (indoor / evening light, with the
    extra sensor noise of a dim picture), sometimes slightly blurred (fixed
    focus, motion), and JPEG-compressed at a random quality.
    """
    out = img
    h, w = out.shape[:2]
    scale = min(1.0, ESP32_SIZE[0] / max(w, h), ESP32_SIZE[1] / min(w, h)) * rng.uniform(0.85, 1.0)
    if scale < 1.0:
        out = cv2.resize(out, (max(1, int(round(w * scale))), max(1, int(round(h * scale)))), interpolation=cv2.INTER_AREA)
    f = out.astype(np.float32)
    if rng.random() < dark_prob:
        gain = rng.uniform(0.35, 0.75)
        f = 255.0 * (f / 255.0) ** rng.uniform(1.1, 1.6) * gain
        f += rng.normal(0.0, rng.uniform(3.0, 8.0), f.shape)
    else:
        f = f * rng.uniform(0.85, 1.1) + rng.uniform(-12, 12)
        f += rng.normal(0.0, rng.uniform(0.0, 3.0), f.shape)
    f *= rng.uniform(0.92, 1.08, size=(1, 1, 3))  # white balance off a little
    out = np.clip(f, 0, 255).astype(np.uint8)
    if rng.random() < 0.3:
        out = cv2.GaussianBlur(out, (3, 3), rng.uniform(0.5, 1.1))
    return _jpeg(out, int(rng.integers(45, 86)))


class Esp32LikeSource:
    """Frame source wrapper: ESP32 picture size / compression and frame rate.

    Frames arriving faster than ``fps`` are dropped (a 30 fps webcam becomes a
    15 fps camera); frame times are kept, so all timing rules see real time.
    """

    def __init__(self, source, fps: float = ESP32_FPS, quality: int = ESP32_JPEG_QUALITY):
        self.source = source
        self.min_dt = 1.0 / fps if fps and fps > 0 else 0.0
        self.quality = quality

    def __iter__(self) -> Iterator[Frame]:
        last: Optional[float] = None
        n = 0
        for frame in self.source:
            if last is not None and frame.t - last < self.min_dt * 0.95:
                continue
            last = frame.t
            yield replace(frame, image=esp32_frame(frame.image, self.quality), index=n)
            n += 1
