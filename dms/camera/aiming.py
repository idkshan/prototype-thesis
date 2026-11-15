"""Camera aiming: how far the camera is off the driver's face axis, and which way to move it.

Purpose   Help mount the camera in front of the driver (SO1, RA 10913 placement)
          before calibration. The same number the calibration checks
          (calibration.max_camera_offset_deg, 25 deg) is shown live.
Inputs    the head pose (yaw, pitch) measured while the driver sits normally
          and looks at the ROAD - not at the camera
Outputs   AimAdvice: smoothed yaw / pitch, total offset, ok or not, and the
          direction to move the camera in the driver's own terms

How the direction follows from the pose (head_pose.py sign convention):
    yaw > 0   the face looks toward the image's right -> the camera sits to the
              driver's right -> move it to the driver's LEFT
    pitch > 0 the face looks up in the image -> the camera sits below the
              face -> move it UP
In short: move the camera the way the driver's nose points in the picture.
The angles carry a few degrees of bias (generic face model, approximate lens),
so aim for well inside the limit rather than exactly at it.
"""

from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass
from typing import Deque, Optional

import numpy as np

GOOD_AXIS_DEG = 10.0  # one axis within this is "good", no move needed


@dataclass
class AimAdvice:
    yaw: Optional[float]
    pitch: Optional[float]
    offset: Optional[float]  # sqrt(yaw^2 + pitch^2), as in the calibration check
    limit: float
    ok: bool
    horizontal: str  # e.g. "move LEFT 12 deg" (driver's left), or "good"
    vertical: str    # e.g. "move UP 8 deg", or "good"

    def summary(self) -> str:
        if self.offset is None:
            return "no face: make sure the whole face is in the picture"
        state = "OK" if self.ok else "TOO FAR OFF"
        return (f"camera angle {self.offset:.0f} deg (limit {self.limit:.0f}) {state} | "
                f"left/right {self.yaw:+.0f}: {self.horizontal} | up/down {self.pitch:+.0f}: {self.vertical}")


def advice(yaw: Optional[float], pitch: Optional[float], limit: float) -> AimAdvice:
    if yaw is None or pitch is None:
        return AimAdvice(None, None, None, limit, False, "-", "-")
    offset = math.hypot(yaw, pitch)
    horizontal = "good" if abs(yaw) <= GOOD_AXIS_DEG else f"move {'LEFT' if yaw > 0 else 'RIGHT'} {abs(yaw):.0f} deg"
    vertical = "good" if abs(pitch) <= GOOD_AXIS_DEG else f"move {'UP' if pitch > 0 else 'DOWN'} {abs(pitch):.0f} deg"
    return AimAdvice(yaw, pitch, offset, limit, offset <= limit, horizontal, vertical)


class CameraAimer:
    """Median over the last ``window`` measurements (about 1 s), so the numbers do not jump."""

    def __init__(self, limit_deg: float = 25.0, window: int = 15):
        self.limit = limit_deg
        self._yaw: Deque[float] = deque(maxlen=window)
        self._pitch: Deque[float] = deque(maxlen=window)

    def update(self, yaw: Optional[float], pitch: Optional[float]) -> AimAdvice:
        if yaw is None or pitch is None:
            self._yaw.clear()
            self._pitch.clear()
            return advice(None, None, self.limit)
        self._yaw.append(yaw)
        self._pitch.append(pitch)
        return advice(float(np.median(self._yaw)), float(np.median(self._pitch)), self.limit)
