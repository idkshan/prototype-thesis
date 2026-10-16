"""Score a recorded session against human ground-truth labels (SO3 and SO5).

Ground truth (CSV, one row per labelled segment, times in video seconds):
    behavior,start_s,end_s
    yawning,63.2,68.9
    gaze_deviation,120.0,124.5
    phone_usage,300.1,309.8
    drowsiness,900.0,1020.0
Accepted behaviour names: drowsiness (drowsy), yawning (yawn), gaze_deviation
(gaze, looking_away), phone_usage (phone). Drowsiness segments may carry the
annotated level instead: slightly_drowsy (level1), moderately_drowsy (level2),
severely_drowsy (level3); they count as drowsiness AND are used to check the
reported drowsiness level (Level 1/2/3 criteria table).

Metrics per behaviour
  * frame level  - every frame is TP/FP/FN/TN (confusion matrix -> P, R, F1)
  * event level  - a labelled segment counts as detected if a predicted episode
                   overlaps it (+/- tolerance); a predicted episode that
                   overlaps no labelled segment is a false positive. Also
                   reports the median detection delay.
  * levels       - for level-annotated drowsiness segments: the highest
                   drowsiness level the system reported inside the segment vs.
                   the annotated level (confusion matrix, exact / within-one)
  * alarms       - alert episodes that start outside every labelled segment
                   are unnecessary alarms; reported per driving hour (SO5 target <= 0.5/h)

Replay mode (--replay) re-runs Stages 3-5 on the logged Stage-2 features with a
different config or evidence mode, without re-running the neural networks:
    python scripts/evaluate_behaviors.py --session results/sessions/S01 --labels labels/S01.csv \
        --replay --eye-mode ear --mouth-mode mar     # geometric-only ablation
"""

import argparse
import csv
import json
import re
import sys
from dataclasses import fields
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np

import _bootstrap  # noqa: F401
from dms.config import EYE_MODES, MOUTH_MODES, apply_evidence_modes, load_config
from dms.engine import DecisionEngine
from dms.types import BEHAVIORS, ContextReading, FrameFeatures

FLAG_OF = {"drowsiness": "drowsy", "yawning": "yawning", "gaze_deviation": "gaze_deviation", "phone_usage": "phone_usage"}
LEVEL_ALIASES = {
    "slightly_drowsy": 1, "level1": 1, "level_1": 1, "mild": 1,
    "moderately_drowsy": 2, "level2": 2, "level_2": 2, "moderate": 2,
    "severely_drowsy": 3, "level3": 3, "level_3": 3, "severe": 3,
}
ALIASES = {
    "drowsy": "drowsiness", "drowsiness": "drowsiness", **{k: "drowsiness" for k in LEVEL_ALIASES},
    "yawn": "yawning", "yawning": "yawning",
    "gaze": "gaze_deviation", "looking_away": "gaze_deviation", "gaze_deviation": "gaze_deviation",
    "phone": "phone_usage", "phone_use": "phone_usage", "phone_usage": "phone_usage",
}
TARGET_F1 = {"drowsiness": 0.85, "gaze_deviation": 0.85, "yawning": 0.80, "phone_usage": 0.80}  # Table 1.3

Segment = Tuple[float, float]


def _norm(name: str) -> str:
    return name.strip().lower().replace(" ", "_").replace("-", "_")


def load_labels(path: str, with_levels: bool = False):
    """Read a label CSV (Excel's UTF-8 BOM and extra spaces are tolerated).

    Returns {behaviour: [(start, end), ...]}; with ``with_levels`` also a list
    of (start, end, level 1..3) for level-annotated drowsiness segments.
    """
    segs: Dict[str, List[Segment]] = {b: [] for b in BEHAVIORS}
    levels: List[Tuple[float, float, int]] = []
    with open(path, newline="", encoding="utf-8-sig") as fh:
        reader = csv.DictReader(fh)
        reader.fieldnames = [_norm(f) for f in reader.fieldnames or []]
        missing = {"behavior", "start_s", "end_s"} - set(reader.fieldnames)
        if missing:
            raise SystemExit(f"{path}: missing column(s) {sorted(missing)}; the header must be behavior,start_s,end_s")
        for row in reader:
            if not (row.get("behavior") or "").strip():
                continue
            key = _norm(row["behavior"])
            name = ALIASES.get(key)
            if name is None:
                continue  # e.g. "alert"
            start, end = float(row["start_s"]), float(row["end_s"])
            segs[name].append((start, end))
            if key in LEVEL_ALIASES:
                levels.append((start, end, LEVEL_ALIASES[key]))
    return (segs, levels) if with_levels else segs


def level_index(name) -> int:
    """'Level 2 (moderately drowsy)' -> 2, 'Alert' -> 0."""
    m = re.search(r"Level\s*(\d)", str(name or ""))
    return int(m.group(1)) if m else 0


def load_frames(session: Path) -> List[dict]:
    with open(session / "frames.jsonl", encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]


def replay(frames: List[dict], cfg) -> Tuple[List[dict], List[dict]]:
    """Re-run Stages 3-5 on the logged features. Returns (per-frame flags, alert dicts)."""
    engine = DecisionEngine(cfg)
    known = {f.name for f in fields(FrameFeatures)}
    flags, alerts = [], []
    for rec in frames:
        f = FrameFeatures(**{k: v for k, v in rec["features"].items() if k in known})
        ctx = ContextReading(**rec["context"]) if rec.get("context") else ContextReading(driving_s=f.t - frames[0]["t"])
        out = engine.step(f, ctx)
        flags.append({"t": f.t, "behavior": {k: getattr(out.behavior, k) for k in FLAG_OF.values()},
                      "risk": {"tier": out.risk.tier, "drowsiness_level": out.risk.fatigue_level}})
        alerts += [a.__dict__ for a in out.alerts]
    return flags, alerts


def episodes(times: np.ndarray, on: np.ndarray, merge_gap: float = 0.5) -> List[Segment]:
    eps: List[Segment] = []
    start = prev = None
    for t, v in zip(times, on):
        if v:
            if start is None:
                start = t
            elif t - prev > merge_gap:
                eps.append((start, prev))
                start = t
            prev = t
    if start is not None:
        eps.append((start, prev))
    return eps


def overlaps(a: Segment, b: Segment, tol: float) -> bool:
    return a[0] <= b[1] + tol and b[0] <= a[1] + tol


def prf(tp, fp, fn):
    p = tp / (tp + fp) if tp + fp else 0.0
    r = tp / (tp + fn) if tp + fn else 0.0
    return p, r, (2 * p * r / (p + r) if p + r else 0.0)


def score(times: np.ndarray, pred: np.ndarray, gt: List[Segment], tol: float) -> dict:
    truth = np.zeros_like(pred, dtype=bool)
    for s, e in gt:
        truth |= (times >= s) & (times <= e)
    tp, fp = int((pred & truth).sum()), int((pred & ~truth).sum())
    fn, tn = int((~pred & truth).sum()), int((~pred & ~truth).sum())
    fp_, fr_, ff_ = prf(tp, fp, fn)
    pred_eps = episodes(times, pred)
    matched_gt = [any(overlaps(p, g, tol) for p in pred_eps) for g in gt]
    matched_pred = [any(overlaps(p, g, tol) for g in gt) for p in pred_eps]
    delays = [min(p[0] for p in pred_eps if overlaps(p, g, tol)) - g[0] for g, m in zip(gt, matched_gt) if m]
    e_tp, e_fp, e_fn = sum(matched_pred), len(pred_eps) - sum(matched_pred), len(gt) - sum(matched_gt)
    ep, er, ef = prf(e_tp, e_fp, e_fn)
    return {
        "frame": {"tp": tp, "fp": fp, "fn": fn, "tn": tn, "precision": round(fp_, 4), "recall": round(fr_, 4), "f1": round(ff_, 4)},
        "event": {"gt_events": len(gt), "pred_events": len(pred_eps), "tp": e_tp, "fp": e_fp, "fn": e_fn,
                  "precision": round(ep, 4), "recall": round(er, 4), "f1": round(ef, 4),
                  "median_delay_s": round(float(np.median(delays)), 2) if delays else None, "delays_s": [round(d, 2) for d in delays]},
    }


def score_levels(times: np.ndarray, pred_levels: np.ndarray, segs: List[Tuple[float, float, int]], tol: float) -> dict:
    """Compare the annotated drowsiness level with the highest level reported in each segment."""
    matrix = [[0] * 4 for _ in range(4)]  # matrix[true][pred], 0 = Alert
    pairs = []
    for s, e, lvl in segs:
        inside = (times >= s - tol) & (times <= e + tol)
        pred = int(pred_levels[inside].max()) if inside.any() else 0
        matrix[lvl][pred] += 1
        pairs.append({"start_s": s, "end_s": e, "true": lvl, "pred": pred})
    n = len(pairs)
    exact = sum(p["true"] == p["pred"] for p in pairs)
    near = sum(abs(p["true"] - p["pred"]) <= 1 for p in pairs)
    return {"segments": n, "exact": exact, "within_one": near,
            "exact_acc": round(exact / n, 4) if n else None, "within_one_acc": round(near / n, 4) if n else None,
            "confusion": matrix, "pairs": pairs}


def evaluate_session(session: Path, labels_path: str, tolerance: float = 1.0, cfg=None) -> dict:
    """Score one session folder against one label CSV (``cfg`` given = replay with that config)."""
    session = Path(session)
    frames = load_frames(session)
    labels, level_segs = load_labels(labels_path, with_levels=True)
    if cfg is not None:
        flag_rows, alerts = replay(frames, cfg)
    else:
        flag_rows = frames
        with open(session / "alerts.jsonl", encoding="utf-8") as fh:
            alerts = [json.loads(line) for line in fh if line.strip()]

    times = np.array([r["t"] for r in flag_rows])
    duration_h = (times[-1] - times[0]) / 3600.0 if len(times) > 1 else 0.0
    report = {"session": str(session), "labels": str(labels_path), "frames": len(times), "duration_min": round(duration_h * 60, 2),
              "replay": cfg is not None, "behaviors": {}}
    for b in BEHAVIORS:
        pred = np.array([bool(r["behavior"][FLAG_OF[b]]) for r in flag_rows])
        res = score(times, pred, labels[b], tolerance)
        res["target_f1"] = TARGET_F1[b]
        res["meets_target_event_f1"] = res["event"]["f1"] >= TARGET_F1[b] if labels[b] else None
        report["behaviors"][b] = res

    if level_segs:
        if all("drowsiness_level" in (r.get("risk") or {}) for r in flag_rows):
            pred_levels = np.array([level_index(r["risk"]["drowsiness_level"]) for r in flag_rows])
            report["drowsiness_levels"] = score_levels(times, pred_levels, level_segs, tolerance)
        else:
            report["drowsiness_levels"] = {"note": "session was recorded before levels were logged; use --replay"}

    all_gt = [s for segs in labels.values() for s in segs]
    starts = [a for a in alerts if a.get("new_episode")]
    unnecessary = [a for a in starts if not any(overlaps((a["t"], a["t"]), g, tolerance) for g in all_gt)]
    report["alarms"] = {
        "alert_episodes": len(starts),
        "unnecessary": len(unnecessary),
        "unnecessary_per_hour": round(len(unnecessary) / duration_h, 3) if duration_h > 0 else None,
        "target_per_hour": 0.5,
        "duration_h": round(duration_h, 4),
    }
    return report


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter, allow_abbrev=False)
    ap.add_argument("--session", required=True, help="folder written by run_monitor.py")
    ap.add_argument("--labels", required=True, help="ground-truth CSV")
    ap.add_argument("--tolerance", type=float, default=1.0, help="event matching tolerance in seconds")
    ap.add_argument("--replay", action="store_true", help="re-run Stages 3-5 on logged features")
    ap.add_argument("--config", default=None, help="config used for --replay")
    ap.add_argument("--eye-mode", choices=sorted(EYE_MODES), default=None)
    ap.add_argument("--mouth-mode", choices=sorted(MOUTH_MODES), default=None)
    ap.add_argument("--out", default=None, help="write the report JSON here")
    args = ap.parse_args()

    session = Path(args.session)
    cfg = apply_evidence_modes(load_config(args.config), args.eye_mode, args.mouth_mode) if args.replay else None
    report = evaluate_session(session, args.labels, args.tolerance, cfg)

    print(f"Session {session}  ({report['duration_min']} min, {report['frames']} frames){'  [replay]' if args.replay else ''}")
    print(f"{'behaviour':16s} {'frame P/R/F1':>22s} {'event P/R/F1':>22s} {'GT':>4s} {'delay':>6s}  target")
    for b, r in report["behaviors"].items():
        fr, ev = r["frame"], r["event"]
        print(f"{b:16s} {fr['precision']:6.2f} {fr['recall']:6.2f} {fr['f1']:6.2f}   {ev['precision']:6.2f} {ev['recall']:6.2f} {ev['f1']:6.2f} "
              f"{ev['gt_events']:4d} {str(ev['median_delay_s']):>6s}  F1>={r['target_f1']}")
    lv = report.get("drowsiness_levels")
    if lv and "segments" in lv:
        print(f"drowsiness level: {lv['exact']}/{lv['segments']} segments exact, {lv['within_one']}/{lv['segments']} within one level")
    a = report["alarms"]
    print(f"alarm episodes {a['alert_episodes']}, unnecessary {a['unnecessary']} ({a['unnecessary_per_hour']}/h, target <= 0.5/h)")
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
