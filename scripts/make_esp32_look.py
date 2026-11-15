"""Make an ESP32-look copy of a YOLO dataset (train the model for the prototype camera).

    python scripts/make_esp32_look.py --data datasets/driver_state/data.yaml --out datasets/driver_state_esp32
    python scripts/make_esp32_look.py --data datasets/driver_state/data.yaml --out datasets/driver_state_esp32 --copies 2

Most training pictures are bigger and sharper than what the ESP32-CAM sends
(480 x 320, strong JPEG compression, often dim). This writes a new dataset:

    images/train       the original pictures + --copies ESP32-look versions of each
    images/val         the original pictures + one ESP32-look version of each
    images/test        the original test pictures (unchanged)
    images/test_esp32  ESP32-look versions of the test pictures
    labels/...         the same boxes (pictures are only shrunk, never cropped)
    data.yaml               train on this (scripts/train_yolo.py --data ...)
    data_esp32_test.yaml    the same, but "test" = the ESP32-look test pictures:
                            benchmark_yolo.py --split test with it shows how the model
                            does on ESP32-like pictures

Each picture's ESP32-look copies stay in the same split as the picture, so no
person moves between train / val / test. See dms/camera/esp32_look.py for what
is changed (size, compression, darkness, noise, blur, colour).
"""

import argparse
import shutil
import sys
from pathlib import Path

import cv2
import numpy as np
import yaml

import _bootstrap  # noqa: F401
from benchmark_yolo import label_path, load_split
from dms.camera.esp32_look import esp32_augment

SPLITS = {"train": "train", "val": "val", "valid": "val", "test": "test"}


def _copy_pair(img: Path, out_img: Path, out_lbl: Path) -> None:
    shutil.copy2(img, out_img)
    lbl = label_path(img)
    if lbl.exists():
        shutil.copy2(lbl, out_lbl)
    else:
        out_lbl.write_text("")  # a picture without objects (negative example)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter, allow_abbrev=False)
    ap.add_argument("--data", required=True, help="data.yaml of the source dataset")
    ap.add_argument("--out", required=True, help="folder for the new dataset (must not exist yet)")
    ap.add_argument("--copies", type=int, default=1, help="ESP32-look versions per training picture")
    ap.add_argument("--replace", action="store_true", help="train only on ESP32-look pictures (drop the originals from train)")
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args(argv)

    out = Path(args.out)
    if out.exists() and any(out.iterdir()):
        raise SystemExit(f"{out} already exists and is not empty: choose a new --out folder")
    rng = np.random.default_rng(args.seed)
    names = None
    counts = {}
    for split in ("train", "val", "test"):
        try:
            images, names = load_split(args.data, split)
        except SystemExit:
            if split == "val":
                try:
                    images, names = load_split(args.data, "valid")
                except SystemExit:
                    print("no val split: skipped")
                    continue
            else:
                print(f"no {split} split: skipped")
                continue
        targets = [split] + (["test_esp32"] if split == "test" else [])
        for t in targets:
            (out / "images" / t).mkdir(parents=True, exist_ok=True)
            (out / "labels" / t).mkdir(parents=True, exist_ok=True)
        n_orig = n_esp = 0
        copies = args.copies if split == "train" else 1
        for img in images:
            keep_original = not (split == "train" and args.replace)
            if keep_original:
                _copy_pair(img, out / "images" / split / img.name, out / "labels" / split / (img.stem + ".txt"))
                n_orig += 1
            picture = cv2.imread(str(img))
            if picture is None:
                print(f"  unreadable, skipped: {img}")
                continue
            dest_split = "test_esp32" if split == "test" else split
            for k in range(copies):
                stem = f"{img.stem}__esp32_{k}"
                cv2.imwrite(str(out / "images" / dest_split / f"{stem}.jpg"), esp32_augment(picture, rng),
                            [cv2.IMWRITE_JPEG_QUALITY, 95])  # already compressed ESP32-style; store without extra loss
                lbl = label_path(img)
                (out / "labels" / dest_split / f"{stem}.txt").write_text(lbl.read_text() if lbl.exists() else "")
                n_esp += 1
        counts[split] = (n_orig, n_esp)
        print(f"  {split}: {n_orig} original + {n_esp} ESP32-look pictures")

    if names is None:
        raise SystemExit(f"no pictures found through {args.data}")
    base = {"path": str(out.resolve()), "train": "images/train", "val": "images/val", "test": "images/test",
            "names": [names[i] for i in sorted(names)]}
    if "val" not in counts:
        base.pop("val")
    (out / "data.yaml").write_text(yaml.safe_dump(base, sort_keys=False), encoding="utf-8")
    (out / "data_esp32_test.yaml").write_text(yaml.safe_dump({**base, "test": "images/test_esp32"}, sort_keys=False), encoding="utf-8")
    print(f"\nWrote {out / 'data.yaml'} and {out / 'data_esp32_test.yaml'}")
    print(f"Train:   python scripts/train_yolo.py --data {out / 'data.yaml'} --models models/yolo11n.pt --imgsz 320")
    print(f"Compare: python scripts/benchmark_yolo.py --models <best.pt> --data {out / 'data_esp32_test.yaml'} --split test")
    return 0


if __name__ == "__main__":
    sys.exit(main())
