"""Picture-based fusion model (optional, learned).

One small neural network per sign looks at ALL the numbers of one picture
together - EAR per eye, MAR, head angles and the 52 face blendshapes - and
gives the probability that the eyes are closed / that the driver is yawning.
It is trained on labelled pictures (scripts/build_fusion_dataset.py, then
scripts/train_fusion.py) and becomes one more evidence source in Stage 3,
next to EAR, blendshapes and YOLO (evidence.eye_fusion / mouth_fusion).

The rules over time (closure length, PERCLOS, yawns per minute, drowsiness
state) do not change. Without a model file the pipeline behaves as before.
"""

from .features import BLENDSHAPE_NAMES, FEATURE_NAMES, fusion_inputs
from .model import HEADS, FusionModel

__all__ = ["BLENDSHAPE_NAMES", "FEATURE_NAMES", "HEADS", "FusionModel", "fusion_inputs"]
