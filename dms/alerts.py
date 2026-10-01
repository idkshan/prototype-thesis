"""Three-tier adaptive alarm (SO5) with hysteresis to limit false alarms.

    LOW    -> soft buzzer, once when the tier is entered and again on each new
              behaviour event (e.g. every yawn), at most every ``min_interval_s``
    MEDIUM -> repeating buzzer every ``medium_repeat_s``, in bursts of at most
              ``medium_max_repeats`` buzzes; each new behaviour event re-arms
              the burst (avoids 5 minutes of buzzing after frequent yawning)
    HIGH   -> repeated alarm every ``high_repeat_s`` while active + push
              notification (at most one push per ``push_interval_s``)

Escalation is immediate. De-escalation happens only after the assessed tier
has stayed lower for ``release_s`` seconds, so a flickering risk score does
not produce on/off/on alarms.
"""

from __future__ import annotations

import json
import threading
import urllib.parse
import urllib.request
from typing import List, Optional

from .config import AlertConfig
from .types import RISK_TIERS, AlertCommand

ACTIONS = {"LOW": "soft_beep", "MEDIUM": "repeating_buzzer", "HIGH": "alarm"}


def _rank(tier: str) -> int:
    return RISK_TIERS.index(tier)


class AlertManager:
    def __init__(self, cfg: AlertConfig):
        self.cfg = cfg
        self.active_tier = "NONE"
        self._below_since: Optional[float] = None
        self._last_fire = -1e9
        self._last_push = -1e9
        self._burst = 0  # alarms fired since the last escalation / behaviour event
        self.episodes = {t: 0 for t in RISK_TIERS[1:]}  # alert episodes started, by entry tier

    def _fire(self, t: float, reason: str, new_episode: bool) -> AlertCommand:
        push = False
        if self.active_tier == "HIGH" and t - self._last_push >= self.cfg.push_interval_s:
            push, self._last_push = True, t
        self._last_fire = t
        self._burst += 1
        return AlertCommand(t=t, tier=self.active_tier, action=ACTIONS[self.active_tier], push=push, reason=reason, new_episode=new_episode)

    def update(self, t: float, tier: str, events: List[str], reason: str) -> List[AlertCommand]:
        cmds: List[AlertCommand] = []
        if _rank(tier) > _rank(self.active_tier):
            new_episode = self.active_tier == "NONE"
            self.active_tier, self._below_since = tier, None
            if new_episode:
                self.episodes[tier] += 1
            self._burst = 0
            cmds.append(self._fire(t, reason, new_episode))
            return cmds

        if _rank(tier) < _rank(self.active_tier):
            if self._below_since is None:
                self._below_since = t
            elif t - self._below_since >= self.cfg.release_s:
                self.active_tier, self._below_since = tier, None
        else:
            self._below_since = None

        if events:
            self._burst = 0  # a new behaviour event re-arms the repeating alarm
        since = t - self._last_fire
        if self.active_tier == "LOW":
            if events and since >= self.cfg.min_interval_s:
                cmds.append(self._fire(t, reason, False))
        elif self.active_tier in ("MEDIUM", "HIGH"):
            high = self.active_tier == "HIGH"
            period = self.cfg.high_repeat_s if high else self.cfg.medium_repeat_s
            limit = self.cfg.high_max_repeats if high else self.cfg.medium_max_repeats
            if since >= period and (limit <= 0 or self._burst < limit):
                cmds.append(self._fire(t, reason, False))
        return cmds


class ConsoleAlertSink:
    def __call__(self, cmd: AlertCommand) -> None:
        bell = "\a" if cmd.tier in ("MEDIUM", "HIGH") else ""
        push = " + PUSH" if cmd.push else ""
        print(f"{bell}[{cmd.t:8.2f}s] ALERT {cmd.tier:<6} {cmd.action}{push} :: {cmd.reason}", flush=True)


class HttpAlertSink:
    """Forward alert commands to the ESP32 (buzzer) with a non-blocking GET.

    ``url`` is the base, e.g. ``http://192.168.4.1/alert``; the request becomes
    ``<url>?tier=HIGH&action=alarm&push=1``. The ESP32 firmware endpoint is
    outside this repository; adjust the query format to match it.
    """

    def __init__(self, url: str, timeout_s: float = 0.5):
        self.url = url
        self.timeout_s = timeout_s

    def __call__(self, cmd: AlertCommand) -> None:
        query = urllib.parse.urlencode({"tier": cmd.tier, "action": cmd.action, "push": int(cmd.push)})
        target = f"{self.url}?{query}"

        def send():
            try:
                urllib.request.urlopen(target, timeout=self.timeout_s).read()
            except Exception as exc:  # network errors must never stop monitoring
                print(f"[alert-sink] could not reach {self.url}: {exc}")

        threading.Thread(target=send, daemon=True).start()


def alert_to_json(cmd: AlertCommand) -> str:
    return json.dumps(cmd.__dict__)
