"""Stage 5: overall driver-risk calculation (Table 1.6).

    Fatigue     F = FIS(eye_closure, yawning, driving duration)   in [0, 1]
    Distraction D = FIS(gaze deviation, phone use)                in [0, 1]
    Context     C = FIS(speed, erratic motion)                    in [0, 1]
    Overall     R = FIS(F, D, C)                                  in [0, 100]

R is mapped to an alarm tier with fixed bounds (default 25 / 52.5 / 77.5,
the midpoints between the centroids of neighbouring output sets).
"""

from __future__ import annotations

from typing import Optional

from ..config import Config
from ..severity import piecewise
from ..types import ContextReading, RiskAssessment, SeverityReport
from .fuzzy import MamdaniSystem
from .rules import context_rules, distraction_rules, fatigue_rules, overall_rules, risk_variable, unit_variable


def tier_for(risk: float, bounds) -> str:
    low, medium, high = bounds
    if risk >= high:
        return "HIGH"
    if risk >= medium:
        return "MEDIUM"
    if risk >= low:
        return "LOW"
    return "NONE"


class DriverRiskModel:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        res = cfg.risk.resolution
        u = unit_variable
        self.fatigue = MamdaniSystem("fatigue", [u("eye_closure"), u("yawning"), u("duration")], u("fatigue"), fatigue_rules(), res)
        self.distraction = MamdaniSystem("distraction", [u("gaze"), u("phone")], u("distraction"), distraction_rules(), res)
        self.context = MamdaniSystem("context", [u("speed"), u("motion")], u("context"), context_rules(), res)
        self.overall = MamdaniSystem(
            "overall", [u("fatigue"), u("distraction"), u("context")], risk_variable("risk"), overall_rules(), res
        )

    def normalise_context(self, ctx: ContextReading) -> dict:
        c = self.cfg.context
        speed_kmh: Optional[float] = ctx.speed_kmh if ctx.speed_kmh is not None else c.assumed_speed_kmh
        return {
            "speed": piecewise(c.speed_kmh, speed_kmh),
            "motion": piecewise(c.motion_g, ctx.motion_g or 0.0),
            "duration": piecewise(c.driving_hours, ctx.driving_s / 3600.0),
        }

    def assess(self, sev: SeverityReport, ctx: ContextReading) -> RiskAssessment:
        cx = self.normalise_context(ctx)
        inputs = {
            "eye_closure": sev.drowsiness.score,
            "yawning": sev.yawning.score,
            "gaze": sev.gaze_deviation.score,
            "phone": sev.phone_usage.score,
            **cx,
        }
        f = self.fatigue.infer({"eye_closure": inputs["eye_closure"], "yawning": inputs["yawning"], "duration": cx["duration"]})
        d = self.distraction.infer({"gaze": inputs["gaze"], "phone": inputs["phone"]})
        c = self.context.infer({"speed": cx["speed"], "motion": cx["motion"]})
        r = self.overall.infer({"fatigue": f.value, "distraction": d.value, "context": c.value})
        top = sorted((fr for fr in r.firing if fr[1] > 0), key=lambda fr: -fr[1])[:3]
        return RiskAssessment(
            fatigue=round(f.value, 4),
            distraction=round(d.value, 4),
            context=round(c.value, 4),
            risk=round(r.value, 2),
            tier=tier_for(r.value, self.cfg.risk.tier_bounds),
            inputs={k: round(v, 4) for k, v in inputs.items()},
            top_rules=[f"{rule.text('R')} [{alpha:.2f}]" for rule, alpha in top],
        )
