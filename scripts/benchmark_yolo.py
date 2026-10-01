"""Compare YOLO models (YOLOv8 / YOLO11 / YOLO26) for mobile phone detection.

Every model is measured under identical conditions:
  * size on disk (MB) of each exported format
  * parameters (M) and computational cost (GFLOPs) at the chosen input size
  * NMS-free head or not (YOLO26 is designed to be end-to-end / NMS-free)
  * latency per image = pre-processing + inference + post-processing (NMS),
    mean / median / p95 and the resulting FPS
  * accuracy on a labelled split (optional): precision, recall and F1 at the
    deployment confidence threshold, AP@0.5, and image-level phone presence
    P/R/F1 (the quantity the behaviour detector actually uses)

The script reports numbers only; it does not rank the models. Desktop CPU
latency is a *proxy*: final mobile claims need on-device measurements
(see docs/Chapter4_Design_Considerations.md, "On-device benchmark protocol").

Examples
--------
    # speed/size only, bundled sample images
    python scripts/benchmark_yolo.py --models models/yolov8n.pt models/yolo11n.pt models/yolo26n.pt

    # full comparison on a labelled phone dataset, two input sizes, three formats
    python scripts/benchmark_yolo.py --models models/yolov8n.pt models/yolo11n.pt models/yolo26n.pt \
        --data datasets/phone/data.yaml --split test --imgsz 320 640 --formats pytorch onnx ncnn
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import platform
import shutil
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import cv2
import numpy as np

import _bootstrap  # noqa: F401

PHONE_ALIASES = {"cell phone", "phone", "mobile phone", "cellphone", "mobile_phone", "smartphone"}
IMG_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
EXPORT_ARGS = {
    "onnx": {"format": "onnx"},
    "torchscript": {"format": "torchscript"},
    "openvino": {"format": "openvino"},
    "ncnn": {"format": "ncnn"},
    "ncnn_fp16": {"format": "ncnn", "half": True},
    "tflite": {"format": "tflite"},
    "tflite_fp16": {"format": "tflite", "half": True},
    "tflite_int8": {"format": "tflite", "int8": True},
}


# ------------------------------------------------------------------ dataset
def load_split(data_yaml: str, split: str) -> Tuple[List[Path], Dict[int, str]]:
    import yaml

    with open(data_yaml, encoding="utf-8") as fh:
        d = yaml.safe_load(fh)
    root = Path(d.get("path") or Path(data_yaml).parent)
    if not root.is_absolute():
        root = (Path(data_yaml).parent / root).resolve()
    entry = d.get(split) or d.get("val")
    entries = entry if isinstance(entry, list) else [entry]
    images: List[Path] = []
    for e in entries:
        p = Path(e) if Path(e).is_absolute() else root / e
        if p.is_dir():
            images += sorted(q for q in p.rglob("*") if q.suffix.lower() in IMG_EXTS)
        elif p.suffix == ".txt":
            images += [Path(line.strip()) if Path(line.strip()).is_absolute() else root / line.strip() for line in p.read_text().splitlines() if line.strip()]
    names = d["names"]
    names = {i: n for i, n in enumerate(names)} if isinstance(names, list) else {int(k): v for k, v in names.items()}
    return images, names


def label_path(img: Path) -> Path:
    s = str(img)
    key = f"{os.sep}images{os.sep}"
    i = s.rfind(key)
    if i >= 0:
        s = s[:i] + f"{os.sep}labels{os.sep}" + s[i + len(key):]
    return Path(s).with_suffix(".txt")


def read_gt(img: Path, w: int, h: int, classes: set) -> np.ndarray:
    lp = label_path(img)
    boxes = []
    if lp.exists():
        for line in lp.read_text().splitlines():
            parts = line.split()
            if len(parts) >= 5 and int(float(parts[0])) in classes:
                cx, cy, bw, bh = (float(v) for v in parts[1:5])
                boxes.append([(cx - bw / 2) * w, (cy - bh / 2) * h, (cx + bw / 2) * w, (cy + bh / 2) * h])
    return np.array(boxes, dtype=float).reshape(-1, 4)


# ------------------------------------------------------------------ metrics
def iou_matrix(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    if len(a) == 0 or len(b) == 0:
        return np.zeros((len(a), len(b)))
    x1 = np.maximum(a[:, None, 0], b[None, :, 0])
    y1 = np.maximum(a[:, None, 1], b[None, :, 1])
    x2 = np.minimum(a[:, None, 2], b[None, :, 2])
    y2 = np.minimum(a[:, None, 3], b[None, :, 3])
    inter = np.clip(x2 - x1, 0, None) * np.clip(y2 - y1, 0, None)
    area = lambda r: (r[:, 2] - r[:, 0]) * (r[:, 3] - r[:, 1])
    return inter / (area(a)[:, None] + area(b)[None, :] - inter + 1e-9)


def match(pred: np.ndarray, scores: np.ndarray, gt: np.ndarray, iou_thr: float = 0.5) -> np.ndarray:
    """Greedy matching in descending score order; returns TP flags per prediction."""
    order = np.argsort(-scores)
    tp = np.zeros(len(pred), dtype=bool)
    used = np.zeros(len(gt), dtype=bool)
    ious = iou_matrix(pred, gt)
    for i in order:
        if len(gt) == 0:
            break
        cand = np.where(~used, ious[i], -1.0)
        j = int(np.argmax(cand))
        if cand[j] >= iou_thr:
            tp[i], used[j] = True, True
    return tp


def average_precision(scores: np.ndarray, tp: np.ndarray, n_gt: int) -> float:
    if n_gt == 0:
        return float("nan")
    if len(scores) == 0:
        return 0.0
    order = np.argsort(-scores)
    tpc = np.cumsum(tp[order])
    fpc = np.cumsum(~tp[order])
    recall = tpc / n_gt
    precision = tpc / (tpc + fpc)
    mrec = np.concatenate([[0.0], recall, [1.0]])
    mpre = np.concatenate([[1.0], precision, [0.0]])
    mpre = np.flip(np.maximum.accumulate(np.flip(mpre)))
    idx = np.where(mrec[1:] != mrec[:-1])[0]
    return float(np.sum((mrec[idx + 1] - mrec[idx]) * mpre[idx + 1]))


def prf(tp: int, fp: int, fn: int) -> Tuple[float, float, float]:
    p = tp / (tp + fp) if tp + fp else 0.0
    r = tp / (tp + fn) if tp + fn else 0.0
    f = 2 * p * r / (p + r) if p + r else 0.0
    return p, r, f


def evaluate(model, images: List[Path], gt_classes: set, imgsz: int, conf: float, device: str,
             pred_classes: Optional[set] = None, nms_iou: float = 0.5) -> dict:
    """Detection metrics for the phone class. ``pred_classes`` overrides which model
    class ids count as phones (default: matched by name)."""
    phone_ids = pred_classes if pred_classes is not None else {k for k, v in model.names.items() if str(v).lower() in PHONE_ALIASES}
    all_scores, all_tp = [], []
    n_gt = 0
    img_tp = img_fp = img_fn = 0
    for path in images:
        img = cv2.imread(str(path))
        if img is None:
            continue
        h, w = img.shape[:2]
        gt = read_gt(path, w, h, gt_classes)
        n_gt += len(gt)
        res = model.predict(img, imgsz=imgsz, conf=0.001, iou=nms_iou, device=device, verbose=False, max_det=300)[0]
        if res.boxes is not None and len(res.boxes):
            keep = np.isin(res.boxes.cls.cpu().numpy().astype(int), list(phone_ids))
            boxes = res.boxes.xyxy.cpu().numpy()[keep]
            scores = res.boxes.conf.cpu().numpy()[keep]
        else:
            boxes, scores = np.zeros((0, 4)), np.zeros(0)
        tp = match(boxes, scores, gt)
        all_scores.append(scores)
        all_tp.append(tp)
        detected = bool((scores >= conf).any())
        img_tp += int(detected and len(gt) > 0)
        img_fp += int(detected and len(gt) == 0)
        img_fn += int(not detected and len(gt) > 0)
    scores = np.concatenate(all_scores) if all_scores else np.zeros(0)
    tp = np.concatenate(all_tp) if all_tp else np.zeros(0, dtype=bool)
    sel = scores >= conf
    p, r, f1 = prf(int(tp[sel].sum()), int((~tp[sel]).sum()), n_gt - int(tp[sel].sum()))
    ip, ir, if1 = prf(img_tp, img_fp, img_fn)
    # F1 across thresholds (informative only; choose the operating point on a validation split)
    best_f1, best_conf = 0.0, conf
    for c in np.linspace(0.05, 0.95, 19):
        s = scores >= c
        _, _, f = prf(int(tp[s].sum()), int((~tp[s]).sum()), n_gt - int(tp[s].sum()))
        if f > best_f1:
            best_f1, best_conf = f, float(c)
    return {
        "images": len(images),
        "gt_boxes": n_gt,
        "precision": round(p, 4),
        "recall": round(r, 4),
        "f1": round(f1, 4),
        "ap50": round(average_precision(scores, tp, n_gt), 4),
        "img_precision": round(ip, 4),
        "img_recall": round(ir, 4),
        "img_f1": round(if1, 4),
        "best_f1": round(best_f1, 4),
        "best_f1_conf": round(best_conf, 2),
    }


# ------------------------------------------------------------------ speed
def latency(model, frames: List[np.ndarray], imgsz: int, conf: float, device: str, runs: int, warmup: int) -> dict:
    for i in range(warmup):
        model.predict(frames[i % len(frames)], imgsz=imgsz, conf=conf, device=device, verbose=False)
    pre, inf, post, wall = [], [], [], []
    for i in range(runs):
        t0 = time.perf_counter()
        res = model.predict(frames[i % len(frames)], imgsz=imgsz, conf=conf, device=device, verbose=False)[0]
        wall.append((time.perf_counter() - t0) * 1000)
        pre.append(res.speed["preprocess"])
        inf.append(res.speed["inference"])
        post.append(res.speed["postprocess"])
    total = np.array(pre) + np.array(inf) + np.array(post)
    return {
        "pre_ms": round(float(np.mean(pre)), 2),
        "inference_ms": round(float(np.mean(inf)), 2),
        "post_ms": round(float(np.mean(post)), 2),
        "total_ms_mean": round(float(total.mean()), 2),
        "total_ms_median": round(float(np.median(total)), 2),
        "total_ms_p95": round(float(np.percentile(total, 95)), 2),
        "fps": round(1000.0 / float(np.median(total)), 1),
        "wall_ms_median": round(float(np.median(wall)), 2),
    }


# ------------------------------------------------------------------ export
def path_size_mb(p: Path) -> float:
    if p.is_dir():
        return sum(f.stat().st_size for f in p.rglob("*") if f.is_file()) / 1e6
    return p.stat().st_size / 1e6


def export(pt_path: Path, fmt: str, imgsz: int, out_dir: Path, data: Optional[str]) -> Path:
    from ultralytics import YOLO

    kwargs = dict(EXPORT_ARGS[fmt], imgsz=imgsz)
    if kwargs.get("int8") and data:
        kwargs["data"] = data  # representative images for INT8 calibration
    produced = Path(YOLO(str(pt_path)).export(**kwargs))
    stem = f"{pt_path.stem}_{imgsz}_{fmt}"
    if produced.is_dir():  # e.g. yolo11n_ncnn_model/
        dest = out_dir / (stem + "_ncnn_model" if fmt.startswith("ncnn") else stem)
    else:
        dest = out_dir / (stem + produced.suffix)
    if dest.exists():
        shutil.rmtree(dest) if dest.is_dir() else dest.unlink()
    shutil.move(str(produced), dest)
    return dest


# ------------------------------------------------------------------ main
def sample_frames(images: List[Path], n: int = 16) -> List[np.ndarray]:
    frames = [cv2.imread(str(p)) for p in images[:n]]
    frames = [f for f in frames if f is not None]
    if not frames:
        import ultralytics

        assets = Path(ultralytics.__file__).parent / "assets"
        frames = [cv2.imread(str(p)) for p in sorted(assets.glob("*.jpg"))]
    return frames


def environment() -> dict:
    import torch
    import ultralytics

    info = {"python": platform.python_version(), "platform": platform.platform(), "processor": platform.processor() or platform.machine(),
            "cpu_count": os.cpu_count(), "torch": torch.__version__, "ultralytics": ultralytics.__version__}
    try:
        import onnxruntime

        info["onnxruntime"] = onnxruntime.__version__
    except ImportError:
        pass
    return info


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--models", nargs="+", default=["models/yolov8n.pt", "models/yolo11n.pt", "models/yolo26n.pt"])
    ap.add_argument("--imgsz", nargs="+", type=int, default=[640])
    ap.add_argument("--formats", nargs="+", default=["pytorch"], choices=["pytorch", *EXPORT_ARGS])
    ap.add_argument("--data", default=None, help="YOLO data.yaml with a labelled split (phone boxes)")
    ap.add_argument("--split", default="test")
    ap.add_argument("--gt-classes", nargs="*", type=int, default=None, help="dataset class ids that are phones (auto from names)")
    ap.add_argument("--conf", type=float, default=0.35, help="deployment confidence threshold (same as the pipeline)")
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--runs", type=int, default=100)
    ap.add_argument("--warmup", type=int, default=10)
    ap.add_argument("--out", default="results/yolo_benchmark")
    args = ap.parse_args()

    from ultralytics import YOLO
    from ultralytics.utils.torch_utils import get_flops, get_num_params

    out = Path(args.out)
    (out / "exports").mkdir(parents=True, exist_ok=True)
    images: List[Path] = []
    gt_classes: set = set()
    if args.data:
        images, names = load_split(args.data, args.split)
        gt_classes = set(args.gt_classes) if args.gt_classes else {k for k, v in names.items() if str(v).lower() in PHONE_ALIASES}
        if not gt_classes and len(names) == 1:
            gt_classes = {0}
        if not gt_classes:
            raise SystemExit(f"No phone class found in {names}; pass --gt-classes")
        print(f"Dataset: {len(images)} images in split '{args.split}', phone class ids {sorted(gt_classes)}")
    frames = sample_frames(images)

    rows = []
    for m in args.models:
        pt = Path(m)
        torch_model = YOLO(str(pt))
        params = get_num_params(torch_model.model) / 1e6
        nms_free = bool(torch_model.model.yaml.get("end2end", False))
        for imgsz in args.imgsz:
            gflops = get_flops(torch_model.model, imgsz)
            for fmt in args.formats:
                label = f"{pt.stem} @{imgsz} [{fmt}]"
                print(f"\n=== {label} ===")
                try:
                    path = pt if fmt == "pytorch" else export(pt, fmt, imgsz, out / "exports", args.data)
                    model = YOLO(str(path), task="detect")
                    row = {"model": pt.stem, "format": fmt, "imgsz": imgsz, "size_mb": round(path_size_mb(path), 2),
                           "params_m": round(params, 3), "gflops": round(gflops, 2), "nms_free": nms_free}
                    row.update(latency(model, frames, imgsz, args.conf, args.device, args.runs, args.warmup))
                    if images:
                        row.update(evaluate(model, images, gt_classes, imgsz, args.conf, args.device))
                    rows.append(row)
                    print(json.dumps(row))
                except Exception as exc:  # keep benchmarking the other models/formats
                    print(f"FAILED {label}: {exc}")
                    rows.append({"model": pt.stem, "format": fmt, "imgsz": imgsz, "error": str(exc)[:200]})

    meta = {"environment": environment(), "args": vars(args), "note": "Desktop measurements are a proxy for mobile performance."}
    (out / "results.json").write_text(json.dumps({"meta": meta, "rows": rows}, indent=2))
    keys = list(dict.fromkeys(k for r in rows for k in r))
    with open(out / "results.csv", "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=keys)
        w.writeheader()
        w.writerows(rows)
    cols = [c for c in ["model", "format", "imgsz", "size_mb", "params_m", "gflops", "nms_free", "inference_ms", "post_ms",
                        "total_ms_median", "total_ms_p95", "fps", "precision", "recall", "f1", "ap50", "img_f1", "error"] if c in keys]
    md = ["| " + " | ".join(cols) + " |", "|" + "---|" * len(cols)]
    md += ["| " + " | ".join(str(r.get(c, "")) for c in cols) + " |" for r in rows]
    env = meta["environment"]
    header = f"Measured on {env['processor']} ({env['cpu_count']} threads), {env['platform']}, torch {env['torch']}, ultralytics {env['ultralytics']}.\n\n"
    (out / "results.md").write_text(header + "\n".join(md) + "\n")
    print("\n" + header + "\n".join(md))
    print(f"\nSaved to {out}/results.(csv|json|md)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
