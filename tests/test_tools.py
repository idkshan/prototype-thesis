"""Metric code used for the thesis numbers (YOLO benchmark and behaviour evaluation)."""

import numpy as np
import pytest

from benchmark_yolo import average_precision, iou_matrix, match, prf
from evaluate_behaviors import episodes, score


def test_iou():
    a = np.array([[0, 0, 10, 10]], float)
    b = np.array([[0, 0, 10, 10], [5, 0, 15, 10], [20, 20, 30, 30]], float)
    assert iou_matrix(a, b)[0] == pytest.approx([1.0, 50 / 150, 0.0])


def test_greedy_matching_prefers_high_scores_and_counts_duplicates_as_fp():
    gt = np.array([[0, 0, 10, 10]], float)
    pred = np.array([[0, 0, 10, 10], [1, 1, 10, 10]], float)
    tp = match(pred, np.array([0.6, 0.9]), gt)
    assert tp.tolist() == [False, True]  # the higher-scored duplicate takes the match


def test_average_precision_perfect_and_half():
    assert average_precision(np.array([0.9, 0.8]), np.array([True, True]), 2) == pytest.approx(1.0)
    # one TP ranked first, one GT never found -> AP = 0.5
    assert average_precision(np.array([0.9, 0.1]), np.array([True, False]), 2) == pytest.approx(0.5)


def test_prf():
    assert prf(8, 2, 2) == pytest.approx((0.8, 0.8, 0.8))
    assert prf(0, 0, 0) == (0.0, 0.0, 0.0)


def test_behaviour_scoring_frame_and_event_level():
    t = np.arange(0, 20, 0.1)
    pred = (t >= 12.0) & (t <= 15.0)  # detected 2 s after onset (persistence rule)
    gt = [(10.0, 15.0)]
    r = score(t, pred, gt, tol=1.0)
    assert r["event"]["f1"] == pytest.approx(1.0)
    assert r["event"]["median_delay_s"] == pytest.approx(2.0)
    assert r["frame"]["precision"] == pytest.approx(1.0)
    assert r["frame"]["recall"] == pytest.approx(31 / 51, abs=0.02)


def test_false_positive_event():
    t = np.arange(0, 30, 0.1)
    pred = ((t >= 2) & (t <= 4)) | ((t >= 20) & (t <= 22))
    r = score(t, pred, [(1.0, 5.0)], tol=0.5)
    assert r["event"]["pred_events"] == 2
    assert r["event"]["precision"] == pytest.approx(0.5)
    assert r["event"]["recall"] == pytest.approx(1.0)


def test_episodes_merge_small_gaps():
    t = np.arange(0, 5, 0.1)
    on = np.ones_like(t, dtype=bool)
    on[20:23] = False  # 0.3 s gap -> merged
    assert len(episodes(t, on, merge_gap=0.5)) == 1
