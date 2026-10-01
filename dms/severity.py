"""Stage 4: severity grading per behaviour.

Each behaviour receives a continuous severity score in [0, 1] (the crisp input
of the fuzzy system) and a linguistic level for explanation. Scores come from
piecewise-linear anchor tables in the config, e.g. for gaze deviation
2 s away -> 0.5 (Moderate) and 3 s away -> 0.85 (High).

The level is the fuzzy term (Table 1.7) with the highest membership:
    NONE      score < 0.10
    MILD      0.10 <= score < 1/3   ("Low" dominates)
    MODERATE  1/3  <= score < 0.65  ("Moderate" dominates)
    SEVERE    score >= 0.65         ("High" dominates)
"""

from __future__ import annotations

from typing import Sequence

import numpy as np

from .config import SeverityConfig
from .types import BehaviorStatus, Severity, SeverityReport

MILD_AT, MODERATE_AT, SEVERE_AT = 0.10, 1.0 / 3.0, 0.65
DROWSY_DETECTED_AT = 0.5  # drowsiness is "detected" (SO3) from Moderate severity upward


def piecewise(anchors: Sequence[Sequence[float]], x: float) -> float:
    xs = [a[0] for a in anchors]
    ys = [a[1] for a in anchors]
    return float(np.interp(x, xs, ys))


def level_for(score: float) -> str:
    if score < MILD_AT:
        return "NONE"
    if score < MODERATE_AT:
        return "MILD"
    if score < SEVERE_AT:
        return "MODERATE"
    return "SEVERE"


def _sev(score: float, reason: str) -> Severity:
    return Severity(score=round(score, 4), level=level_for(score), reason=reason)


class SeverityGrader:
    def __init__(self, cfg: SeverityConfig):
        self.cfg = cfg

    def grade(self, b: BehaviorStatus) -> SeverityReport:
        c = self.cfg
        closure = piecewise(c.closure_s, b.closure_s)
        perclos = piecewise(c.perclos, b.perclos) if b.perclos is not None else 0.0
        if closure >= perclos:
            drowsy = _sev(closure, f"eye closure {b.closure_s:.1f}s")
        else:
            drowsy = _sev(perclos, f"PERCLOS {100 * (b.perclos or 0):.0f}%")

        yawn = _sev(piecewise(c.yawn_count, b.yawns_in_window), f"{b.yawns_in_window} yawn(s) in window")

        away = piecewise(c.gaze_s, b.gaze_away_s)
        offroad = piecewise(c.offroad_ratio, b.offroad_ratio)
        if away >= offroad:
            gaze = _sev(away, f"looking away {b.gaze_away_s:.1f}s")
        else:
            gaze = _sev(offroad, f"eyes off road {100 * b.offroad_ratio:.0f}% of window")

        phone = _sev(piecewise(c.phone_s, b.phone_s), f"phone visible {b.phone_s:.1f}s")
        return SeverityReport(drowsiness=drowsy, yawning=yawn, gaze_deviation=gaze, phone_usage=phone)
