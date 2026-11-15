import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from export_android_config import android_config, main  # noqa: E402

from dms.config import load_config  # noqa: E402


def test_android_config_has_no_desktop_paths():
    data = android_config(None)
    assert "model_path" not in data["face"] and "model_path" not in data["detector"]
    assert "eye_model" not in data
    assert data["alerts"]["camera_timeout_s"] == load_config().alerts.camera_timeout_s


def test_android_config_round_trips(tmp_path):
    out = tmp_path / "dms_config.json"
    assert main(["--out", str(out)]) == 0
    data = json.loads(out.read_text(encoding="utf-8"))
    assert load_config(overrides=data).to_dict() == load_config().to_dict()


def test_phone_golden_config_is_current():
    # The Kotlin tests compare the phone's defaults with this file. If this fails, the
    # desktop defaults changed: update the Kotlin Config.kt too, then run
    # python scripts/export_android_golden.py (see androidapp/README.md).
    golden = ROOT / "androidapp" / "app" / "src" / "test" / "resources" / "golden" / "config.json"
    assert json.loads(golden.read_text(encoding="utf-8")) == android_config(None)
