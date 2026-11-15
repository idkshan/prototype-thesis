"""Write the decision settings as JSON for the Android app (Settings > Import tuned config).

    python scripts/export_android_config.py                                # defaults
    python scripts/export_android_config.py --config configs/tuned.yaml   # your tuned YAML
    python scripts/export_android_config.py --config configs/tuned.yaml --out exports/android/dms_config.json

The phone reads the same keys as configs/default.yaml (snake_case sections).
Model file paths are not used on the phone: it has its own face model and an
imported YOLO .tflite. Copy the JSON to the phone and import it in the app,
or put it in androidapp/app/src/main/assets/dms_config.json before building.
"""

import argparse
import json
import sys
from pathlib import Path

import _bootstrap  # noqa: F401
from dms.config import load_config

# Desktop-only settings: file paths of desktop models, and the optional eye CNN
# (eye_model), which the phone does not run (its eye evidence is EAR + blendshapes + YOLO).
DESKTOP_ONLY = {("face", "model_path"), ("detector", "model_path"), ("detector", "device")}
DESKTOP_ONLY_SECTIONS = {"eye_model", "fusion"}  # the phone imports its fusion model file in Settings


def android_config(path: str | None) -> dict:
    data = load_config(path).to_dict()
    if data.get("eye_model", {}).get("model_path"):
        print("Note: eye_model (eye CNN) is desktop-only; the phone ignores it.")
    for section in DESKTOP_ONLY_SECTIONS:
        data.pop(section, None)
    for section, key in DESKTOP_ONLY:
        data.get(section, {}).pop(key, None)
    return data


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter, allow_abbrev=False)
    ap.add_argument("--config", default=None, help="YAML config (default: built-in defaults)")
    ap.add_argument("--out", default="exports/android/dms_config.json")
    args = ap.parse_args(argv)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(android_config(args.config), indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {out}")
    print("Next: copy it to the phone, then in the app: Settings > Import tuned config.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
