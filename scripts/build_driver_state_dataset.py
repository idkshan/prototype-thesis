"""Build the YOLO *driver-state* dataset from the approved datasets.

One YOLO model then detects four classes in a single inference:

    0 eye_closed     1 eye_open     2 phone     3 yawn

Sources
-------
* ``--phone-dataset``: your reviewed phone dataset (Roboflow "YOLOv8" export
  of datasets/phone, built from State Farm with autolabel_phones.py). Its
  phone boxes become class 2 (phone). Its existing train/val/test split is kept.
* ``--yawdd``: the YawDD videos (folder searched recursively for .avi/.mp4).
  Frames are sampled, and the split is made *by person* (e.g. "12-Female").

Automatic labelling (to be checked by a person in Roboflow afterwards)
--------------------------------------------------------------------
For every image MediaPipe finds the face; then
* each eye gets a box, labelled eye_closed / eye_open from EAR and the
  eyeBlink score compared with the person's normal values (per-video median;
  population defaults for still photos). Unclear eyes are not guessed:
  video frames with an unclear eye are skipped, still photos are listed
  in review_eyes.txt.
* the mouth gets a "yawn" box when MAR and jawOpen are clearly wide open.
  In YawDD videos named "Normal" or "Talking" a yawn is never labelled.
* closed eyes and yawns are rare, so video frames that contain them are
  sampled more densely than ordinary frames.

Phone photos also contain eyes; they are labelled too, otherwise YOLO would
learn that eyes in those photos are "background".

Example
-------
    python scripts/build_driver_state_dataset.py --phone-dataset datasets/phone_roboflow \
        --yawdd data/yawdd --out datasets/driver_state
"""

from __future__ import annotations

import argparse
import random
import re
import shutil
import sys
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import cv2
import numpy as np
import yaml

import _bootstrap  # noqa: F401
from dms.config import FaceModelConfig
from dms.features.geometry import LEFT_EYE_EAR, RIGHT_EYE_EAR, eye_aspect_ratio, mouth_aspect_ratio
from dms.perception.eye_state import LEFT_EYE_CONTOUR, RIGHT_EYE_CONTOUR

# Alphabetical, the order Roboflow uses in its exports, so a split re-exported
# from Roboflow keeps the same class ids. The pipeline reads classes by NAME.
CLASSES = ["eye_closed", "eye_open", "phone", "yawn"]
EYE_CLOSED, EYE_OPEN, PHONE, YAWN = range(4)
PHONE_ALIASES = {"cell phone", "phone", "mobile phone", "cellphone", "mobile_phone", "smartphone"}
OUTER_LIPS = [61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185]
IMG_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
VID_EXTS = {".avi", ".mp4", ".mov", ".mkv"}

# population defaults for still photos (no per-person baseline available)
DEFAULT_BASE = {"ear": 0.28, "blink": 0.15, "mar": 0.05, "jaw": 0.03}


# ------------------------------------------------------------- measurement
def measure(face) -> Dict[str, float]:
    pts, bs = face.landmarks, face.blendshapes
    return {
        "ear_r": eye_aspect_ratio(pts, RIGHT_EYE_EAR),
        "ear_l": eye_aspect_ratio(pts, LEFT_EYE_EAR),
        "blink_r": bs.get("eyeBlinkRight", 0.0),
        "blink_l": bs.get("eyeBlinkLeft", 0.0),
        "mar": mouth_aspect_ratio(pts),
        "jaw": bs.get("jawOpen", 0.0),
    }


def ramp(x: float, zero_at: float, half_at: float) -> float:
    span = half_at - zero_at
    return float(np.clip(0.5 * (x - zero_at) / span, 0.0, 1.0)) if abs(span) > 1e-9 else 0.0


def eye_state(ear: float, blink: float, base: dict, args) -> Optional[int]:
    """EYE_CLOSED / EYE_OPEN, or None when unclear."""
    score = (ramp(ear, base["ear"], base["ear"] * args.ear_ratio) + ramp(blink, base["blink"], base["blink"] + args.blink_delta)) / 2
    if score >= args.sure_closed:
        return EYE_CLOSED
    if score <= args.sure_open:
        return EYE_OPEN
    return None


def mouth_state(mar: float, jaw: float, base: dict, args) -> Optional[bool]:
    """True = yawn, False = not yawning, None = unclear."""
    score = (ramp(mar, base["mar"], base["mar"] + args.mar_delta) + ramp(jaw, base["jaw"], base["jaw"] + args.jaw_delta)) / 2
    if score >= args.sure_yawn:
        return True
    if score <= args.sure_no_yawn:
        return False
    return None


def box_from_points(pts: np.ndarray, sx: float, sy: float, min_h_ratio: float, w: int, h: int) -> Tuple[float, float, float, float]:
    x1, y1 = pts[:, 0].min(), pts[:, 1].min()
    x2, y2 = pts[:, 0].max(), pts[:, 1].max()
    cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
    bw = (x2 - x1) * sx
    bh = max((y2 - y1) * sy, bw * min_h_ratio)
    x1, x2 = max(0, cx - bw / 2), min(w, cx + bw / 2)
    y1, y2 = max(0, cy - bh / 2), min(h, cy + bh / 2)
    return (x1 + x2) / 2 / w, (y1 + y2) / 2 / h, (x2 - x1) / w, (y2 - y1) / h


def face_labels(face, m: dict, base: dict, args, w: int, h: int, allow_yawn: bool) -> Tuple[List[str], bool]:
    """YOLO label lines for eyes and mouth; second value False if something was unclear."""
    lines, clear = [], True
    for contour, ear, blink in ((RIGHT_EYE_CONTOUR, m["ear_r"], m["blink_r"]), (LEFT_EYE_CONTOUR, m["ear_l"], m["blink_l"])):
        st = eye_state(ear, blink, base, args)
        if st is None:
            clear = False
            continue
        cx, cy, bw, bh = box_from_points(face.landmarks[contour, :2], 1.5, 2.2, 0.45, w, h)
        lines.append(f"{st} {cx:.6f} {cy:.6f} {bw:.6f} {bh:.6f}")
    ms = mouth_state(m["mar"], m["jaw"], base, args)
    if ms is None or (ms and not allow_yawn):
        clear = False
    elif ms:
        cx, cy, bw, bh = box_from_points(face.landmarks[OUTER_LIPS, :2], 1.2, 1.2, 0.0, w, h)
        lines.append(f"{YAWN} {cx:.6f} {cy:.6f} {bw:.6f} {bh:.6f}")
    return lines, clear


# ------------------------------------------------------------- phone dataset
def find_splits(root: Path) -> Dict[str, Path]:
    """Image folders of a YOLO dataset in either layout (Roboflow or Ultralytics)."""
    found = {}
    for split, names in (("train", ["train"]), ("val", ["valid", "val"]), ("test", ["test"])):
        for n in names:
            for cand in (root / n / "images", root / "images" / n):
                if cand.is_dir():
                    found[split] = cand
                    break
            if split in found:
                break
    return found


def phone_class_ids(root: Path) -> set:
    for y in list(root.glob("*.yaml")):
        names = yaml.safe_load(open(y)).get("names")
        if names is None:
            continue
        names = dict(enumerate(names)) if isinstance(names, list) else {int(k): v for k, v in names.items()}
        ids = {k for k, v in names.items() if str(v).lower() in PHONE_ALIASES}
        if ids:
            return ids
    return {0}


def label_of(img: Path) -> Path:
    parts = list(img.parts)
    for i in range(len(parts) - 1, -1, -1):
        if parts[i] == "images":
            parts[i] = "labels"
            break
    return Path(*parts).with_suffix(".txt")


# ------------------------------------------------------------- YawDD
def subject_of(video: Path) -> str:
    m = re.match(r"(\d+)-?(Female|Male)", video.stem, re.IGNORECASE)
    if m:
        return f"{m.group(2).lower()}{int(m.group(1))}"
    gender = next((p.lower() for p in video.parts if p.lower() in ("female", "male")), "")
    num = re.match(r"(\d+)", video.stem)
    return f"{gender}{num.group(1)}" if num else video.stem


def video_frames(path: Path, stride: int):
    cap = cv2.VideoCapture(str(path))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    i = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if i % stride == 0:
            yield i / fps, frame
        i += 1
    cap.release()


# ------------------------------------------------------------- main
def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter, allow_abbrev=False)
    ap.add_argument("--phone-dataset", default=None, help="reviewed phone dataset (Roboflow YOLOv8 export)")
    ap.add_argument("--yawdd", default=None, help="folder with YawDD videos")
    ap.add_argument("--out", default="datasets/driver_state")
    ap.add_argument("--face-model", default="models/face_landmarker.task")
    ap.add_argument("--scan-stride", type=int, default=3, help="analyse every Nth video frame")
    ap.add_argument("--every-sec", type=float, default=1.0, help="keep an ordinary frame every N seconds")
    ap.add_argument("--event-every-sec", type=float, default=0.2, help="keep closed-eye / yawn frames every N seconds")
    ap.add_argument("--split", nargs=3, type=float, default=[0.7, 0.15, 0.15])
    ap.add_argument("--seed", type=int, default=0)
    # labelling thresholds (same meaning as the live model's thresholds)
    ap.add_argument("--ear-ratio", type=float, default=0.70)
    ap.add_argument("--blink-delta", type=float, default=0.35)
    ap.add_argument("--mar-delta", type=float, default=0.40)
    ap.add_argument("--jaw-delta", type=float, default=0.35)
    ap.add_argument("--sure-closed", type=float, default=0.65)
    ap.add_argument("--sure-open", type=float, default=0.35)
    ap.add_argument("--sure-yawn", type=float, default=0.65)
    ap.add_argument("--sure-no-yawn", type=float, default=0.35)
    args = ap.parse_args()
    if not args.phone_dataset and not args.yawdd:
        ap.error("give --phone-dataset and/or --yawdd")

    from dms.perception.face_landmarker import FaceLandmarkerModel

    out = Path(args.out)
    for split in ("train", "val", "test"):
        (out / "images" / split).mkdir(parents=True, exist_ok=True)
        (out / "labels" / split).mkdir(parents=True, exist_ok=True)
    counts = defaultdict(lambda: np.zeros(len(CLASSES) + 1, dtype=int))  # per split: classes + images
    review: List[str] = []

    def save(split: str, name: str, image: Optional[np.ndarray], src: Optional[Path], lines: List[str]) -> None:
        dest = out / "images" / split / name
        if src is not None:
            shutil.copy2(src, dest)
        else:
            cv2.imwrite(str(dest), image)
        (out / "labels" / split / (Path(name).stem + ".txt")).write_text("\n".join(lines) + ("\n" if lines else ""))
        counts[split][-1] += 1
        for ln in lines:
            counts[split][int(ln.split()[0])] += 1

    landmarker = FaceLandmarkerModel(FaceModelConfig(model_path=args.face_model), video_mode=False)

    # ---- phone dataset (still photos): keep split, add eyes/mouth
    if args.phone_dataset:
        root = Path(args.phone_dataset)
        splits = find_splits(root)
        if not splits:
            raise SystemExit(f"No train/valid/test image folders found in {root}")
        phone_ids = phone_class_ids(root)
        print(f"Phone dataset: splits {sorted(splits)}, phone class ids {sorted(phone_ids)}")
        for split, img_dir in splits.items():
            for img_path in sorted(p for p in img_dir.iterdir() if p.suffix.lower() in IMG_EXTS):
                lines = []
                lp = label_of(img_path)
                if lp.exists():
                    for ln in lp.read_text().splitlines():
                        parts = ln.split()
                        if len(parts) >= 5 and int(float(parts[0])) in phone_ids:
                            lines.append(" ".join([str(PHONE)] + parts[1:5]))
                img = cv2.imread(str(img_path))
                if img is None:
                    continue
                h, w = img.shape[:2]
                face = landmarker.detect(img, 0.0)
                if face is not None:
                    face_lines, clear = face_labels(face, measure(face), DEFAULT_BASE, args, w, h, allow_yawn=True)
                    lines += face_lines
                    if not clear:
                        review.append(f"{split}/{img_path.name}")
                save(split, f"sf_{img_path.name}", None, img_path, lines)
        print("  phone photos done")

    # ---- YawDD videos: per-person split, per-video baseline, dense sampling of events
    if args.yawdd:
        videos = sorted(p for p in Path(args.yawdd).rglob("*") if p.suffix.lower() in VID_EXTS)
        groups = defaultdict(list)
        for v in videos:
            groups[subject_of(v)].append(v)
        keys = sorted(groups)
        random.Random(args.seed).shuffle(keys)
        n = len(keys)
        c1, c2 = int(n * args.split[0]), int(n * (args.split[0] + args.split[1]))
        if n >= 3:
            c1 = min(max(c1, 1), n - 2)
            c2 = min(max(c2, c1 + 1), n - 1)
        split_of = {k: ("train" if i < c1 else "val" if i < c2 else "test") for i, k in enumerate(keys)}
        print(f"YawDD: {len(videos)} videos from {n} people")
        for vi, video in enumerate(videos, 1):
            split = split_of[subject_of(video)]
            name_l = video.stem.lower()
            allow_yawn = not ("normal" in name_l or "talking" in name_l)
            frames = []  # (t, frame, face, measurements)
            for t, frame in video_frames(video, args.scan_stride):
                face = landmarker.detect(frame, 0.0)
                if face is not None:
                    frames.append((t, frame, face, measure(face)))
            if len(frames) < 10:
                continue
            base = {
                "ear": float(np.median([m["ear_r"] + m["ear_l"] for *_, m in frames]) / 2),
                "blink": float(np.median([m["blink_r"] + m["blink_l"] for *_, m in frames]) / 2),
                "mar": float(np.median([m["mar"] for *_, m in frames])),
                "jaw": float(np.median([m["jaw"] for *_, m in frames])),
            }
            last_kept = -1e9
            kept = 0
            for t, frame, face, m in frames:
                h, w = frame.shape[:2]
                lines, clear = face_labels(face, m, base, args, w, h, allow_yawn)
                if not clear:
                    continue  # plenty of frames: never guess on video
                event = any(ln.split()[0] in (str(EYE_CLOSED), str(YAWN)) for ln in lines)
                if t - last_kept < (args.event_every_sec if event else args.every_sec):
                    continue
                last_kept = t
                kept += 1
                save(split, f"yawdd_{video.stem}_{int(t * 1000):07d}.jpg", frame, None, lines)
            print(f"  [{vi}/{len(videos)}] {video.name}: {kept} frames -> {split}")

    landmarker.close()
    yaml.safe_dump({"path": str(out.resolve()), "train": "images/train", "val": "images/val", "test": "images/test",
                    "names": dict(enumerate(CLASSES))}, open(out / "data.yaml", "w"), sort_keys=False)
    (out / "review_eyes.txt").write_text("\n".join(review) + "\n")
    print("\nsplit   images  " + "  ".join(f"{c:>10s}" for c in CLASSES))
    for split in ("train", "val", "test"):
        c = counts[split]
        print(f"{split:6s} {c[-1]:7d}  " + "  ".join(f"{v:10d}" for v in c[:-1]))
    print(f"\n{len(review)} phone photos with an unclear eye or mouth -> {out / 'review_eyes.txt'}")
    print(f"Dataset config: {out / 'data.yaml'}")
    print("NEXT: upload to Roboflow, check the boxes (at least the test split), export YOLOv8, then train.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
