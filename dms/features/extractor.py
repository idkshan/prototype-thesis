"""Stage 2: turn Stage-1 model outputs into numeric visual features.

This stage makes no decisions. It computes numbers from the AI outputs:
EAR and MAR from landmark geometry, head pose from solvePnP, the learned
blendshape scores, the optional eye-state probability, and the confidence of
any YOLO phone detection located near the driver.
"""

from __future__ import annotations

from ..config import ObjectDetectorConfig
from ..types import FaceObservation, FrameFeatures, PerceptionResult
from .geometry import LEFT_EYE_EAR, RIGHT_EYE_EAR, eye_aspect_ratio, mouth_aspect_ratio
from .head_pose import estimate_head_pose


def phone_in_driver_roi(box, face: FaceObservation | None, frame_h: int, cfg: ObjectDetectorConfig) -> bool:
    """True if a phone box is plausibly held by the driver.

    Phones far from the driver (e.g. on the passenger seat or a dashboard
    mount at the edge of the frame) are ignored. Without a face every phone
    counts, because the driver's position is unknown.
    """
    if face is None:
        return True
    fx1, fy1, fx2, fy2 = face.bbox
    fw, fh = fx2 - fx1, fy2 - fy1
    fcx = (fx1 + fx2) / 2.0
    cx, cy = (box[0] + box[2]) / 2.0, (box[1] + box[3]) / 2.0
    within_x = abs(cx - fcx) <= cfg.roi_width_factor * fw
    within_y = fy1 - cfg.roi_above_factor * fh <= cy <= frame_h
    return within_x and within_y


class FeatureExtractor:
    def __init__(self, detector_cfg: ObjectDetectorConfig):
        self.detector_cfg = detector_cfg

    def extract(self, perception: PerceptionResult, t: float) -> FrameFeatures:
        w, h = perception.frame_size
        face = perception.face

        phone_conf, phone_boxes = 0.0, 0
        for det in perception.detections:
            if det.role != "phone":
                continue
            det.in_driver_roi = phone_in_driver_roi(det.box, face, h, self.detector_cfg)
            if det.in_driver_roi:
                phone_boxes += 1
                phone_conf = max(phone_conf, det.confidence)

        if face is None:
            return FrameFeatures(t=t, face_present=False, phone_conf=phone_conf, phone_boxes=phone_boxes)

        pts = face.landmarks
        ear_r = eye_aspect_ratio(pts, RIGHT_EYE_EAR)
        ear_l = eye_aspect_ratio(pts, LEFT_EYE_EAR)
        pose = estimate_head_pose(pts, w, h)
        bs = face.blendshapes
        blink = None
        if "eyeBlinkLeft" in bs and "eyeBlinkRight" in bs:
            blink = (bs["eyeBlinkLeft"] + bs["eyeBlinkRight"]) / 2.0
        return FrameFeatures(
            t=t,
            face_present=True,
            ear=(ear_l + ear_r) / 2.0,
            ear_left=ear_l,
            ear_right=ear_r,
            mar=mouth_aspect_ratio(pts),
            yaw=pose[0] if pose else None,
            pitch=pose[1] if pose else None,
            roll=pose[2] if pose else None,
            blink=blink,
            jaw_open=bs.get("jawOpen"),
            eye_closed_prob=perception.eye_closed_prob,
            phone_conf=phone_conf,
            phone_boxes=phone_boxes,
        )
