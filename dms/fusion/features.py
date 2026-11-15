"""Inputs of the fusion model: the numbers of ONE picture, none of them calibrated.

The training pictures come from many people without a calibration phase, so
the inputs are absolute values (EAR and MAR are already scale-free ratios).
The driver's calibration still acts in Stage 3, through the other evidence
sources the fusion output is averaged with.
"""

from __future__ import annotations

from typing import Dict, Optional

from ..types import FaceObservation, FrameFeatures

# MediaPipe Face Landmarker blendshape categories, in the model's output order
BLENDSHAPE_NAMES = (
    "_neutral", "browDownLeft", "browDownRight", "browInnerUp", "browOuterUpLeft", "browOuterUpRight",
    "cheekPuff", "cheekSquintLeft", "cheekSquintRight", "eyeBlinkLeft", "eyeBlinkRight",
    "eyeLookDownLeft", "eyeLookDownRight", "eyeLookInLeft", "eyeLookInRight", "eyeLookOutLeft",
    "eyeLookOutRight", "eyeLookUpLeft", "eyeLookUpRight", "eyeSquintLeft", "eyeSquintRight",
    "eyeWideLeft", "eyeWideRight", "jawForward", "jawLeft", "jawOpen", "jawRight", "mouthClose",
    "mouthDimpleLeft", "mouthDimpleRight", "mouthFrownLeft", "mouthFrownRight", "mouthFunnel",
    "mouthLeft", "mouthLowerDownLeft", "mouthLowerDownRight", "mouthPressLeft", "mouthPressRight",
    "mouthPucker", "mouthRight", "mouthRollLower", "mouthRollUpper", "mouthShrugLower",
    "mouthShrugUpper", "mouthSmileLeft", "mouthSmileRight", "mouthStretchLeft", "mouthStretchRight",
    "mouthUpperUpLeft", "mouthUpperUpRight", "noseSneerLeft", "noseSneerRight",
)
GEOMETRY_NAMES = ("ear_left", "ear_right", "mar", "yaw", "pitch", "roll")
FEATURE_NAMES = GEOMETRY_NAMES + tuple("bs_" + n for n in BLENDSHAPE_NAMES)


def fusion_inputs(face: Optional[FaceObservation], f: FrameFeatures) -> Dict[str, Optional[float]]:
    """All candidate inputs by name (None = not measured on this picture)."""
    values: Dict[str, Optional[float]] = {name: getattr(f, name) for name in GEOMETRY_NAMES}
    blend = face.blendshapes if face is not None else {}
    for name in BLENDSHAPE_NAMES:
        v = blend.get(name)
        values["bs_" + name] = None if v is None else float(v)
    return values
