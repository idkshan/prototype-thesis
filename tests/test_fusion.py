import csv
import json
import sys
from pathlib import Path

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from dms.config import Config, apply_evidence_modes  # noqa: E402
from dms.engine import DecisionEngine  # noqa: E402
from dms.features.extractor import FeatureExtractor  # noqa: E402
from dms.fusion import FEATURE_NAMES, FusionModel, fusion_inputs  # noqa: E402
from dms.fusion.dataset import COLUMNS, targets_from_roles  # noqa: E402
from dms.fusion.model import FORMAT  # noqa: E402
from dms.fusion.train import binary_metrics, fit_head, fit_standardisation, predict_layers  # noqa: E402
from dms.types import ContextReading, FaceObservation, FrameFeatures, PerceptionResult  # noqa: E402


def _model(eye_w=4.0, yawn_w=3.0) -> FusionModel:
    """Hand-made model: eyes closed <- low EAR, yawn <- high MAR."""
    n = len(FEATURE_NAMES)
    eye = [0.0] * n
    eye[FEATURE_NAMES.index("ear_left")] = -eye_w
    eye[FEATURE_NAMES.index("ear_right")] = -eye_w
    yawn = [0.0] * n
    yawn[FEATURE_NAMES.index("mar")] = yawn_w
    mean = [0.0] * n
    mean[FEATURE_NAMES.index("ear_left")] = mean[FEATURE_NAMES.index("ear_right")] = 0.25
    mean[FEATURE_NAMES.index("mar")] = 0.3
    scale = [1.0] * n
    scale[FEATURE_NAMES.index("ear_left")] = scale[FEATURE_NAMES.index("ear_right")] = 0.05
    scale[FEATURE_NAMES.index("mar")] = 0.1
    return FusionModel({"format": FORMAT, "features": list(FEATURE_NAMES), "mean": mean, "scale": scale, "heads": {
        "eye_closed": {"kind": "logistic", "layers": [{"weights": [eye], "bias": [0.0], "activation": "sigmoid"}]},
        "yawn": {"kind": "mlp", "layers": [{"weights": [yawn, [0.0] * n], "bias": [0.0, 0.0], "activation": "tanh"},
                                            {"weights": [[2.0, 0.0]], "bias": [0.0], "activation": "sigmoid"}]}}})


def _real_face():
    """A real MediaPipe face (landmark numbers only) from the Android golden data."""
    cases = json.loads((ROOT / "androidapp/app/src/test/resources/golden/features.json").read_text())
    c = next(c for c in cases if c["landmarks"] is not None)
    pts = np.array(c["landmarks"], dtype=float)
    return FaceObservation(landmarks=pts, blendshapes=dict(c["blendshapes"]),
                           bbox=(pts[:, 0].min(), pts[:, 1].min(), pts[:, 0].max(), pts[:, 1].max())), (c["w"], c["h"])


def _values(ear, mar, missing=None):
    v = {n: 0.0 for n in FEATURE_NAMES}
    v.update(ear_left=ear, ear_right=ear, mar=mar)
    if missing:
        v[missing] = None
    return v


def test_model_outputs_follow_inputs_and_missing_input_gives_none():
    m = _model()
    open_eyes, closed_eyes = m.predict(_values(0.30, 0.1)), m.predict(_values(0.15, 0.1))
    assert open_eyes["eye_closed"] < 0.1 < 0.9 < closed_eyes["eye_closed"]
    assert m.predict(_values(0.3, 0.8))["yawn"] > 0.85 > 0.5 > m.predict(_values(0.3, 0.05))["yawn"]
    assert m.predict(_values(0.3, 0.1, missing="yaw")) == {"eye_closed": None, "yawn": None}


def test_model_file_round_trip_and_validation(tmp_path):
    m = _model()
    p = tmp_path / "m.json"
    m.save(p)
    again = FusionModel.load(p)
    assert again.predict(_values(0.2, 0.4)) == m.predict(_values(0.2, 0.4))
    bad = json.loads(p.read_text())
    bad["format"] = "something"
    with pytest.raises(ValueError):
        FusionModel(bad)
    bad = json.loads(p.read_text())
    bad["heads"]["yawn"]["layers"][1]["weights"] = [[1.0, 2.0, 3.0]]
    with pytest.raises(ValueError):
        FusionModel(bad)


def test_training_learns_a_separable_problem():
    rng = np.random.default_rng(0)
    X = rng.normal(0, 1, (900, 6))
    y = ((X[:, 0] - 0.5 * X[:, 3]) > 0.4).astype(float)  # rarer positive class
    mean, scale = fit_standardisation(X[:600])
    head, report = fit_head((X[:600] - mean) / scale, y[:600], (X[600:750] - mean) / scale, y[600:750], hidden=8)
    test = binary_metrics(y[750:], predict_layers(head["layers"], (X[750:] - mean) / scale))
    assert report["chosen"] in ("logistic", "mlp")
    assert test["f1"] > 0.9 and test["accuracy"] > 0.93


def test_labels_from_yolo_boxes():
    assert targets_from_roles({"eye_closed"}) == (1, 0)
    assert targets_from_roles({"eye_open", "yawn"}) == (0, 1)
    assert targets_from_roles({"eye_open", "eye_closed"}) == (None, 0)  # wink: no eye label
    assert targets_from_roles({"phone"}) == (None, None)  # phone-only photo: faces never checked
    assert targets_from_roles({"no_yawn"}) == (None, 0)


def test_extractor_adds_fusion_outputs_and_rules_use_them():
    face, size = _real_face()
    p = PerceptionResult(frame_size=size, face=face)
    plain = FeatureExtractor(Config().detector).extract(p, 0.0)
    fused = FeatureExtractor(Config().detector, fusion=_model()).extract(p, 0.0)
    assert plain.eye_closed_fusion is None and plain.yawn_fusion is None
    want = _model().predict(fusion_inputs(face, plain))
    assert fused.eye_closed_fusion is not None
    assert fused.eye_closed_fusion == want["eye_closed"] and fused.yawn_fusion == want["yawn"]

    cfg = Config()
    cfg.calibration.enabled = False
    engine = DecisionEngine(cfg)
    f = FrameFeatures(t=0.0, face_present=True, ear=0.3, ear_left=0.3, ear_right=0.3, mar=0.05, yaw=0.0, pitch=0.0,
                      roll=0.0, blink=0.05, jaw_open=0.02, eye_width_px=40.0, eye_closed_fusion=0.9, yawn_fusion=0.1)
    out = engine.step(f, ContextReading())
    assert out.states.eye_sources["fusion"] == 0.9
    apply_evidence_modes(cfg, "rules", "rules")
    assert cfg.evidence.eye_fusion == 0.0 and cfg.evidence.mouth_fusion == 0.0
    apply_evidence_modes(cfg, "fusion", "fusion")
    out = DecisionEngine(cfg).step(f, ContextReading())
    assert out.states.eye_evidence == 0.9 and out.states.eyes_closed


def _synthetic_table(path: Path, n=400, seed=0):
    rng = np.random.default_rng(seed)
    with open(path, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(COLUMNS))
        w.writeheader()
        for i in range(n):
            ear = rng.uniform(0.08, 0.35)
            mar = rng.uniform(0.0, 0.9)
            row = {k: round(float(rng.uniform(0, 0.3)), 5) for k in FEATURE_NAMES}
            blink = 0.9 if ear < 0.2 else 0.1
            row.update(ear_left=round(ear, 5), ear_right=round(ear, 5), mar=round(mar, 5), yaw=0.0, pitch=0.0, roll=0.0,
                       bs_eyeBlinkLeft=blink, bs_eyeBlinkRight=blink, bs_jawOpen=round(mar * 0.8, 5))
            row.update(image=f"p{i}.jpg", split="train" if i < 260 else "val" if i < 330 else "test",
                       group=f"person{i % 40}", eye_closed=int(ear < 0.2), yawn=int(mar > 0.55))
            w.writerow(row)


def test_train_script_writes_a_loadable_model_and_report(tmp_path):
    import train_fusion

    table = tmp_path / "table.csv"
    _synthetic_table(table)
    out = tmp_path / "fusion_model.json"
    assert train_fusion.main(["--table", str(table), "--out", str(out), "--hidden", "4"]) == 0
    model = FusionModel.load(out)
    assert set(model.heads) == {"eye_closed", "yawn"}
    report = json.loads((tmp_path / "fusion_model_report.json").read_text())
    for head in ("eye_closed", "yawn"):
        assert report["heads"][head]["test"]["fusion"]["f1"] > 0.85
        assert set(report["heads"][head]["test"]) == {"rules", "fusion", "rules_plus_fusion"}
    assert "Rules F1" in (tmp_path / "fusion_model_report.md").read_text()


def test_run_monitor_options():
    import run_monitor

    args = run_monitor.parse_args(["--fusion", "models/fusion_model.json", "--esp32-like", "--eye-mode", "fusion"])
    assert args.fusion == "models/fusion_model.json" and args.esp32_like and args.esp32_fps == 15.0
