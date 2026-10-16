"""Participant-study evaluation: label loading, level scoring, pooled metrics."""

import csv
import json
import sys

import numpy as np
import pytest

import evaluate_study
from dms.config import Config
from dms.simulation import ScenarioBuilder
from evaluate_behaviors import evaluate_session, level_index, load_labels, score_levels


def write_session(folder, seed=0):
    """A synthetic session folder holding only Stage-2 features (evaluated with replay)."""
    b = ScenarioBuilder(fps=10, seed=seed)
    b.alert(40).yawn(4).alert(20).eyes_closed(4).alert(20).phone(6).alert(20)
    folder.mkdir(parents=True)
    with open(folder / "frames.jsonl", "w", encoding="utf-8") as fh:
        for f in b.build():
            fh.write(json.dumps({"t": f.t, "features": f.to_dict()}) + "\n")
    (folder / "alerts.jsonl").write_text("")
    return folder


def write_labels(path, bom=False):
    text = "behavior,start_s,end_s\nyawning,40,44\nmoderately_drowsy,64,68\nphone,88,94\n"
    path.write_bytes((b"\xef\xbb\xbf" if bom else b"") + text.encode())
    return path


def test_labels_tolerate_excel_bom_and_levels(tmp_path):
    segs, levels = load_labels(str(write_labels(tmp_path / "l.csv", bom=True)), with_levels=True)
    assert segs["yawning"] == [(40.0, 44.0)]
    assert segs["drowsiness"] == [(64.0, 68.0)]
    assert segs["phone_usage"] == [(88.0, 94.0)]
    assert levels == [(64.0, 68.0, 2)]


def test_level_index_and_level_scoring():
    assert level_index("Alert") == 0 and level_index("Level 3 (severely drowsy)") == 3
    t = np.arange(0, 20, 0.1)
    pred = np.where((t > 5) & (t < 8), 2, 0)
    res = score_levels(t, pred, [(5, 8, 2), (12, 14, 1)], tol=0.5)
    assert res["exact"] == 1 and res["within_one"] == 2 and res["confusion"][2][2] == 1 and res["confusion"][1][0] == 1


def test_session_replay_detects_labelled_behaviours(tmp_path):
    session = write_session(tmp_path / "S")
    rep = evaluate_session(session, str(write_labels(tmp_path / "l.csv")), cfg=Config())
    assert rep["behaviors"]["yawning"]["event"]["tp"] == 1
    assert rep["behaviors"]["phone_usage"]["event"]["tp"] == 1
    assert rep["drowsiness_levels"]["segments"] == 1


def test_study_pools_participants_and_blocks_leakage(tmp_path, monkeypatch):
    rows = []
    for i, split in enumerate(["validation", "validation", "test"]):
        write_session(tmp_path / "sessions" / f"P{i}_day", seed=i)
        write_labels(tmp_path / f"P{i}_day.csv")
        rows.append([f"P{i}", f"P{i}_day", f"P{i}_day.csv", split, "day"])
    manifest = tmp_path / "manifest.csv"
    with open(manifest, "w", newline="") as fh:
        csv.writer(fh).writerows([["participant", "session", "labels", "split", "condition"]] + rows)
    out = tmp_path / "eval"
    monkeypatch.setattr(sys, "argv", ["x", "--manifest", str(manifest), "--sessions", str(tmp_path / "sessions"),
                                      "--split", "validation", "--replay", "--tag", "t", "--out", str(out)])
    assert evaluate_study.main() == 0
    report = json.loads((out / "t" / "report.json").read_text())
    assert report["meta"]["sessions"] == 2 and report["meta"]["participants"] == 2
    yawn = report["summary"]["validation - all sessions"]["yawning"]
    assert yawn["gt_events"] == 2 and yawn["detected"] == 2
    assert (out / "t" / "summary.md").exists() and (out / "t" / "per_session.csv").exists()

    # the same participant in both splits is refused
    with open(manifest, "a", newline="") as fh:
        csv.writer(fh).writerow(["P0", "P2_day", "P2_day.csv", "test", "night"])
    with pytest.raises(SystemExit, match="leakage"):
        evaluate_study.load_manifest(manifest, tmp_path / "sessions")
