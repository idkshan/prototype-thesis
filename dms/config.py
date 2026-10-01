"""Configuration for the driver-monitoring pipeline.

Every tunable number used by the five pipeline stages lives here so that the
thesis can cite one table of parameters and experiments can change them
through ``configs/*.yaml`` without touching code.  The dataclass defaults are
the reference values; ``configs/default.yaml`` mirrors them (a unit test keeps
the two in sync).

Piecewise-linear "anchor" lists are ``[[x0, y0], [x1, y1], ...]`` and are
evaluated with linear interpolation (values outside the range are clamped to
the first/last y).
"""

from __future__ import annotations

import dataclasses
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, List, Optional

import yaml

Anchors = List[List[float]]


# --------------------------------------------------------------------------
# Stage 1 - AI perception models
# --------------------------------------------------------------------------
@dataclass
class FaceModelConfig:
    """MediaPipe Face Landmarker (face detector + landmark/blendshape networks)."""

    model_path: str = "models/face_landmarker.task"
    min_detection_confidence: float = 0.5
    min_presence_confidence: float = 0.5
    min_tracking_confidence: float = 0.5


@dataclass
class ObjectDetectorConfig:
    """YOLO object detector used for mobile-phone detection.

    Any Ultralytics detection model can be used (YOLOv8, YOLO11, YOLO26, or a
    fine-tuned checkpoint). The default is the paper's baseline, YOLOv8-nano;
    the default does not imply it is the best choice (see benchmark_yolo.py).
    """

    model_path: str = "models/yolov8n.pt"
    imgsz: int = 640
    conf: float = 0.35
    iou: float = 0.5
    device: str = "cpu"
    every_n_frames: int = 1  # run YOLO every N frames and reuse results in between
    phone_class_names: List[str] = field(
        default_factory=lambda: ["cell phone", "phone", "mobile phone", "cellphone", "mobile_phone"]
    )
    # Spatial filter: a phone only counts if its centre lies near the driver,
    # i.e. within +/- roi_width_factor face-widths of the face centre and no
    # higher than roi_above_factor face-heights above the top of the face.
    roi_width_factor: float = 2.5
    roi_above_factor: float = 0.5


@dataclass
class EyeStateModelConfig:
    """Optional learned eye-state classifier (e.g. YOLO-cls trained on MRL Eye).

    Disabled when ``model_path`` is null. When enabled, both eye regions are
    cropped using the face landmarks and classified as open/closed; the
    probability of "closed" becomes a third eye-closure evidence source.
    """

    model_path: Optional[str] = None
    closed_class_names: List[str] = field(default_factory=lambda: ["closed", "close", "closed_eye", "closed_eyes"])
    crop_scale: float = 1.8
    grayscale: bool = True  # MRL Eye images are infrared/greyscale
    imgsz: Optional[int] = None  # None -> use the size the model was trained with


# --------------------------------------------------------------------------
# Stage 3 - threshold-based classification
# --------------------------------------------------------------------------
@dataclass
class EvidenceWeights:
    """Weights used to fuse several evidence sources into one score in [0, 1].

    Sources that are unavailable on a frame (e.g. no eye-state model) are
    skipped and the remaining weights are renormalised. Setting a weight to 0
    removes that source, which is how ablation modes (EAR-only, AI-only,
    fused) are produced.
    """

    eye_ear: float = 0.5
    eye_blendshape: float = 0.5
    eye_cnn: float = 0.5
    mouth_mar: float = 0.5
    mouth_blendshape: float = 0.5


@dataclass
class ThresholdConfig:
    """Per-driver thresholds, expressed relative to the calibrated baseline."""

    ear_closed_ratio: float = 0.70  # eyes "closed" when EAR < 0.70 x baseline EAR
    blink_closed_delta: float = 0.35  # eyeBlink blendshape > baseline + 0.35
    mar_open_delta: float = 0.40  # mouth "wide open" when MAR > baseline + 0.40
    jaw_open_delta: float = 0.35  # jawOpen blendshape > baseline + 0.35
    yaw_deg: float = 30.0  # |yaw - baseline yaw| beyond this = head turned away
    pitch_down_deg: float = 20.0  # head lowered this far below baseline = looking down
    pitch_up_deg: float = 25.0
    decision_level: float = 0.5  # fused evidence >= this -> state is ON
    uncalibrated_pose_scale: float = 1.5  # widen pose limits while uncalibrated


@dataclass
class CalibrationDefaults:
    """Population defaults used until a valid per-driver baseline exists."""

    ear: float = 0.28
    mar: float = 0.05
    yaw: float = 0.0
    pitch: float = 0.0
    blink: float = 0.15
    jaw: float = 0.03


@dataclass
class CalibrationConfig:
    enabled: bool = True
    duration_s: float = 5.0  # length of the calibration window
    min_samples: int = 25  # minimum valid frames in the window
    max_pose_std_deg: float = 5.0  # head must be steady (robust std of yaw/pitch)
    min_ear: float = 0.18  # eyes must be open
    max_mar: float = 0.25  # mouth must be closed / neutral
    max_blink: float = 0.5
    # Online adaptation (Section 1.7.9): slow exponential update over frames in
    # which the driver is confidently alert and facing forward.
    adapt: bool = True
    adapt_tau_s: float = 120.0  # time constant of the running baseline
    adapt_quiet_s: float = 10.0  # no behaviour episode for this long before adapting
    max_ear_drift: float = 0.20  # running EAR baseline stays within +/-20 % of calibration
    max_mar_drift: float = 0.10  # absolute
    max_pose_drift_deg: float = 10.0
    defaults: CalibrationDefaults = field(default_factory=CalibrationDefaults)


@dataclass
class TemporalConfig:
    """Persistence rules. All durations are in seconds, never in frames."""

    closure_gap_s: float = 0.15  # tolerated flicker inside one eye-closure episode
    closure_event_s: float = 0.5  # closures shorter than this are ordinary blinks
    yawn_min_s: float = 1.5  # mouth must stay wide open this long (rejects speech)
    yawn_gap_s: float = 0.3
    gaze_min_s: float = 2.0  # glances shorter than this are normal mirror checks
    gaze_gap_s: float = 0.3
    phone_confirm_s: float = 3.0  # Section 1.7.15: phone visible >= 3 s
    phone_gap_s: float = 0.5
    perclos_window_s: float = 60.0
    perclos_min_coverage_s: float = 30.0  # PERCLOS ignored until half a window of data exists
    offroad_window_s: float = 30.0
    yawn_window_s: float = 300.0
    closure_hold_s: float = 3.0  # keep reporting a long closure briefly after the eyes reopen
    gaze_hold_s: float = 1.0
    phone_hold_s: float = 1.0
    face_loss_as_gaze_away: bool = True  # face vanishing (head turned far away) counts as gaze away
    face_loss_max_s: float = 10.0  # after this the system reports "no face" instead


# --------------------------------------------------------------------------
# Stage 4 - severity grading
# --------------------------------------------------------------------------
@dataclass
class SeverityConfig:
    """Anchors mapping measured quantities to a severity score in [0, 1].

    The fuzzy sets of Table 1.7 cross at 1/3 (Low -> Moderate) and at 0.65
    (Moderate -> High). Those crossovers are where a behaviour's severity
    level - and, for a single behaviour, the alarm tier - changes, so every
    table places 0.34 and 0.66 at the intended limits, for example:
      gaze_s     2.0 s away   -> 0.34 (MODERATE)  3.0 s -> 0.66 (SEVERE)
      phone_s    < 3 s        -> <= 0.30          3.0 s -> 0.70 (SEVERE)
      perclos    8 %          -> 0.34             15 %  -> 0.66
    """

    perclos: Anchors = field(default_factory=lambda: [[0.0, 0.0], [0.04, 0.0], [0.08, 0.34], [0.15, 0.66], [0.25, 1.0]])
    closure_s: Anchors = field(default_factory=lambda: [[0.0, 0.0], [0.4, 0.0], [0.8, 0.34], [1.5, 0.66], [2.0, 0.9], [3.0, 1.0]])
    yawn_count: Anchors = field(default_factory=lambda: [[0.0, 0.0], [1.0, 0.4], [2.0, 0.5], [3.0, 0.7], [4.0, 0.9]])
    gaze_s: Anchors = field(default_factory=lambda: [[0.0, 0.0], [1.0, 0.0], [2.0, 0.34], [3.0, 0.66], [4.0, 0.9], [5.0, 1.0]])
    offroad_ratio: Anchors = field(default_factory=lambda: [[0.0, 0.0], [0.1, 0.0], [0.25, 0.34], [0.4, 0.66], [0.6, 1.0]])
    phone_s: Anchors = field(default_factory=lambda: [[0.0, 0.0], [1.0, 0.0], [2.99, 0.3], [3.0, 0.7], [5.0, 1.0]])


# --------------------------------------------------------------------------
# Stage 5 - driver-risk fusion and alerts
# --------------------------------------------------------------------------
@dataclass
class ContextConfig:
    # stopped (< ~10 km/h) -> Low, normal driving 20-60 km/h -> Moderate, >= 80 km/h -> High
    speed_kmh: Anchors = field(
        default_factory=lambda: [[0.0, 0.0], [5.0, 0.1], [10.0, 0.34], [20.0, 0.5], [60.0, 0.5], [80.0, 0.66], [100.0, 0.9], [120.0, 1.0]]
    )
    # peak horizontal acceleration: >= 0.25 g Moderate, >= 0.45 g (harsh brake / swerve) High
    motion_g: Anchors = field(default_factory=lambda: [[0.0, 0.0], [0.1, 0.0], [0.25, 0.34], [0.45, 0.66], [0.6, 1.0]])
    # continuous driving: >= 1 h Moderate, >= 2 h High
    driving_hours: Anchors = field(default_factory=lambda: [[0.0, 0.0], [1.0, 0.34], [2.0, 0.66], [3.0, 1.0]])
    # When GPS speed is unavailable the vehicle is assumed to be moving, so a
    # missing sensor can never suppress an alert.
    assumed_speed_kmh: float = 40.0


@dataclass
class RiskConfig:
    tier_bounds: List[float] = field(default_factory=lambda: [25.0, 52.5, 77.5])  # R -> LOW / MEDIUM / HIGH
    resolution: int = 201  # samples on each output universe for centroid defuzzification


@dataclass
class AlertConfig:
    release_s: float = 3.0  # tier must stay lower this long before the alarm de-escalates
    min_interval_s: float = 5.0  # minimum gap between re-triggered LOW alarms
    medium_repeat_s: float = 2.0  # repeating buzzer period
    medium_max_repeats: int = 5  # a MEDIUM burst stops after this many buzzes until a new event (0 = no limit)
    high_repeat_s: float = 1.0  # repeated alarm period
    high_max_repeats: int = 0  # HIGH keeps sounding while the risk stays HIGH
    push_interval_s: float = 60.0  # at most one push notification per minute


@dataclass
class Config:
    face: FaceModelConfig = field(default_factory=FaceModelConfig)
    detector: ObjectDetectorConfig = field(default_factory=ObjectDetectorConfig)
    eye_model: EyeStateModelConfig = field(default_factory=EyeStateModelConfig)
    evidence: EvidenceWeights = field(default_factory=EvidenceWeights)
    thresholds: ThresholdConfig = field(default_factory=ThresholdConfig)
    calibration: CalibrationConfig = field(default_factory=CalibrationConfig)
    temporal: TemporalConfig = field(default_factory=TemporalConfig)
    severity: SeverityConfig = field(default_factory=SeverityConfig)
    context: ContextConfig = field(default_factory=ContextConfig)
    risk: RiskConfig = field(default_factory=RiskConfig)
    alerts: AlertConfig = field(default_factory=AlertConfig)

    def to_dict(self) -> dict:
        return dataclasses.asdict(self)


EYE_MODES = {
    "fused": {"eye_ear": 0.5, "eye_blendshape": 0.5, "eye_cnn": 0.5},
    "ear": {"eye_ear": 1.0, "eye_blendshape": 0.0, "eye_cnn": 0.0},
    "blendshape": {"eye_ear": 0.0, "eye_blendshape": 1.0, "eye_cnn": 0.0},
    "cnn": {"eye_ear": 0.0, "eye_blendshape": 0.0, "eye_cnn": 1.0},
}
MOUTH_MODES = {
    "fused": {"mouth_mar": 0.5, "mouth_blendshape": 0.5},
    "mar": {"mouth_mar": 1.0, "mouth_blendshape": 0.0},
    "blendshape": {"mouth_mar": 0.0, "mouth_blendshape": 1.0},
}


def apply_evidence_modes(cfg: "Config", eye_mode: Optional[str] = None, mouth_mode: Optional[str] = None) -> "Config":
    """Ablation presets: geometric-only, learned-only, or fused evidence."""
    if eye_mode:
        _merge(cfg.evidence, EYE_MODES[eye_mode])
    if mouth_mode:
        _merge(cfg.evidence, MOUTH_MODES[mouth_mode])
    return cfg


def _merge(obj: Any, overrides: dict, path: str = "") -> None:
    """Recursively apply ``overrides`` onto dataclass ``obj`` (unknown keys are errors)."""
    known = {f.name: f for f in dataclasses.fields(obj)}
    for key, value in overrides.items():
        if key not in known:
            raise KeyError(f"Unknown config key '{path}{key}'. Valid keys: {sorted(known)}")
        current = getattr(obj, key)
        if dataclasses.is_dataclass(current):
            if not isinstance(value, dict):
                raise TypeError(f"Config key '{path}{key}' must be a mapping")
            _merge(current, value, f"{path}{key}.")
        else:
            setattr(obj, key, value)


def load_config(path: Optional[str | Path] = None, overrides: Optional[dict] = None) -> Config:
    """Build a :class:`Config` from defaults, an optional YAML file, and overrides."""
    cfg = Config()
    if path is not None:
        with open(path, "r", encoding="utf-8") as fh:
            data = yaml.safe_load(fh) or {}
        _merge(cfg, data)
    if overrides:
        _merge(cfg, overrides)
    return cfg
