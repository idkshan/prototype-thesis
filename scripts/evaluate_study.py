"""Evaluate the detection model on the participant study (validation / test splits).

The participants are NOT used for training: the YOLO models are trained on the
approved public datasets only. The participant sessions are split by person:
  * validation - used to tune the thresholds / persistence times (configs/*.yaml)
  * test       - touched once, with the final config, for the reported results

Manifest (CSV, one row per recorded session):
    participant,session,labels,split,condition
    P01,P01_day,labels/P01_day.csv,validation,day
    P01,P01_night,labels/P01_night.csv,validation,night
    P02,P02_day,labels/P02_day.csv,test,day
  * session   - folder name under --sessions (written by process_videos.py) or a path
  * labels    - ground-truth CSV (evaluate_behaviors.py format); relative paths are
                read from the manifest's folder
  * split     - validation or test (a participant must stay in ONE split)
  * condition - free text used for grouping, e.g. day / night (optional)

Examples
--------
    # tune on the validation split (replay = re-run Stages 3-5 with this config)
    python scripts/evaluate_study.py --manifest data/study/manifest.csv --split validation \
        --replay --config configs/tuned.yaml --tag tuned

    # final, reported numbers: the test split, once
    python scripts/evaluate_study.py --manifest data/study/manifest.csv --split test --replay --config configs/tuned.yaml --tag final

    # ablation on the test split: AI only vs thresholds only vs both
    python scripts/evaluate_study.py --manifest data/study/manifest.csv --split test --replay --eye-mode yolo --mouth-mode yolo --tag ai_only

Results go to results/study_eval/<tag>/: per_session.csv, summary.csv,
summary.md (thesis-ready tables) and report.json.

Pooled metrics add up the TP/FP/FN of every session before computing P/R/F1
(each event counts once); per-participant mean +/- SD of the event F1 shows the
spread between drivers.
"""

import argparse
import csv
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path
from typing import Dict, List

import _bootstrap  # noqa: F401
from dms.config import EYE_MODES, MOUTH_MODES, apply_evidence_modes, load_config
from dms.types import BEHAVIORS
from evaluate_behaviors import TARGET_F1, evaluate_session, prf

SPLITS = ("validation", "test")


def _norm(name: str) -> str:
    return name.strip().lower().replace(" ", "_")


def load_manifest(path: Path, sessions_dir: Path) -> List[dict]:
    rows = []
    with open(path, newline="", encoding="utf-8-sig") as fh:
        reader = csv.DictReader(fh)
        reader.fieldnames = [_norm(f) for f in reader.fieldnames or []]
        missing = {"participant", "session", "labels", "split"} - set(reader.fieldnames)
        if missing:
            raise SystemExit(f"{path}: missing column(s) {sorted(missing)}")
        for i, row in enumerate(reader, 2):
            if not (row.get("session") or "").strip():
                continue
            split = _norm(row["split"])
            split = {"val": "validation", "valid": "validation", "testing": "test"}.get(split, split)
            if split not in SPLITS:
                raise SystemExit(f"{path} line {i}: split must be validation or test, not {row['split']!r}")
            session = Path(row["session"].strip())
            if not (session / "frames.jsonl").exists():
                session = sessions_dir / row["session"].strip()
            labels = Path(row["labels"].strip())
            if not labels.is_absolute() and not labels.exists():
                labels = path.parent / labels
            rows.append({"participant": row["participant"].strip(), "session": session, "labels": labels, "split": split,
                         "condition": (row.get("condition") or "").strip() or "all"})
    leaks = defaultdict(set)
    for r in rows:
        leaks[r["participant"]].add(r["split"])
    both = sorted(p for p, s in leaks.items() if len(s) > 1)
    if both:
        raise SystemExit(f"Participant(s) in BOTH validation and test (data leakage): {both}. Keep each person in one split.")
    return rows


def pooled(reports: List[dict], participants: List[str]) -> Dict[str, dict]:
    """Aggregate a list of evaluate_session() reports."""
    out = {}
    hours = sum(r["alarms"]["duration_h"] for r in reports)
    for b in BEHAVIORS:
        f = {k: sum(r["behaviors"][b]["frame"][k] for r in reports) for k in ("tp", "fp", "fn")}
        e = {k: sum(r["behaviors"][b]["event"][k] for r in reports) for k in ("tp", "fp", "fn")}
        fp_, fr_, ff_ = prf(f["tp"], f["fp"], f["fn"])
        ep, er, ef = prf(e["tp"], e["fp"], e["fn"])
        delays = [d for r in reports for d in r["behaviors"][b]["event"].get("delays_s", [])]
        per_person = defaultdict(lambda: {"tp": 0, "fp": 0, "fn": 0})
        for r, p in zip(reports, participants):
            for k in ("tp", "fp", "fn"):
                per_person[p][k] += r["behaviors"][b]["event"][k]
        f1s = [prf(c["tp"], c["fp"], c["fn"])[2] for c in per_person.values() if c["tp"] + c["fn"] > 0]
        out[b] = {
            "gt_events": e["tp"] + e["fn"], "detected": e["tp"], "false_events": e["fp"],
            "event_precision": round(ep, 4), "event_recall": round(er, 4), "event_f1": round(ef, 4),
            "frame_precision": round(fp_, 4), "frame_recall": round(fr_, 4), "frame_f1": round(ff_, 4),
            "participant_f1_mean": round(statistics.mean(f1s), 4) if f1s else None,
            "participant_f1_sd": round(statistics.stdev(f1s), 4) if len(f1s) > 1 else None,
            "median_delay_s": round(statistics.median(delays), 2) if delays else None,
            "false_events_per_hour": round(e["fp"] / hours, 3) if hours > 0 else None,
            "target_f1": TARGET_F1[b],
            "meets_target": (ef >= TARGET_F1[b]) if e["tp"] + e["fn"] > 0 else None,
        }
    unnecessary = sum(r["alarms"]["unnecessary"] for r in reports)
    out["alarms"] = {
        "hours": round(hours, 3), "alert_episodes": sum(r["alarms"]["alert_episodes"] for r in reports),
        "unnecessary": unnecessary, "unnecessary_per_hour": round(unnecessary / hours, 3) if hours > 0 else None,
        "target_per_hour": 0.5,
    }
    matrix = [[0] * 4 for _ in range(4)]
    for r in reports:
        lv = r.get("drowsiness_levels") or {}
        for i, row in enumerate(lv.get("confusion", [])):
            for j, v in enumerate(row):
                matrix[i][j] += v
    n = sum(sum(row) for row in matrix)
    if n:
        exact = sum(matrix[i][i] for i in range(4))
        near = sum(matrix[i][j] for i in range(4) for j in range(4) if abs(i - j) <= 1)
        out["drowsiness_levels"] = {"segments": n, "exact_acc": round(exact / n, 4), "within_one_acc": round(near / n, 4),
                                    "confusion": matrix}
    return out


def markdown(groups: Dict[str, dict], meta: dict) -> str:
    lines = [f"# Participant study evaluation ({meta['tag']})", "",
             f"Split: **{meta['split']}** | sessions: {meta['sessions']} | participants: {meta['participants']} | "
             f"{'replay with ' + (meta['config'] or 'default config') if meta['replay'] else 'as recorded'}"
             + (f" | eye mode {meta['eye_mode']}" if meta.get("eye_mode") else "")
             + (f" | mouth mode {meta['mouth_mode']}" if meta.get("mouth_mode") else ""), ""]
    for name, g in groups.items():
        a = g["alarms"]
        lines += [f"## {name}", "",
                  f"Driving time {a['hours'] * 60:.1f} min; alert episodes {a['alert_episodes']}; "
                  f"unnecessary alarms {a['unnecessary']} = **{a['unnecessary_per_hour']}/h** (target <= 0.5/h)", "",
                  "| Behaviour | GT events | Detected | False | Event P | Event R | Event F1 | Target | Frame F1 | "
                  "F1 per participant (mean ± SD) | Median delay (s) |",
                  "|---|---|---|---|---|---|---|---|---|---|---|"]
        for b in BEHAVIORS:
            r = g[b]
            ok = "—" if r["meets_target"] is None else ("✓" if r["meets_target"] else "✗")
            spread = "—" if r["participant_f1_mean"] is None else (
                f"{r['participant_f1_mean']:.2f}" + (f" ± {r['participant_f1_sd']:.2f}" if r["participant_f1_sd"] is not None else ""))
            num = (lambda k: f"{r[k]:.2f}") if r["gt_events"] else (lambda k: "—")  # no labelled events: only false events count
            lines.append(f"| {b} | {r['gt_events']} | {r['detected']} | {r['false_events']} | {num('event_precision')} | "
                         f"{num('event_recall')} | {num('event_f1')} | {r['target_f1']} {ok} | {num('frame_f1')} | {spread} | "
                         f"{r['median_delay_s'] if r['median_delay_s'] is not None else '—'} |")
        lv = g.get("drowsiness_levels")
        if lv:
            names = ["Alert", "Level 1", "Level 2", "Level 3"]
            lines += ["", f"Drowsiness level (highest level reported inside each annotated segment): "
                          f"exact {lv['exact_acc']:.2f}, within one level {lv['within_one_acc']:.2f} ({lv['segments']} segments)", "",
                      "| Annotated \\ Reported | " + " | ".join(names) + " |", "|---|---|---|---|---|"]
            for i in range(1, 4):
                lines.append(f"| {names[i]} | " + " | ".join(str(v) for v in lv["confusion"][i]) + " |")
        lines.append("")
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter, allow_abbrev=False)
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--sessions", default="results/study_sessions", help="folder with the session folders")
    ap.add_argument("--split", choices=["validation", "test", "all"], default="validation")
    ap.add_argument("--replay", action="store_true", help="re-run Stages 3-5 on the logged features (needed to try a new config)")
    ap.add_argument("--config", default=None, help="config for --replay")
    ap.add_argument("--eye-mode", choices=sorted(EYE_MODES), default=None)
    ap.add_argument("--mouth-mode", choices=sorted(MOUTH_MODES), default=None)
    ap.add_argument("--tolerance", type=float, default=1.0, help="event matching tolerance (s)")
    ap.add_argument("--tag", default=None, help="name of this run (output sub-folder)")
    ap.add_argument("--out", default="results/study_eval")
    args = ap.parse_args()
    if (args.eye_mode or args.mouth_mode or args.config) and not args.replay:
        print("Note: --config / --eye-mode / --mouth-mode only take effect with --replay; adding --replay.")
        args.replay = True

    manifest = Path(args.manifest)
    rows = load_manifest(manifest, Path(args.sessions))
    if args.split != "all":
        rows = [r for r in rows if r["split"] == args.split]
    if not rows:
        raise SystemExit(f"No sessions for split '{args.split}' in {manifest}")
    missing = [str(r["session"]) for r in rows if not (r["session"] / "frames.jsonl").exists()]
    missing += [str(r["labels"]) for r in rows if not r["labels"].exists()]
    if missing:
        raise SystemExit("Missing files (run process_videos.py / check the label paths):\n  " + "\n  ".join(missing))

    cfg = apply_evidence_modes(load_config(args.config), args.eye_mode, args.mouth_mode) if args.replay else None
    reports = []
    for n, r in enumerate(rows, 1):
        print(f"[{n}/{len(rows)}] {r['participant']} {r['session'].name} ({r['split']}, {r['condition']})")
        rep = evaluate_session(r["session"], str(r["labels"]), args.tolerance, cfg)
        reports.append(rep)

    groups: Dict[str, List[int]] = defaultdict(list)
    for i, r in enumerate(rows):
        groups[f"{r['split']} - all sessions"].append(i)
        groups[f"{r['split']} - {r['condition']}"].append(i)
    if args.split == "all":
        groups = {"all splits": list(range(len(rows))), **groups}
    # drop the per-condition group when there is only one condition (it equals the "all sessions" group)
    conditions = {r["condition"] for r in rows}
    if len(conditions) == 1:
        groups = {k: v for k, v in groups.items() if not k.endswith(f" - {next(iter(conditions))}")}
    summary = {name: pooled([reports[i] for i in idx], [rows[i]["participant"] for i in idx]) for name, idx in groups.items()}

    tag = args.tag or (f"{args.split}_replay" if args.replay else args.split)
    out = Path(args.out) / tag
    out.mkdir(parents=True, exist_ok=True)
    meta = {"tag": tag, "split": args.split, "sessions": len(rows), "participants": len({r["participant"] for r in rows}),
            "replay": args.replay, "config": args.config, "eye_mode": args.eye_mode, "mouth_mode": args.mouth_mode,
            "tolerance_s": args.tolerance}

    with open(out / "per_session.csv", "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["participant", "session", "split", "condition", "minutes", "behavior", "gt_events", "event_tp", "event_fp",
                    "event_fn", "event_f1", "frame_f1", "median_delay_s", "unnecessary_alarms"])
        for r, rep in zip(rows, reports):
            for b in BEHAVIORS:
                ev, fr = rep["behaviors"][b]["event"], rep["behaviors"][b]["frame"]
                w.writerow([r["participant"], r["session"].name, r["split"], r["condition"], rep["duration_min"], b, ev["gt_events"],
                            ev["tp"], ev["fp"], ev["fn"], ev["f1"], fr["f1"], ev["median_delay_s"], rep["alarms"]["unnecessary"]])
    with open(out / "summary.csv", "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        keys = list(next(iter(summary.values()))[BEHAVIORS[0]].keys())
        w.writerow(["group", "behavior"] + keys)
        for name, g in summary.items():
            for b in BEHAVIORS:
                w.writerow([name, b] + [g[b][k] for k in keys])
            a = g["alarms"]
            w.writerow([name, "unnecessary_alarms_per_hour", a["unnecessary_per_hour"]])
    md = markdown(summary, meta)
    (out / "summary.md").write_text(md, encoding="utf-8")
    (out / "report.json").write_text(json.dumps({"meta": meta, "summary": summary,
                                                 "sessions": [{**{k: str(v) for k, v in r.items()}, "report": rep}
                                                              for r, rep in zip(rows, reports)]}, indent=2), encoding="utf-8")
    print("\n" + md)
    print(f"Saved to {out}/ (per_session.csv, summary.csv, summary.md, report.json)")
    if args.split == "test":
        print("Reminder: report the test split only with the final config; tune on the validation split.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
