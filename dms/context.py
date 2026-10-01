"""Driving-context providers: speed (NEO-6M GPS), motion (MPU6050), duration.

The camera pipeline does not depend on these sensors. When they are missing,
``DriverRiskModel`` assumes the vehicle is moving (``assumed_speed_kmh``) so
missing context can never suppress an alert.
"""

from __future__ import annotations

import json
import threading
import time
import urllib.request
from typing import Optional

from .types import ContextReading


class ContextProvider:
    def __init__(self) -> None:
        self._t0: Optional[float] = None

    def driving_seconds(self, t: float) -> float:
        if self._t0 is None:
            self._t0 = t
        return max(0.0, t - self._t0)

    def read(self, t: float) -> ContextReading:
        return ContextReading(driving_s=self.driving_seconds(t))


class StaticContext(ContextProvider):
    """Fixed speed / motion values (bench testing or recorded videos)."""

    def __init__(self, speed_kmh: Optional[float] = None, motion_g: Optional[float] = None, start_offset_s: float = 0.0):
        super().__init__()
        self.speed_kmh = speed_kmh
        self.motion_g = motion_g
        self.start_offset_s = start_offset_s  # e.g. simulate a session that started 2 h ago

    def read(self, t: float) -> ContextReading:
        return ContextReading(self.speed_kmh, self.motion_g, self.driving_seconds(t) + self.start_offset_s)


class HttpContext(ContextProvider):
    """Polls a JSON endpoint on the ESP32, e.g. ``{"speed_kmh": 32.5, "motion_g": 0.08}``.

    ``motion_g`` is the peak horizontal acceleration (in g) over the last
    second, computed on the ESP32 from the MPU6050. Readings older than
    ``stale_s`` are treated as missing.
    """

    def __init__(self, url: str, poll_s: float = 1.0, stale_s: float = 5.0):
        super().__init__()
        self.url, self.poll_s, self.stale_s = url, poll_s, stale_s
        self._latest: dict = {}
        self._stamp = 0.0
        self._stop = threading.Event()
        threading.Thread(target=self._poll, daemon=True).start()

    def _poll(self) -> None:
        while not self._stop.is_set():
            try:
                with urllib.request.urlopen(self.url, timeout=1.0) as resp:
                    self._latest = json.loads(resp.read().decode("utf-8"))
                    self._stamp = time.monotonic()
            except Exception:
                pass
            self._stop.wait(self.poll_s)

    def read(self, t: float) -> ContextReading:
        fresh = time.monotonic() - self._stamp <= self.stale_s
        speed = self._latest.get("speed_kmh") if fresh else None
        motion = self._latest.get("motion_g") if fresh else None
        return ContextReading(speed, motion, self.driving_seconds(t))

    def close(self) -> None:
        self._stop.set()
