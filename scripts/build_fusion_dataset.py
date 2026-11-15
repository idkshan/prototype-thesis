"""Turn labelled pictures into the training table of the fusion model.

    python scripts/build_fusion_dataset.py --data datasets/driver_state/data.yaml
    python scripts/build_fusion_dataset.py --data datasets/driver_state/data.yaml --groups datasets/driver_state/split_manifest.csv

For every picture of the dataset's train / val / test splits that has eye or
mouth boxes, MediaPipe measures the face (as in the live program) and one row
of numbers is written: EAR per eye, MAR, head angles, the 52 blendshapes and
the picture's labels (see dms/fusion/dataset.py). Pictures without a face are
counted and skipped. The dataset's own split is kept, so a person in the test
split stays out of training. Only numbers are written, no pictures.

Next: python scripts/train_fusion.py --table datasets/fusion/fusion_table.csv
"""

import argparse
import json
import sys
import time
from pathlib import Path

import cv2

import _bootstrap  # noqa: F401
from benchmark_yolo import label_path, load_split
from dms.config import Config
from dms.data.splits import group_from_name, load_group_map
from dms.features.extractor import FeatureExtractor
from dms.fusion import fusion_inputs
from dms.fusion.dataset import role_map, roles_in_label_file, targets_from_roles, write_table
from dms.perception.face_landmarker import FaceLandmarkerModel
from dms.types import PerceptionResult

SPLIT_NAMES = {"train": "train", "val": "val", "valid": "val", "test": "test"}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter, allow_abbrev=False)
    ap.add_argument("--data", required=True, help="data.yaml of the YOLO dataset (driver-state dataset)")
    ap.add_argument("--out", default="datasets/fusion/fusion_table.csv")
    ap.add_argument("--splits", nargs="+", default=["train", "val", "test"])
    ap.add_argument("--groups", default=None, help="CSV mapping pictures to people (split_manifest.csv), stored for reference")
    ap.add_argument("--face-model", default="models/face_landmarker.task")
    ap.add_argument("--max-per-split", type=int, default=None, help="quick test: only the first N labelled pictures per split")
    args = ap.parse_args(argv)

    cfg = Config()
    cfg.face.model_path = args.face_model
    roles = role_map(cfg.detector)
    groups = load_group_map(Path(args.groups)) if args.groups else None
    landmarker = FaceLandmarkerModel(cfg.face, video_mode=False)  # pictures are independent
    extractor = FeatureExtractor(cfg.detector)

    rows, stats = [], {}
    t0 = time.time()
    try:
        for split in args.splits:
            try:
                images, names = load_split(args.data, split)
            except SystemExit as exc:
                print(f"skip split {split}: {exc}")
                continue
            name = SPLIT_NAMES.get(split, split)
            st = stats.setdefault(name, {"pictures": 0, "labelled": 0, "no_face": 0, "unreadable": 0, "rows": 0,
                                          "eye_closed": [0, 0], "yawn": [0, 0]})
            for img_path in images:
                st["pictures"] += 1
                eye, yawn = targets_from_roles(roles_in_label_file(label_path(img_path), names, roles))
                if eye is None and yawn is None:
                    continue
                st["labelled"] += 1
                if args.max_per_split and st["labelled"] > args.max_per_split:
                    break
                img = cv2.imread(str(img_path))
                if img is None:
                    st["unreadable"] += 1
                    continue
                face = landmarker.detect(img, 0.0)
                if face is None:
                    st["no_face"] += 1
                    continue
                h, w = img.shape[:2]
                f = extractor.extract(PerceptionResult(frame_size=(w, h), face=face), 0.0)
                row = {"image": img_path.name, "split": name,
                       "group": group_from_name(img_path.name, groups) if groups else None,
                       "eye_closed": eye, "yawn": yawn}
                row.update({k: (None if v is None else round(v, 6)) for k, v in fusion_inputs(face, f).items()})
                rows.append(row)
                st["rows"] += 1
                for target, value in (("eye_closed", eye), ("yawn", yawn)):
                    if value is not None:
                        st[target][value] += 1
                if st["rows"] % 500 == 0:
                    print(f"  {name}: {st['rows']} pictures measured ({time.time() - t0:.0f} s)")
    finally:
        landmarker.close()

    out = Path(args.out)
    n = write_table(out, rows)
    summary = {"data": args.data, "rows": n, "splits": stats}
    out.with_suffix(".summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"\nWrote {n} rows to {out}")
    for name, st in stats.items():
        print(f"  {name:5s}: {st['rows']} rows | eyes open/closed {st['eye_closed'][0]}/{st['eye_closed'][1]} | "
              f"no-yawn/yawn {st['yawn'][0]}/{st['yawn'][1]} | no face found in {st['no_face']} of {st['labelled']} labelled pictures")
    print(f"Next: python scripts/train_fusion.py --table {out}")
    return 0 if n else 1


if __name__ == "__main__":
    sys.exit(main())
