"""Build a YOLO phone-detection dataset from State Farm / AUC distracted-driver images.

Both datasets are *image-classification* datasets (folders c0..c9); they have
no bounding boxes, which a YOLO detector needs. This script pseudo-labels them:

  * images from the phone classes (default c1-c4: texting / talking, right and
    left hand) are passed through a large pretrained "teacher" detector; every
    COCO "cell phone" box above --teacher-conf becomes a label (class 0 = phone)
  * images from the negative class (default c0: safe driving) get empty
    label files, teaching the model what "no phone" looks like
  * phone-class images where the teacher finds nothing are listed in
    needs_review.txt instead of being silently dropped

The split is done *by driver* (State Farm's driver_imgs_list.csv) when
available, so the same person never appears in both train and test.

IMPORTANT: pseudo-labels must be checked by a person (e.g. in CVAT, Label
Studio or Roboflow) before the test split is used to report results;
otherwise the evaluation measures agreement with the teacher model.

Example
-------
    python scripts/autolabel_phones.py --src data/state-farm/imgs/train \
        --driver-csv data/state-farm/driver_imgs_list.csv --out datasets/phone --teacher models/yolo11x.pt
"""

import argparse
import csv
import random
import shutil
import sys
from collections import defaultdict
from pathlib import Path

import yaml

import _bootstrap  # noqa: F401

PHONE_ALIASES = {"cell phone", "phone", "mobile phone", "cellphone"}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", required=True, help="folder containing class sub-folders c0..c9")
    ap.add_argument("--out", default="datasets/phone")
    ap.add_argument("--teacher", default="models/yolo11x.pt", help="large pretrained detector used as labeller")
    ap.add_argument("--teacher-conf", type=float, default=0.30)
    ap.add_argument("--phone-classes", nargs="+", default=["c1", "c2", "c3", "c4"])
    ap.add_argument("--negative-classes", nargs="+", default=["c0"])
    ap.add_argument("--negatives-per-class", type=int, default=1500, help="cap on negative images per class")
    ap.add_argument("--driver-csv", default=None, help="State Farm driver_imgs_list.csv for a subject-wise split")
    ap.add_argument("--split", nargs=3, type=float, default=[0.7, 0.15, 0.15], metavar=("TRAIN", "VAL", "TEST"))
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--device", default=None)
    args = ap.parse_args()

    from ultralytics import YOLO

    rng = random.Random(args.seed)
    src, out = Path(args.src), Path(args.out)
    teacher = YOLO(args.teacher)
    phone_ids = [k for k, v in teacher.names.items() if str(v).lower() in PHONE_ALIASES]

    items = []  # (path, class_folder)
    for cls in args.phone_classes:
        items += [(p, cls) for p in sorted((src / cls).glob("*.jpg"))]
    for cls in args.negative_classes:
        files = sorted((src / cls).glob("*.jpg"))
        rng.shuffle(files)
        items += [(p, cls) for p in files[: args.negatives_per_class]]

    # subject-wise split
    subject_of = {}
    if args.driver_csv:
        with open(args.driver_csv, newline="") as fh:
            for row in csv.DictReader(fh):
                subject_of[row["img"]] = row["subject"]
    groups = defaultdict(list)
    for p, cls in items:
        groups[subject_of.get(p.name, p.name)].append((p, cls))
    keys = sorted(groups)
    rng.shuffle(keys)
    n = len(keys)
    cut1, cut2 = int(n * args.split[0]), int(n * (args.split[0] + args.split[1]))
    if n >= 3:  # keep at least one group in every split
        cut1 = min(max(cut1, 1), n - 2)
        cut2 = min(max(cut2, cut1 + 1), n - 1)
    split_of = {k: ("train" if i < cut1 else "val" if i < cut2 else "test") for i, k in enumerate(keys)}
    if not args.driver_csv:
        print("WARNING: no --driver-csv; splitting by image, so the same driver can appear in several splits.")

    review = []
    counts = defaultdict(lambda: defaultdict(int))
    for key, members in groups.items():
        split = split_of[key]
        for p, cls in members:
            img_dir, lbl_dir = out / "images" / split, out / "labels" / split
            img_dir.mkdir(parents=True, exist_ok=True)
            lbl_dir.mkdir(parents=True, exist_ok=True)
            dest = img_dir / f"{cls}_{p.name}"
            shutil.copy2(p, dest)
            lines = []
            if cls in args.phone_classes:
                res = teacher.predict(str(p), conf=args.teacher_conf, classes=phone_ids, device=args.device, verbose=False)[0]
                for (cx, cy, w, h) in res.boxes.xywhn.cpu().numpy():
                    lines.append(f"0 {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}")
                if not lines:
                    review.append(str(dest))
            (lbl_dir / f"{cls}_{p.stem}.txt").write_text("\n".join(lines) + ("\n" if lines else ""))
            counts[split]["images"] += 1
            counts[split]["boxes"] += len(lines)

    yaml.safe_dump({"path": str(out.resolve()), "train": "images/train", "val": "images/val", "test": "images/test", "names": {0: "phone"}},
                   open(out / "data.yaml", "w"))
    (out / "needs_review.txt").write_text("\n".join(review) + "\n")
    for split in ("train", "val", "test"):
        print(f"{split:5s}: {counts[split]['images']} images, {counts[split]['boxes']} phone boxes")
    print(f"{len(review)} phone-class images without a teacher box -> {out / 'needs_review.txt'}")
    print(f"Dataset config: {out / 'data.yaml'}  (review labels before reporting test results!)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
