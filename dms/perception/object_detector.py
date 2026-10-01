"""Stage 1 (AI): YOLO object detector wrapper (YOLOv8 / YOLO11 / YOLO26).

All three families share the Ultralytics API, so the model is chosen purely by
the weights file (``yolov8n.pt``, ``yolo11n.pt``, ``yolo26n.pt`` or a
fine-tuned checkpoint / exported ``.onnx``/``.tflite``/NCNN model). This keeps
the comparison fair: everything except the network is identical.
"""

from __future__ import annotations

from typing import Dict, List, Tuple

import numpy as np

from ..config import ObjectDetectorConfig
from ..types import Detection


class ObjectDetector:
    def __init__(self, cfg: ObjectDetectorConfig):
        try:
            from ultralytics import YOLO
        except ImportError as exc:  # pragma: no cover - dependency error path
            raise ImportError("ultralytics is required: pip install ultralytics") from exc

        self.cfg = cfg
        self.model = YOLO(cfg.model_path, task="detect")
        self._phone_names = {n.lower() for n in cfg.phone_class_names}
        self._frame = 0
        self._last: Tuple[List[Detection], Dict[str, float]] = ([], {})

    def role_of(self, label: str) -> str | None:
        return "phone" if label.lower() in self._phone_names else None

    def detect(self, frame_bgr: np.ndarray) -> Tuple[List[Detection], Dict[str, float]]:
        """Return (detections, timings_ms). Runs every ``every_n_frames`` frames."""
        run = self._frame % max(1, self.cfg.every_n_frames) == 0
        self._frame += 1
        if not run:
            return [d for d in self._last[0]], {"yolo_skipped": 1.0}

        res = self.model.predict(
            frame_bgr,
            imgsz=self.cfg.imgsz,
            conf=self.cfg.conf,
            iou=self.cfg.iou,
            device=self.cfg.device,
            verbose=False,
        )[0]
        names = res.names
        dets: List[Detection] = []
        if res.boxes is not None and len(res.boxes):
            xyxy = res.boxes.xyxy.cpu().numpy()
            conf = res.boxes.conf.cpu().numpy()
            cls = res.boxes.cls.cpu().numpy().astype(int)
            for box, c, k in zip(xyxy, conf, cls):
                label = str(names[int(k)])
                dets.append(Detection(label=label, confidence=float(c), box=tuple(float(v) for v in box), role=self.role_of(label)))
        speed = {f"yolo_{k}": float(v) for k, v in (res.speed or {}).items()}
        self._last = (dets, speed)
        return dets, speed
