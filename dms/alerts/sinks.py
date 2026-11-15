"""Where alert commands go: console, the ESP32 buzzer (HTTP), later the Android app.

Device protocol (HttpAlertSink): one GET per command
    <url>?event=start&tier=HIGH&mode=alarm&push=1&id=ab12cd34-000017&alert=ab12cd34-A00016
  event  start | escalate | deescalate | beep | keepalive | stop | unavailable | available | push
  mode   soft_beep | repeating_buzzer | alarm | off   (what the buzzer should do now)
Two firmware styles are supported:
  * "beeps" (default, like version 1/2): the firmware sounds once per request;
    every start / escalate / beep request makes one sound, stop/off silences.
  * "state": the firmware runs the pattern itself after start/escalate/deescalate
    and needs the keepalive requests; it must switch itself off when it gets
    "stop" OR when no request arrives for ~3 s (fail-safe if the phone dies).
  * "tier": the sensor board's /tier endpoint (06_devkit_wifi.ino):
        <url>?level=N   N = 0 off, 1 LOW, 2 MEDIUM, 3 HIGH
    The board plays the tier's pattern until the next request; stop and
    unavailable send level=0. Keepalives repeat the level once per second.
The ESP32 firmware is outside this repository; adjust the query format to it.
"""

from __future__ import annotations

import json
import queue
import threading
import time
import urllib.parse
import urllib.request

from ..types import AlertCommand

DEVICE_EVENTS = {
    "beeps": {"start", "escalate", "deescalate", "beep", "stop", "unavailable"},
    "state": {"start", "escalate", "deescalate", "keepalive", "stop", "unavailable"},
    "tier": {"start", "escalate", "deescalate", "beep", "keepalive", "stop", "unavailable"},
}
TIER_LEVELS = {"NONE": 0, "LOW": 1, "MEDIUM": 2, "HIGH": 3}


class ConsoleAlertSink:
    """Prints the alert lifecycle (start / escalate / step down / stop / push) - not every keepalive."""

    def __init__(self, show_beeps: bool = False):
        self.show_beeps = show_beeps

    def __call__(self, cmd: AlertCommand) -> None:
        if cmd.event == "keepalive" or (cmd.event == "beep" and not self.show_beeps):
            return
        bell = "\a" if cmd.event in ("start", "escalate") and cmd.tier in ("MEDIUM", "HIGH") else ""
        push = " + PUSH" if cmd.push else ""
        print(f"{bell}[{cmd.t:8.2f}s] {cmd.event.upper():<11s} {cmd.tier:<6} {cmd.action}{push} :: {cmd.reason}", flush=True)


class HttpAlertSink:
    """Forward alert commands to the ESP32 buzzer without blocking the monitor.

    Requests go out one at a time, in order, from one background thread: the
    ESP32 web server answers one client at a time, and a "stop" that overtook
    or lost to an older "start" would leave the buzzer on. A failed stop
    (level 0 / unavailable) is retried STOP_RETRIES times.
    """

    STOP_RETRIES = 3

    def __init__(self, url: str, timeout_s: float = 0.5, style: str = "beeps"):
        if style not in DEVICE_EVENTS:
            raise ValueError(f"style must be one of {sorted(DEVICE_EVENTS)}")
        self.url, self.timeout_s, self.style = url, timeout_s, style
        self._queue: "queue.Queue[tuple[str, int]]" = queue.Queue()
        threading.Thread(target=self._worker, daemon=True).start()

    def __call__(self, cmd: AlertCommand) -> None:
        if cmd.event not in DEVICE_EVENTS[self.style]:
            return
        tries = 1 + (self.STOP_RETRIES if cmd.event in ("stop", "unavailable") else 0)
        self._queue.put((f"{self.url}?{urllib.parse.urlencode(self.query(cmd))}", tries))

    def _worker(self) -> None:
        while True:
            target, tries = self._queue.get()
            for attempt in range(tries):
                try:
                    urllib.request.urlopen(target, timeout=self.timeout_s).read()
                    break
                except Exception as exc:  # network errors must never stop monitoring
                    if attempt == tries - 1:
                        print(f"[alert-sink] could not reach {self.url}: {exc}")
            self._queue.task_done()

    def close(self) -> None:
        """Switch the buzzer off when monitoring ends (quit during an alarm) and send what is queued."""
        self(AlertCommand(t=0.0, tier="NONE", action="off", push=False, reason="monitor closed", new_episode=False,
                          event="stop"))
        self.flush()

    def flush(self, timeout_s: float = 5.0) -> None:
        """Wait until the queued requests are sent (end of a session, tests)."""
        end = time.monotonic() + timeout_s
        while self._queue.unfinished_tasks and time.monotonic() < end:
            time.sleep(0.02)

    def query(self, cmd: AlertCommand) -> dict:
        if self.style == "tier":
            off = cmd.event in ("stop", "unavailable") or cmd.action == "off"
            return {"level": 0 if off else TIER_LEVELS.get(cmd.tier, 0)}
        return {"event": cmd.event, "tier": cmd.tier, "mode": cmd.action, "action": cmd.action,
                "push": int(cmd.push), "id": cmd.id, "alert": cmd.alert_id}


def alert_to_json(cmd: AlertCommand) -> str:
    return json.dumps(cmd.to_dict())
