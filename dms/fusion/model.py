"""The fusion model file (JSON) and its forward pass.

    {"format": "dms-fusion-1",
     "features": [input names],              see fusion/features.py
     "mean": [...], "scale": [...],          standardisation from the training pictures
     "heads": {"eye_closed": {"kind": "mlp", "layers": [...]},
               "yawn":       {"kind": "logistic", "layers": [...]}},
     "info": {...}}                          training data and scores (for the thesis)

A layer is {"weights": [[w_ij] per output], "bias": [...], "activation":
tanh | relu | sigmoid | linear}; the last layer has one sigmoid output, the
probability of the sign. The forward pass is written as plain loops so that
the Android app (dms/Fusion.kt) computes exactly the same numbers.
"""

from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Dict, List, Mapping, Optional

FORMAT = "dms-fusion-1"
HEADS = ("eye_closed", "yawn")
ACTIVATIONS = ("tanh", "relu", "sigmoid", "linear")


def _activate(name: str, x: float) -> float:
    if name == "tanh":
        return math.tanh(x)
    if name == "relu":
        return x if x > 0.0 else 0.0
    if name == "sigmoid":
        return 1.0 / (1.0 + math.exp(-x)) if x >= 0.0 else math.exp(x) / (1.0 + math.exp(x))
    return x


class FusionModel:
    def __init__(self, data: dict):
        if data.get("format") != FORMAT:
            raise ValueError(f"not a fusion model file (format {data.get('format')!r}, expected {FORMAT!r})")
        self.features: List[str] = [str(n) for n in data["features"]]
        self.mean: List[float] = [float(v) for v in data["mean"]]
        self.scale: List[float] = [float(v) for v in data["scale"]]
        n = len(self.features)
        if len(self.mean) != n or len(self.scale) != n or any(s <= 0 for s in self.scale):
            raise ValueError("fusion model: mean / scale do not match the feature list")
        self.heads: Dict[str, List[dict]] = {}
        for name, head in data["heads"].items():
            width = n
            layers = []
            for i, layer in enumerate(head["layers"]):
                w = [[float(v) for v in row] for row in layer["weights"]]
                b = [float(v) for v in layer["bias"]]
                act = layer.get("activation", "linear")
                if act not in ACTIVATIONS:
                    raise ValueError(f"fusion model head {name}: unknown activation {act!r}")
                if len(w) != len(b) or any(len(row) != width for row in w):
                    raise ValueError(f"fusion model head {name}: layer {i} has the wrong shape")
                layers.append({"weights": w, "bias": b, "activation": act})
                width = len(b)
            if width != 1:
                raise ValueError(f"fusion model head {name}: the last layer must have one output")
            self.heads[name] = layers
        self.data = data

    @classmethod
    def load(cls, path: str | Path) -> "FusionModel":
        return cls(json.loads(Path(path).read_text(encoding="utf-8")))

    def save(self, path: str | Path) -> None:
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_text(json.dumps(self.data, indent=1) + "\n", encoding="utf-8")

    def standardise(self, values: Mapping[str, Optional[float]]) -> Optional[List[float]]:
        x = []
        for name, m, s in zip(self.features, self.mean, self.scale):
            v = values.get(name)
            if v is None:
                return None
            x.append((float(v) - m) / s)
        return x

    def head_output(self, name: str, x: List[float]) -> float:
        h = x
        for layer in self.heads[name]:
            out = []
            for row, b in zip(layer["weights"], layer["bias"]):
                s = b
                for w, v in zip(row, h):
                    s += w * v
                out.append(_activate(layer["activation"], s))
            h = out
        return h[0]

    def predict(self, values: Mapping[str, Optional[float]]) -> Dict[str, Optional[float]]:
        """Probability per head (None for every head when an input is missing)."""
        x = self.standardise(values)
        return {name: (None if x is None else self.head_output(name, x)) for name in self.heads}
