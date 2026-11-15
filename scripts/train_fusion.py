"""Train the picture-based fusion model and compare it with the rules.

    python scripts/train_fusion.py --table datasets/fusion/fusion_table.csv
    python scripts/train_fusion.py --table datasets/fusion/fusion_table.csv --out models/fusion_model.json --hidden 16

For each sign (eyes closed, yawning) a logistic model and a small neural
network are trained on the TRAIN rows; the better one on the VAL rows is kept.
The TEST rows are used once, for the report:

    rules           EAR + blendshape evidence with population thresholds
                    (pictures have no calibration phase)
    fusion          the trained model alone
    rules + fusion  how the live program combines them (evidence.eye_fusion /
                    mouth_fusion = 0.5 next to the other sources)

Output: the model file (load it with run_monitor.py --fusion, or import it in
the Android app: Settings > Import fusion model) and a report (.json + .md).
"""

import argparse
import datetime as dt
import json
import sys
from dataclasses import asdict
from pathlib import Path

import numpy as np

import _bootstrap  # noqa: F401
from dms.behaviors.drowsiness import DrowsinessDetector
from dms.behaviors.yawning import YawningDetector
from dms.classification.calibration import Baseline, Thresholds
from dms.config import Config
from dms.fusion.dataset import head_arrays, read_table
from dms.fusion.model import FORMAT, HEADS, FusionModel
from dms.fusion.train import binary_metrics, fit_head, fit_standardisation
from dms.types import FrameFeatures, FrameStates

TITLES = {"eye_closed": "Eyes closed", "yawn": "Yawning"}


def _num(row: dict, key: str):
    v = row.get(key, "")
    return None if v == "" else float(v)


def rule_evidence(row: dict, head: str, cfg: Config, thr: Thresholds, fusion_p=None):
    """Stage-3 evidence of one picture, exactly as the live program computes it."""
    l, r = _num(row, "ear_left"), _num(row, "ear_right")
    bl, br = _num(row, "bs_eyeBlinkLeft"), _num(row, "bs_eyeBlinkRight")
    dl, dr = _num(row, "bs_eyeLookDownLeft"), _num(row, "bs_eyeLookDownRight")
    f = FrameFeatures(
        t=0.0, face_present=True, ear_left=l, ear_right=r,
        ear=None if l is None or r is None else (l + r) / 2.0,
        mar=_num(row, "mar"), yaw=_num(row, "yaw"), pitch=_num(row, "pitch"), roll=_num(row, "roll"),
        blink=None if bl is None or br is None else (bl + br) / 2.0,
        look_down=None if dl is None or dr is None else (dl + dr) / 2.0,
        jaw_open=_num(row, "bs_jawOpen"),
        eye_closed_fusion=fusion_p if head == "eye_closed" else None,
        yawn_fusion=fusion_p if head == "yawn" else None,
    )
    st = FrameStates()
    if head == "eye_closed":
        DrowsinessDetector(cfg).frame_state(f, thr, st, mouth_guard=False)
        return st.eye_evidence
    YawningDetector(cfg).frame_state(f, thr, st)
    return st.mouth_evidence


def carve_val(rows, seed: int) -> int:
    """No val split in the table: move 15 % of the TRAIN people (or pictures) to val."""
    train = [r for r in rows if r["split"] == "train"]
    keys = sorted({r.get("group") or r["image"] for r in train})
    rng = np.random.default_rng(seed)
    val_keys = set(rng.choice(keys, size=max(1, int(round(0.15 * len(keys)))), replace=False)) if keys else set()
    moved = 0
    for r in train:
        if (r.get("group") or r["image"]) in val_keys:
            r["split"] = "val"
            moved += 1
    return moved


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter, allow_abbrev=False)
    ap.add_argument("--table", required=True, help="CSV from build_fusion_dataset.py")
    ap.add_argument("--out", default="models/fusion_model.json")
    ap.add_argument("--report", default=None, help="report path without extension (default: next to --out)")
    ap.add_argument("--hidden", type=int, default=16, help="hidden units of the neural-network candidate (0 = logistic only)")
    ap.add_argument("--heads", nargs="+", default=list(HEADS), choices=list(HEADS))
    ap.add_argument("--min-rows", type=int, default=20, help="minimum rows per class in train to train a head")
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args(argv)

    rows, features = read_table(Path(args.table))
    if not rows:
        raise SystemExit(f"{args.table} has no rows")
    notes = []
    if not any(r["split"] == "val" for r in rows):
        n = carve_val(rows, args.seed)
        notes.append(f"no val split in the table: {n} train rows (15 % of the people/pictures) were used for validation")

    train_rows = [[float(r[f]) for f in features] for r in rows
                  if r["split"] == "train" and all(r.get(f, "") != "" for f in features)]
    if not train_rows:
        raise SystemExit("no complete training rows")
    mean, scale = fit_standardisation(np.array(train_rows))

    cfg = Config()
    thr = Thresholds.from_baseline(Baseline(**asdict(cfg.calibration.defaults)), cfg.thresholds, calibrated=False)
    heads, report = {}, {"table": args.table, "trained": dt.datetime.now().isoformat(timespec="seconds"),
                         "notes": notes, "heads": {}}
    for head in args.heads:
        X, y, _ = head_arrays(rows, features, head, ("train",))
        Xv, yv, _ = head_arrays(rows, features, head, ("val",))
        Xt, yt, idx_t = head_arrays(rows, features, head, ("test",))
        counts = {"train": [int((y == 0).sum()), int((y == 1).sum())], "val": [int((yv == 0).sum()), int((yv == 1).sum())],
                  "test": [int((yt == 0).sum()), int((yt == 1).sum())]}
        if min(counts["train"]) < args.min_rows or not len(yv):
            msg = f"{head}: not enough labelled pictures (train no/yes = {counts['train']}, val rows = {len(yv)}); head skipped"
            print(msg)
            report["heads"][head] = {"skipped": msg, "counts": counts}
            continue
        head_model, fit_report = fit_head((X - mean) / scale, y, (Xv - mean) / scale, yv, hidden=args.hidden, seed=args.seed)
        heads[head] = head_model
        entry = {"counts": counts, **fit_report}
        if len(yt):
            model = FusionModel({"format": FORMAT, "features": features, "mean": mean.tolist(), "scale": scale.tolist(),
                                 "heads": {head: head_model}})
            p_fusion, p_rules, p_both = [], [], []
            for i in idx_t:
                values = {f: float(rows[i][f]) for f in features}
                p = model.predict(values)[head]
                p_fusion.append(p)
                p_rules.append(rule_evidence(rows[i], head, cfg, thr) or 0.0)
                p_both.append(rule_evidence(rows[i], head, cfg, thr, fusion_p=p) or 0.0)
            entry["test"] = {"rules": binary_metrics(yt, np.array(p_rules)),
                             "fusion": binary_metrics(yt, np.array(p_fusion)),
                             "rules_plus_fusion": binary_metrics(yt, np.array(p_both))}
        else:
            notes.append(f"{head}: no test rows, no test scores")
        report["heads"][head] = entry

    if not heads:
        raise SystemExit("no head could be trained (see the messages above)")
    info = {"trained": report["trained"], "table": args.table, "notes": notes,
            "heads": {h: {k: v for k, v in e.items() if k in ("counts", "chosen", "test")} for h, e in report["heads"].items()}}
    model = FusionModel({"format": FORMAT, "features": features, "mean": [round(v, 9) for v in mean.tolist()],
                         "scale": [round(v, 9) for v in scale.tolist()], "heads": heads, "info": info})
    model.save(args.out)

    base = Path(args.report) if args.report else Path(args.out).with_suffix("")
    base.parent.mkdir(parents=True, exist_ok=True)
    Path(str(base) + "_report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    lines = ["# Fusion model report", "", f"Table: `{args.table}` - trained {report['trained']}", ""]
    lines += [f"* {n}" for n in notes]
    lines += ["", "| Sign | Model kept | Test pictures (no/yes) | Rules F1 | Fusion F1 | Rules + fusion F1 |", "|---|---|---|---|---|---|"]
    for head, e in report["heads"].items():
        if "skipped" in e:
            lines.append(f"| {TITLES[head]} | skipped | {e['counts']['test']} | - | - | - |")
            continue
        t = e.get("test", {})
        f1 = lambda k: f"{t[k]['f1']:.3f}" if k in t else "-"  # noqa: E731
        lines.append(f"| {TITLES[head]} | {e['chosen']} | {e['counts']['test']} | {f1('rules')} | {f1('fusion')} | {f1('rules_plus_fusion')} |")
    lines += ["", "Rules = EAR / MAR and blendshape thresholds with population defaults (no calibration on single pictures).",
              "Rules + fusion = the live program with the fusion model loaded (equal weights)."]
    Path(str(base) + "_report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    print("\n".join(lines))
    print(f"\nModel: {args.out}\nReport: {base}_report.md")
    print(f"Use it: python scripts/run_monitor.py --fusion {args.out}   |   phone: Settings > Import fusion model")
    return 0


if __name__ == "__main__":
    sys.exit(main())
