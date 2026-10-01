"""Synthetic feature timelines for testing Stages 3-5 without a camera.

Values mimic what the Stage-1/2 models produce for a real driver (open-eye
EAR ~0.29, closed mouth MAR ~0.02, ...), with small seeded noise. The
scenarios reproduce the behaviour-to-alarm mapping of Section 1.7.1 and are
used by the unit tests and by ``scripts/simulate_scenarios.py``.
"""

from __future__ import annotations

from dataclasses import replace
from typing import List, Optional

import numpy as np

from .config import Config
from .context import StaticContext
from .engine import DecisionEngine, EngineOutput
from .types import FrameFeatures

NEUTRAL = dict(ear=0.29, mar=0.02, yaw=8.0, pitch=-4.0, roll=0.0, blink=0.10, jaw_open=0.02)


class ScenarioBuilder:
    def __init__(self, fps: float = 10.0, seed: int = 0, noise: bool = True):
        self.dt = 1.0 / fps
        self.t = 0.0
        self.frames: List[FrameFeatures] = []
        self.rng = np.random.default_rng(seed)
        self.noise = noise

    def _jitter(self, value: float, sd: float) -> float:
        return float(value + (self.rng.normal(0, sd) if self.noise else 0.0))

    def _emit(self, seconds: float, face: bool = True, phone_conf: float = 0.0, **overrides) -> "ScenarioBuilder":
        n = max(1, int(round(seconds / self.dt)))
        for _ in range(n):
            if face:
                v = {**NEUTRAL, **overrides}
                ear = max(0.0, self._jitter(v["ear"], 0.008))
                f = FrameFeatures(
                    t=round(self.t, 4),
                    face_present=True,
                    ear=ear,
                    ear_left=ear,
                    ear_right=ear,
                    mar=max(0.0, self._jitter(v["mar"], 0.01)),
                    yaw=self._jitter(v["yaw"], 1.0),
                    pitch=self._jitter(v["pitch"], 1.0),
                    roll=v["roll"],
                    blink=min(1.0, max(0.0, self._jitter(v["blink"], 0.02))),
                    jaw_open=min(1.0, max(0.0, self._jitter(v["jaw_open"], 0.01))),
                    phone_conf=phone_conf,
                    phone_boxes=int(phone_conf > 0),
                )
            else:
                f = FrameFeatures(t=round(self.t, 4), face_present=False, phone_conf=phone_conf, phone_boxes=int(phone_conf > 0))
            self.frames.append(f)
            self.t += self.dt
        return self

    # Behaviours ---------------------------------------------------------
    def alert(self, seconds: float):
        return self._emit(seconds)

    def blink(self):
        self._emit(0.2, ear=0.08, blink=0.85)
        return self._emit(0.1)

    def eyes_closed(self, seconds: float):
        return self._emit(seconds, ear=0.07, blink=0.9)

    def yawn(self, seconds: float = 3.0):
        return self._emit(seconds, mar=0.85, jaw_open=0.75, ear=0.22, blink=0.45)

    def talk(self, seconds: float):
        # speech: the mouth opens and closes quickly and never stays wide open
        for _ in range(int(seconds / 0.4)):
            self._emit(0.2, mar=0.3, jaw_open=0.25)
            self._emit(0.2, mar=0.08, jaw_open=0.06)
        return self

    def look_away(self, seconds: float, yaw: float = 50.0):
        return self._emit(seconds, yaw=NEUTRAL["yaw"] + yaw)

    def look_down(self, seconds: float, pitch: float = -30.0):
        return self._emit(seconds, pitch=NEUTRAL["pitch"] + pitch, ear=0.2)

    def phone(self, seconds: float, conf: float = 0.7):
        return self._emit(seconds, phone_conf=conf)

    def face_lost(self, seconds: float):
        return self._emit(seconds, face=False)

    def build(self) -> List[FrameFeatures]:
        return list(self.frames)


def run_scenario(
    frames: List[FrameFeatures],
    cfg: Optional[Config] = None,
    speed_kmh: Optional[float] = 40.0,
    motion_g: Optional[float] = None,
    motion_at: Optional[tuple] = None,
) -> List[EngineOutput]:
    """Run frames through a fresh DecisionEngine.

    ``motion_at=(start_s, end_s, g)`` injects an erratic-motion burst.
    """
    engine = DecisionEngine(cfg or Config())
    ctx = StaticContext(speed_kmh=speed_kmh, motion_g=motion_g)
    out = []
    for f in frames:
        reading = ctx.read(f.t)
        if motion_at and motion_at[0] <= f.t < motion_at[1]:
            reading = replace(reading, motion_g=motion_at[2])
        out.append(engine.step(f, reading))
    return out


def max_tier(outputs: List[EngineOutput], start_s: float = 0.0, frames: Optional[List[FrameFeatures]] = None) -> str:
    order = ("NONE", "LOW", "MEDIUM", "HIGH")
    tiers = [o.active_tier for i, o in enumerate(outputs) if frames is None or frames[i].t >= start_s]
    return max(tiers, key=order.index) if tiers else "NONE"
