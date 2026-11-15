"""Training table of the fusion model: labelled pictures -> numbers.

The labels come from a YOLO dataset (e.g. the driver-state dataset of
scripts/build_driver_state_dataset.py): the boxes in a picture's label file
say what the picture shows.

    eye_closed target   1 = closed-eye box(es) and no open-eye box
                        0 = open-eye box(es) and no closed-eye box
                        empty = no eye boxes, or one open and one closed (wink)
    yawn target         1 = a yawn box
                        0 = a no_yawn box, or face parts were labelled (eye boxes)
                            without a yawn box
                        empty = no face-part boxes at all (e.g. phone-only photos,
                            whose faces were never checked for yawns)

Only numbers are stored (no pictures), one row per picture with a face.
"""

from __future__ import annotations

import csv
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Set, Tuple

import numpy as np

from ..config import ObjectDetectorConfig
from .features import FEATURE_NAMES

TARGETS = ("eye_closed", "yawn")
META_COLUMNS = ("image", "split", "group") + TARGETS
COLUMNS = META_COLUMNS + FEATURE_NAMES
FACE_PART_ROLES = {"eye_open", "eye_closed", "yawn", "no_yawn"}


def role_map(cfg: ObjectDetectorConfig) -> Dict[str, str]:
    """Class name (lower case) -> role, the same mapping as the live detector."""
    out = {}
    for role, names in (("phone", cfg.phone_class_names), ("eye_open", cfg.eye_open_class_names),
                        ("eye_closed", cfg.eye_closed_class_names), ("yawn", cfg.yawn_class_names),
                        ("no_yawn", cfg.no_yawn_class_names)):
        for n in names:
            out[n.lower()] = role
    return out


def roles_in_label_file(path: Path, class_names: Dict[int, str], roles: Dict[str, str]) -> Set[str]:
    found: Set[str] = set()
    if not path.exists():
        return found
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if not parts:
            continue
        try:
            cls = int(float(parts[0]))
        except ValueError:
            continue
        role = roles.get(str(class_names.get(cls, "")).lower())
        if role:
            found.add(role)
    return found


def targets_from_roles(found: Set[str]) -> Tuple[Optional[int], Optional[int]]:
    """(eye_closed, yawn) targets of one picture; None = not labelled for that sign."""
    eye = None
    if "eye_closed" in found and "eye_open" not in found:
        eye = 1
    elif "eye_open" in found and "eye_closed" not in found:
        eye = 0
    yawn = None
    if "yawn" in found:
        yawn = 1
    elif found & FACE_PART_ROLES:
        yawn = 0
    return eye, yawn


def write_table(path: Path, rows: Iterable[dict]) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    n = 0
    with open(path, "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(COLUMNS))
        writer.writeheader()
        for row in rows:
            writer.writerow({k: ("" if row.get(k) is None else row.get(k)) for k in COLUMNS})
            n += 1
    return n


def read_table(path: Path) -> Tuple[List[dict], List[str]]:
    """Rows (strings) and the feature columns present in the file."""
    with open(path, newline="", encoding="utf-8") as fh:
        reader = csv.DictReader(fh)
        feats = [c for c in reader.fieldnames or [] if c not in META_COLUMNS]
        return list(reader), feats


def head_arrays(rows: List[dict], features: List[str], target: str, splits: Tuple[str, ...]) -> Tuple[np.ndarray, np.ndarray, List[int]]:
    """X, y for one target; rows without that label or with a missing input are left out."""
    X, y, idx = [], [], []
    for i, r in enumerate(rows):
        if r["split"] not in splits or r.get(target, "") == "":
            continue
        vals = [r.get(f, "") for f in features]
        if any(v == "" for v in vals):
            continue
        X.append([float(v) for v in vals])
        y.append(float(r[target]))
        idx.append(i)
    return np.array(X, dtype=float).reshape(-1, len(features)), np.array(y, dtype=float), idx
