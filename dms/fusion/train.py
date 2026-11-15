"""Training of the fusion model heads (numpy only, deterministic for a given seed).

Two candidates per sign are trained on the training pictures:
  logistic  - one weighted sum of the inputs (easy to explain)
  mlp       - one hidden layer of ``hidden`` tanh units (can learn combinations,
              e.g. "eyeBlink high AND eyeLookDown low = closed, not looking down")
Both use class-balanced weights (closed eyes and yawns are rarer than open
eyes), L2 regularisation and early stopping on the validation pictures. The
candidate with the better validation F1 is kept; the test pictures are only
used for the final report.
"""

from __future__ import annotations

from typing import Dict, List, Optional, Tuple

import numpy as np


def fit_standardisation(X: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
    mean = X.mean(axis=0)
    scale = X.std(axis=0)
    scale[scale < 1e-6] = 1.0  # constant input: leave it unscaled
    return mean, scale


def binary_metrics(y: np.ndarray, p: np.ndarray, threshold: float = 0.5) -> Dict[str, float]:
    y = np.asarray(y).astype(bool)
    pred = np.asarray(p) >= threshold
    tp = int(np.sum(pred & y))
    fp = int(np.sum(pred & ~y))
    fn = int(np.sum(~pred & y))
    tn = int(np.sum(~pred & ~y))
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    n = tp + fp + fn + tn
    return {"n": n, "tp": tp, "fp": fp, "fn": fn, "tn": tn,
            "accuracy": round((tp + tn) / n, 4) if n else 0.0,
            "precision": round(precision, 4), "recall": round(recall, 4), "f1": round(f1, 4)}


def _sigmoid(z: np.ndarray) -> np.ndarray:
    return np.where(z >= 0, 1.0 / (1.0 + np.exp(-np.abs(z))), np.exp(-np.abs(z)) / (1.0 + np.exp(-np.abs(z))))


class _Net:
    """hidden = 0: logistic regression; hidden > 0: one tanh hidden layer."""

    def __init__(self, n_in: int, hidden: int, rng: np.random.Generator):
        self.hidden = hidden
        if hidden:
            self.p = {"W1": rng.normal(0, np.sqrt(1.0 / n_in), (hidden, n_in)), "b1": np.zeros(hidden),
                      "W2": rng.normal(0, np.sqrt(1.0 / hidden), (1, hidden)), "b2": np.zeros(1)}
        else:
            self.p = {"W2": np.zeros((1, n_in)), "b2": np.zeros(1)}

    def forward(self, X: np.ndarray):
        a1 = np.tanh(X @ self.p["W1"].T + self.p["b1"]) if self.hidden else X
        return a1, _sigmoid(a1 @ self.p["W2"].T + self.p["b2"])[:, 0]

    def grads(self, X, y, w, l2):
        a1, prob = self.forward(X)
        dz2 = ((prob - y) * w / w.sum())[:, None]
        g = {"W2": dz2.T @ a1 + l2 * self.p["W2"], "b2": dz2.sum(axis=0)}
        if self.hidden:
            dz1 = (dz2 @ self.p["W2"]) * (1.0 - a1 ** 2)
            g["W1"] = dz1.T @ X + l2 * self.p["W1"]
            g["b1"] = dz1.sum(axis=0)
        return g

    def loss(self, X, y, w) -> float:
        _, prob = self.forward(X)
        prob = np.clip(prob, 1e-12, 1 - 1e-12)
        return float(-np.sum(w * (y * np.log(prob) + (1 - y) * np.log(1 - prob))) / w.sum())

    def layers(self) -> List[dict]:
        out = []
        if self.hidden:
            out.append({"weights": self.p["W1"].tolist(), "bias": self.p["b1"].tolist(), "activation": "tanh"})
        out.append({"weights": self.p["W2"].tolist(), "bias": self.p["b2"].tolist(), "activation": "sigmoid"})
        return out


def _balanced(y: np.ndarray) -> np.ndarray:
    pos = max(1, int(y.sum()))
    neg = max(1, int(len(y) - y.sum()))
    return np.where(y > 0.5, len(y) / (2.0 * pos), len(y) / (2.0 * neg))


def train_network(X: np.ndarray, y: np.ndarray, Xv: np.ndarray, yv: np.ndarray, hidden: int = 0, seed: int = 0,
                  l2: float = 1e-3, lr: float = 0.01, epochs: int = 300, batch: int = 256,
                  patience: int = 25) -> Tuple[List[dict], dict]:
    """Adam + mini-batches, keeps the weights with the lowest validation loss."""
    rng = np.random.default_rng(seed)
    net = _Net(X.shape[1], hidden, rng)
    w, wv = _balanced(y), _balanced(yv)
    m = {k: np.zeros_like(v) for k, v in net.p.items()}
    s = {k: np.zeros_like(v) for k, v in net.p.items()}
    b1, b2, eps, step = 0.9, 0.999, 1e-8, 0
    best = (net.loss(Xv, yv, wv), {k: v.copy() for k, v in net.p.items()}, 0)
    since_best = 0
    for epoch in range(1, epochs + 1):
        order = rng.permutation(len(X))
        for start in range(0, len(X), batch):
            idx = order[start:start + batch]
            g = net.grads(X[idx], y[idx], w[idx], l2)
            step += 1
            for k in net.p:
                m[k] = b1 * m[k] + (1 - b1) * g[k]
                s[k] = b2 * s[k] + (1 - b2) * g[k] ** 2
                net.p[k] -= lr * (m[k] / (1 - b1 ** step)) / (np.sqrt(s[k] / (1 - b2 ** step)) + eps)
        val = net.loss(Xv, yv, wv)
        if val < best[0] - 1e-6:
            best, since_best = (val, {k: v.copy() for k, v in net.p.items()}, epoch), 0
        else:
            since_best += 1
            if since_best >= patience:
                break
    net.p = best[1]
    return net.layers(), {"val_loss": round(best[0], 5), "best_epoch": best[2], "epochs_run": epoch}


def predict_layers(layers: List[dict], X: np.ndarray) -> np.ndarray:
    """Vectorised forward pass of exported layers (same maths as FusionModel)."""
    h = X
    for layer in layers:
        z = h @ np.asarray(layer["weights"]).T + np.asarray(layer["bias"])
        act = layer["activation"]
        h = np.tanh(z) if act == "tanh" else np.maximum(z, 0) if act == "relu" else _sigmoid(z) if act == "sigmoid" else z
    return h[:, 0]


def fit_head(X: np.ndarray, y: np.ndarray, Xv: np.ndarray, yv: np.ndarray, hidden: int = 16,
             seed: int = 0) -> Tuple[dict, dict]:
    """Train the logistic and the MLP candidate; keep the one with the better validation F1."""
    candidates = {}
    for kind, h in (("logistic", 0), ("mlp", hidden)):
        if kind == "mlp" and hidden <= 0:
            continue
        layers, hist = train_network(X, y, Xv, yv, hidden=h, seed=seed)
        candidates[kind] = {"layers": layers, "history": hist, "val": binary_metrics(yv, predict_layers(layers, Xv))}
    best: Optional[str] = None
    for kind, c in candidates.items():  # logistic first: it wins ties (simpler)
        if best is None or c["val"]["f1"] > candidates[best]["val"]["f1"] + 1e-9:
            best = kind
    report = {"chosen": best, "candidates": {k: {"val": c["val"], **c["history"]} for k, c in candidates.items()}}
    return {"kind": best, "layers": candidates[best]["layers"]}, report
