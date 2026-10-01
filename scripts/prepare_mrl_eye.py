"""Convert the MRL Eye Dataset into a YOLO classification dataset (open / closed).

MRL file names encode the labels:
    s0001_00001_0_0_0_0_0_01.png
    subject_image_gender_glasses_EYESTATE_reflections_lighting_sensor
    eye state: 0 = closed, 1 = open

The split is by subject so that no person appears in more than one split.
Output layout (what ``yolo classify train`` expects):
    datasets/mrl_eye/{train,val,test}/{open,closed}/*.png

Then train e.g.:
    python scripts/train_yolo.py --task classify --data datasets/mrl_eye \
        --models models/yolov8n-cls.pt models/yolo11n-cls.pt models/yolo26n-cls.pt --imgsz 96 --epochs 30
and enable it in the pipeline with ``--eye-model runs/classify/yolo11n-cls/weights/best.pt``.
"""

import argparse
import os
import random
import shutil
import sys
from collections import defaultdict
from pathlib import Path

import _bootstrap  # noqa: F401


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", required=True, help="unzipped mrlEyes_2018_01 folder")
    ap.add_argument("--out", default="datasets/mrl_eye")
    ap.add_argument("--split", nargs=3, type=float, default=[0.7, 0.15, 0.15])
    ap.add_argument("--max-per-subject", type=int, default=0, help="optional cap to balance subjects (0 = all)")
    ap.add_argument("--link", action="store_true", help="symlink instead of copying (saves disk)")
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    rng = random.Random(args.seed)
    by_subject = defaultdict(list)
    for p in Path(args.src).rglob("*.png"):
        parts = p.stem.split("_")
        if len(parts) < 8:
            continue
        by_subject[parts[0]].append((p, "closed" if parts[4] == "0" else "open"))
    subjects = sorted(by_subject)
    if not subjects:
        print("No MRL images found.")
        return 1
    rng.shuffle(subjects)
    n = len(subjects)
    cut1, cut2 = int(n * args.split[0]), int(n * (args.split[0] + args.split[1]))
    if n >= 3:  # keep at least one group in every split
        cut1 = min(max(cut1, 1), n - 2)
        cut2 = min(max(cut2, cut1 + 1), n - 1)
    counts = defaultdict(lambda: defaultdict(int))
    for i, s in enumerate(subjects):
        split = "train" if i < cut1 else "val" if i < cut2 else "test"
        files = by_subject[s]
        if args.max_per_subject:
            rng.shuffle(files)
            files = files[: args.max_per_subject]
        for p, label in files:
            dest_dir = Path(args.out) / split / label
            dest_dir.mkdir(parents=True, exist_ok=True)
            dest = dest_dir / p.name
            if args.link:
                if not dest.exists():
                    os.symlink(p.resolve(), dest)
            else:
                shutil.copy2(p, dest)
            counts[split][label] += 1
    for split in ("train", "val", "test"):
        print(f"{split:5s}: open={counts[split]['open']:6d} closed={counts[split]['closed']:6d}")
    print(f"{n} subjects -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
