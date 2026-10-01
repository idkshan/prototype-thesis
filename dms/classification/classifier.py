"""Stage 3: threshold-based classification of the AI-derived visual features.

This is the rule-based layer. It does not look at pixels; it *interprets*
what the neural networks of Stage 1 measured:

1. Per-frame states - fused evidence >= decision level (0.5) switches a state
   ON: eyes closed, mouth wide open, head turned away, phone visible.
2. Temporal persistence - a state only becomes a behaviour once it lasts long
   enough (blinks, speech and mirror glances are rejected), and sliding
   windows summarise longer patterns (PERCLOS, yawn count, off-road time).
"""

from __future__ import annotations

import math
from typing import Optional

from ..config import Config
from ..types import BehaviorStatus, FrameFeatures, FrameStates
from .calibration import Thresholds
from .evidence import fuse, ramp_evidence
from .temporal import EpisodeTracker, EventWindow, TimeWeightedRatio


class BehaviorClassifier:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        tc = cfg.temporal
        self.closure = EpisodeTracker(tc.closure_gap_s)
        self.mouth = EpisodeTracker(tc.yawn_gap_s)
        self.gaze = EpisodeTracker(tc.gaze_gap_s)
        self.phone = EpisodeTracker(tc.phone_gap_s)
        self.perclos = TimeWeightedRatio(tc.perclos_window_s)
        self.offroad = TimeWeightedRatio(tc.offroad_window_s)
        self.yawns = EventWindow(tc.yawn_window_s)
        self._last_face_t: Optional[float] = None
        self.last_event_t = -math.inf

    # ------------------------------------------------------ per-frame states
    def classify_frame(self, f: FrameFeatures, thr: Thresholds) -> FrameStates:
        ev, tc, b = self.cfg.evidence, self.cfg.temporal, thr.baseline
        level = thr.decision_level
        st = FrameStates(phone_visible=f.phone_conf > 0.0)

        if not f.face_present:
            if self._last_face_t is not None:
                st.face_lost_s = f.t - self._last_face_t
                # A face that vanishes mid-drive usually means the head turned
                # far away from the camera (beyond the landmark model's range).
                if tc.face_loss_as_gaze_away and st.face_lost_s <= tc.face_loss_max_s:
                    st.head_away = True
            return st

        self._last_face_t = f.t

        # Head pose -> gaze deviation evidence (deviation from the driver's own forward pose)
        if f.yaw is not None and f.pitch is not None:
            dp = f.pitch - b.pitch
            st.pose_evidence = max(
                ramp_evidence(abs(f.yaw - b.yaw), 0.0, thr.yaw_limit),
                ramp_evidence(-dp, 0.0, thr.pitch_down_limit),
                ramp_evidence(dp, 0.0, thr.pitch_up_limit),
            )
            st.head_away = st.pose_evidence >= level

        # Mouth: geometric MAR + learned jawOpen blendshape
        mouth = {"mar": ramp_evidence(f.mar, b.mar, thr.mar_open), "blendshape": ramp_evidence(f.jaw_open, b.jaw, thr.jaw_open)}
        st.mouth_sources = {k: v for k, v in mouth.items() if v is not None}
        st.mouth_evidence = fuse(mouth, {"mar": ev.mouth_mar, "blendshape": ev.mouth_blendshape})
        if st.mouth_evidence is not None:
            st.mouth_open = st.mouth_evidence >= level

        # Eyes: geometric EAR + learned eyeBlink blendshape (+ optional eye-state CNN)
        eyes = {
            "ear": ramp_evidence(f.ear, b.ear, thr.ear_closed),
            "blendshape": ramp_evidence(f.blink, b.blink, thr.blink_closed),
            "cnn": f.eye_closed_prob,
        }
        st.eye_sources = {k: v for k, v in eyes.items() if v is not None}
        st.eye_evidence = fuse(eyes, {"ear": ev.eye_ear, "blendshape": ev.eye_blendshape, "cnn": ev.eye_cnn})
        # Eye state is not judged while the head is turned/lowered (EAR drops when
        # looking down) or during a wide mouth opening (eyes squint when yawning).
        if st.eye_evidence is not None and not st.head_away and not st.mouth_open:
            st.eyes_closed = st.eye_evidence >= level
        return st

    # ---------------------------------------------------- temporal behaviour
    def update(self, f: FrameFeatures, s: FrameStates) -> BehaviorStatus:
        t, tc = f.t, self.cfg.temporal
        events = []

        self.closure.update(t, s.eyes_closed)
        if s.eyes_closed is None:
            self.perclos.skip(t)
        else:
            self.perclos.add(t, s.eyes_closed)
        if self.closure.fire_once("closure", tc.closure_event_s):
            events.append("eye_closure")

        self.mouth.update(t, s.mouth_open)
        if self.mouth.fire_once("yawn", tc.yawn_min_s):
            self.yawns.add(t)
            events.append("yawn")

        self.gaze.update(t, s.head_away)
        if s.head_away is None:
            self.offroad.skip(t)
        else:
            self.offroad.add(t, s.head_away)
        if self.gaze.fire_once("gaze", tc.gaze_min_s):
            events.append("gaze_deviation")

        self.phone.update(t, s.phone_visible)
        if self.phone.fire_once("phone", tc.phone_confirm_s):
            events.append("phone_usage")

        if events:
            self.last_event_t = t

        perclos = self.perclos.ratio if self.perclos.coverage_s >= tc.perclos_min_coverage_s else None
        no_face = not f.face_present and (self._last_face_t is None or s.face_lost_s > tc.face_loss_max_s)
        return BehaviorStatus(
            closure_s=self.closure.recent_max(t, tc.closure_hold_s),
            perclos=perclos,
            mouth_open_s=self.mouth.duration(),
            yawns_in_window=self.yawns.count(t),
            gaze_away_s=self.gaze.recent_max(t, tc.gaze_hold_s),
            # off-road time over a fixed window length (not over observed time),
            # so a single glance early in a session is not over-weighted
            offroad_ratio=self.offroad.ratio * min(1.0, self.offroad.coverage_s / tc.offroad_window_s),
            phone_s=self.phone.recent_max(t, tc.phone_hold_s),
            yawning=self.mouth.active and self.mouth.duration() >= tc.yawn_min_s,
            gaze_deviation=self.gaze.active and self.gaze.duration() >= tc.gaze_min_s,
            phone_usage=self.phone.active and self.phone.duration() >= tc.phone_confirm_s,
            events=events,
            no_face=no_face,
        )

    def any_episode_active(self) -> bool:
        return self.closure.active or self.mouth.active or self.gaze.active or self.phone.active
