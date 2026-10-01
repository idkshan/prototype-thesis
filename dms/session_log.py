"""Session logging for evaluation (SO3-SO5 metrics).

Writes into ``<log_dir>/<session>/``:
  frames.jsonl   one line per frame: Stage-2 features, Stage-3 states and
                 behaviour flags, Stage-4 severities, Stage-5 risk, timings
  alerts.jsonl   every alert command (tier, action, push, reason)
  summary.json   frame rate, per-stage latency statistics, alert episodes and
                 alerts per hour, calibration baseline

``frames.jsonl`` holds the full Stage-2 feature vector, so Stages 3-5 can be
re-run offline with different thresholds (``evaluate_behaviors.py --replay``)
without re-running the neural networks.
"""

from __future__ import annotations

import json
import time
from dataclasses import asdict
from pathlib import Path
from typing import Dict, List, Optional

import numpy as np

from .types import FrameResult


def _round(v, d=4):
    if isinstance(v, float):
        return round(v, d)
    if isinstance(v, dict):
        return {k: _round(x, d) for k, x in v.items()}
    return v


class SessionLogger:
    def __init__(self, log_dir: str | Path, name: Optional[str] = None, meta: Optional[dict] = None):
        name = name or time.strftime("session_%Y%m%d_%H%M%S")
        self.dir = Path(log_dir) / name
        self.dir.mkdir(parents=True, exist_ok=True)
        self._frames = open(self.dir / "frames.jsonl", "w", encoding="utf-8")
        self._alerts = open(self.dir / "alerts.jsonl", "w", encoding="utf-8")
        self.meta = meta or {}
        self._timings: Dict[str, List[float]] = {}
        self._n = 0
        self._first_t: Optional[float] = None
        self._last_t = 0.0
        self._wall0 = time.monotonic()
        self._alert_count = 0
        self._episodes = {"LOW": 0, "MEDIUM": 0, "HIGH": 0}
        self._flag_frames = {"drowsy": 0, "yawning": 0, "gaze_deviation": 0, "phone_usage": 0}
        self._face_frames = 0
        self._last_result: Optional[FrameResult] = None

    def log(self, r: FrameResult) -> None:
        self._n += 1
        self._first_t = r.t if self._first_t is None else self._first_t
        self._last_t = r.t
        self._face_frames += int(r.features.face_present)
        b = r.behavior
        for k in self._flag_frames:
            self._flag_frames[k] += int(getattr(b, k))
        for k, v in r.timings_ms.items():
            self._timings.setdefault(k, []).append(v)
        rec = {
            "t": round(r.t, 4),
            "i": r.frame_index,
            "features": _round(r.features.to_dict()),
            "states": {
                "eyes_closed": r.states.eyes_closed,
                "eye_evidence": _round(r.states.eye_evidence),
                "eye_sources": _round(r.states.eye_sources),
                "mouth_open": r.states.mouth_open,
                "mouth_evidence": _round(r.states.mouth_evidence),
                "mouth_sources": _round(r.states.mouth_sources),
                "head_away": r.states.head_away,
                "pose_evidence": _round(r.states.pose_evidence),
                "phone_visible": r.states.phone_visible,
            },
            "behavior": _round({k: v for k, v in asdict(b).items()}),
            "severity": {k: {"score": s.score, "level": s.level} for k, s in r.severity.as_dict().items()},
            "risk": {"F": r.risk.fatigue, "D": r.risk.distraction, "C": r.risk.context, "R": r.risk.risk, "tier": r.risk.tier},
            "context": _round(asdict(r.context)) if r.context is not None else None,
            "active_tier": r.active_tier,
            "calibration": r.calibration_status,
            "timings_ms": _round(r.timings_ms, 2),
        }
        self._frames.write(json.dumps(rec) + "\n")
        for a in r.alerts:
            self._alert_count += 1
            if a.new_episode:
                self._episodes[a.tier] += 1
            self._alerts.write(json.dumps(asdict(a)) + "\n")
        self._last_result = r

    def close(self, calibration: Optional[dict] = None) -> dict:
        self._frames.close()
        self._alerts.close()
        duration = (self._last_t - self._first_t) if self._first_t is not None else 0.0
        wall = time.monotonic() - self._wall0
        stats = {}
        for k, vals in self._timings.items():
            a = np.array(vals)
            stats[k] = {"mean": round(float(a.mean()), 2), "median": round(float(np.median(a)), 2), "p95": round(float(np.percentile(a, 95)), 2)}
        total = self._timings.get("total_ms")
        summary = {
            **self.meta,
            "frames": self._n,
            "video_duration_s": round(duration, 2),
            "wall_time_s": round(wall, 2),
            "input_fps": round(self._n / duration, 2) if duration > 0 else None,
            "processing_fps": round(1000.0 / float(np.mean(total)), 2) if total else None,
            "face_detected_ratio": round(self._face_frames / self._n, 4) if self._n else None,
            "stage_latency_ms": stats,
            "alert_commands": self._alert_count,
            "alert_episodes": self._episodes,
            "alert_episodes_per_hour": round(sum(self._episodes.values()) / (duration / 3600.0), 2) if duration > 0 else None,
            "behavior_flag_frames": self._flag_frames,
            "calibration": calibration,
        }
        with open(self.dir / "summary.json", "w", encoding="utf-8") as fh:
            json.dump(summary, fh, indent=2)
        return summary
