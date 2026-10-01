from pathlib import Path

import pytest
import yaml

from dms.config import Config, apply_evidence_modes, load_config

ROOT = Path(__file__).resolve().parents[1]


def test_default_yaml_matches_dataclass_defaults():
    with open(ROOT / "configs" / "default.yaml", encoding="utf-8") as fh:
        from_yaml = yaml.safe_load(fh)
    assert from_yaml == Config().to_dict()


def test_yaml_file_loads():
    cfg = load_config(ROOT / "configs" / "default.yaml")
    assert cfg.temporal.phone_confirm_s == 3.0


def test_unknown_keys_are_rejected():
    with pytest.raises(KeyError):
        load_config(overrides={"thresholds": {"ear_ratio_typo": 0.5}})


def test_overrides_and_modes():
    cfg = load_config(overrides={"detector": {"model_path": "models/yolo26n.pt"}})
    assert cfg.detector.model_path == "models/yolo26n.pt"
    apply_evidence_modes(cfg, "ear", "blendshape")
    assert cfg.evidence.eye_blendshape == 0.0 and cfg.evidence.eye_ear == 1.0
    assert cfg.evidence.mouth_mar == 0.0 and cfg.evidence.mouth_blendshape == 1.0
