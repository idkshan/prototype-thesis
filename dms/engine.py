"""Stages 3-5 chained together: FrameFeatures -> alert commands.

The engine is independent of the camera and the neural networks. It can be
driven by live perception (``DriverMonitor``), by features replayed from a
session log (``scripts/evaluate_behaviors.py --replay``), or by synthetic
scenarios (``dms/simulation.py``) - which is how the thresholds and fuzzy
rules are unit-tested.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import List

from .alerts import AlertManager
from .classification.calibration import CALIBRATING, DriverCalibrator
from .classification.classifier import BehaviorClassifier
from .config import Config
from .risk.driver_risk import DriverRiskModel
from .severity import DROWSY_DETECTED_AT, SeverityGrader
from .types import AlertCommand, BehaviorStatus, ContextReading, FrameFeatures, FrameStates, RiskAssessment, SeverityReport


@dataclass
class EngineOutput:
    states: FrameStates
    behavior: BehaviorStatus
    severity: SeverityReport
    risk: RiskAssessment
    alerts: List[AlertCommand]
    active_tier: str
    calibration_status: str
    context: ContextReading


class DecisionEngine:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.calibrator = DriverCalibrator(cfg.calibration, cfg.thresholds)
        self.classifier = BehaviorClassifier(cfg)
        self.grader = SeverityGrader(cfg.severity)
        self.risk_model = DriverRiskModel(cfg)
        self.alerts = AlertManager(cfg.alerts)

    def recalibrate(self) -> None:
        self.calibrator.restart()

    def step(self, feats: FrameFeatures, ctx: ContextReading) -> EngineOutput:
        thr = self.calibrator.thresholds

        # Stage 3: threshold-based classification
        states = self.classifier.classify_frame(feats, thr)
        behavior = self.classifier.update(feats, states)

        # Stage 4: severity grading
        severity = self.grader.grade(behavior)
        behavior.drowsy = severity.drowsiness.score >= DROWSY_DETECTED_AT

        # Calibration / online adaptation of the baseline
        if self.calibrator.status == CALIBRATING:
            self.calibrator.collect(feats)
        elif self._confidently_alert(feats, states, severity):
            self.calibrator.adapt(feats)
        else:
            self.calibrator.pause_adaptation()

        # Stage 5: driver-risk fusion -> alarm tier
        risk = self.risk_model.assess(severity, ctx)
        reason = self._reason(severity, risk)
        alerts = self.alerts.update(feats.t, risk.tier, behavior.events, reason)
        return EngineOutput(states, behavior, severity, risk, alerts, self.alerts.active_tier, self.calibrator.status, ctx)

    def _confidently_alert(self, f: FrameFeatures, s: FrameStates, sev: SeverityReport) -> bool:
        return (
            f.face_present
            and not s.phone_visible
            and (s.eye_evidence or 0.0) < 0.3
            and (s.mouth_evidence or 0.0) < 0.3
            and (s.pose_evidence or 0.0) < 0.25
            and sev.max_score() < 0.1
            and not self.classifier.any_episode_active()
            and f.t - self.classifier.last_event_t >= self.cfg.calibration.adapt_quiet_s
        )

    @staticmethod
    def _reason(sev: SeverityReport, risk: RiskAssessment) -> str:
        parts = [f"{name}={s.level}({s.reason})" for name, s in sev.as_dict().items() if s.level != "NONE"]
        ctx = f"F={risk.fatigue:.2f} D={risk.distraction:.2f} C={risk.context:.2f} R={risk.risk:.0f}"
        return (", ".join(parts) + " | " if parts else "") + ctx
