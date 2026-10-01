"""Data passed between the five pipeline stages.

    Stage 1  AI perception          -> PerceptionResult (FaceObservation, Detection)
    Stage 2  visual features        -> FrameFeatures     (plain numbers)
    Stage 3  threshold classifier   -> FrameStates + BehaviorStatus
    Stage 4  severity grading       -> SeverityReport
    Stage 5  driver-risk fusion     -> RiskAssessment -> AlertCommand
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Dict, List, Optional, Tuple

import numpy as np

Box = Tuple[float, float, float, float]  # x1, y1, x2, y2 in pixels

SEVERITY_LEVELS = ("NONE", "MILD", "MODERATE", "SEVERE")
RISK_TIERS = ("NONE", "LOW", "MEDIUM", "HIGH")
BEHAVIORS = ("drowsiness", "yawning", "gaze_deviation", "phone_usage")


# ----------------------------------------------------------------- Stage 1
@dataclass
class FaceObservation:
    """Output of the MediaPipe Face Landmarker for one face."""

    landmarks: np.ndarray  # (478, 3): x, y in pixels, z relative depth (pixel scale)
    blendshapes: Dict[str, float]  # 52 learned facial-expression coefficients in [0, 1]
    bbox: Box


@dataclass
class Detection:
    """One YOLO bounding box."""

    label: str
    confidence: float
    box: Box
    role: Optional[str] = None  # e.g. "phone" when the class is a phone
    in_driver_roi: bool = True


@dataclass
class PerceptionResult:
    frame_size: Tuple[int, int]  # (width, height)
    face: Optional[FaceObservation]
    detections: List[Detection] = field(default_factory=list)
    eye_closed_prob: Optional[float] = None  # optional learned eye-state classifier
    timings_ms: Dict[str, float] = field(default_factory=dict)


# ----------------------------------------------------------------- Stage 2
@dataclass
class FrameFeatures:
    """Numeric visual features of one frame (no decisions yet)."""

    t: float
    face_present: bool
    ear: Optional[float] = None
    ear_left: Optional[float] = None
    ear_right: Optional[float] = None
    mar: Optional[float] = None
    yaw: Optional[float] = None  # degrees, + = face turned toward image right
    pitch: Optional[float] = None  # degrees, + = face tilted up
    roll: Optional[float] = None
    blink: Optional[float] = None  # mean eyeBlink blendshape (learned)
    jaw_open: Optional[float] = None  # jawOpen blendshape (learned)
    eye_closed_prob: Optional[float] = None  # learned eye-state classifier
    phone_conf: float = 0.0  # best YOLO phone confidence inside the driver ROI
    phone_boxes: int = 0

    def to_dict(self) -> dict:
        return asdict(self)


# ----------------------------------------------------------------- Stage 3
@dataclass
class FrameStates:
    """Per-frame binary states after thresholding the fused evidence."""

    eyes_closed: Optional[bool] = None
    eye_evidence: Optional[float] = None
    eye_sources: Dict[str, float] = field(default_factory=dict)
    mouth_open: Optional[bool] = None
    mouth_evidence: Optional[float] = None
    mouth_sources: Dict[str, float] = field(default_factory=dict)
    head_away: Optional[bool] = None
    pose_evidence: Optional[float] = None
    phone_visible: bool = False
    face_lost_s: float = 0.0


@dataclass
class BehaviorStatus:
    """Temporal interpretation of the frame states (persistence + windows)."""

    closure_s: float = 0.0  # longest eye closure within the hold window
    perclos: Optional[float] = None  # fraction of closed-eye time in the PERCLOS window
    mouth_open_s: float = 0.0
    yawns_in_window: int = 0
    gaze_away_s: float = 0.0
    offroad_ratio: float = 0.0
    phone_s: float = 0.0
    # Behaviour "detected" flags (used for F1 evaluation, SO3)
    drowsy: bool = False
    yawning: bool = False
    gaze_deviation: bool = False
    phone_usage: bool = False
    events: List[str] = field(default_factory=list)  # new events on this frame
    no_face: bool = False  # face missing for longer than face_loss_max_s


# ----------------------------------------------------------------- Stage 4
@dataclass
class Severity:
    score: float  # 0..1, fed to the fuzzy system
    level: str  # NONE / MILD / MODERATE / SEVERE (for explanation)
    reason: str = ""


@dataclass
class SeverityReport:
    drowsiness: Severity
    yawning: Severity
    gaze_deviation: Severity
    phone_usage: Severity

    def as_dict(self) -> Dict[str, Severity]:
        return {b: getattr(self, b) for b in BEHAVIORS}

    def max_score(self) -> float:
        return max(s.score for s in self.as_dict().values())


# ----------------------------------------------------------------- Stage 5
@dataclass
class ContextReading:
    speed_kmh: Optional[float] = None
    motion_g: Optional[float] = None
    driving_s: float = 0.0


@dataclass
class RiskAssessment:
    fatigue: float  # F in [0, 1]
    distraction: float  # D in [0, 1]
    context: float  # C in [0, 1]
    risk: float  # R in [0, 100]
    tier: str  # NONE / LOW / MEDIUM / HIGH
    inputs: Dict[str, float] = field(default_factory=dict)
    top_rules: List[str] = field(default_factory=list)


@dataclass
class AlertCommand:
    t: float
    tier: str
    action: str  # soft_beep | repeating_buzzer | alarm
    push: bool
    reason: str
    new_episode: bool  # True when this starts a new alert episode (used for FP/hour)


@dataclass
class FrameResult:
    t: float
    frame_index: int
    perception: Optional[PerceptionResult]
    features: FrameFeatures
    states: FrameStates
    behavior: BehaviorStatus
    severity: SeverityReport
    risk: RiskAssessment
    alerts: List[AlertCommand]
    active_tier: str
    calibration_status: str
    context: Optional[ContextReading] = None
    timings_ms: Dict[str, float] = field(default_factory=dict)
