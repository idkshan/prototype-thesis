"""End-to-end behaviour of Stages 3-5 on synthetic drivers (no camera needed)."""

import pytest

from dms.alerts import AlertManager
from dms.config import AlertConfig
from dms.simulation import ScenarioBuilder, max_tier, run_scenario

CAL = 8.0  # every scenario starts with 8 s of alert driving (calibration takes 5 s)


def _run(builder, **kw):
    frames = builder.build()
    out = run_scenario(frames, **kw)
    alerts = [a for o in out for a in o.alerts]
    return frames, out, alerts


def test_calibrates_on_synthetic_driver():
    _, out, _ = _run(ScenarioBuilder().alert(10))
    assert out[-1].calibration_status == "CALIBRATED"


@pytest.mark.parametrize(
    "name,build",
    [
        ("alert driver, 2 minutes", lambda b: b.alert(120)),
        ("normal blinking", lambda b: [b.alert(3.0).blink() for _ in range(30)]),
        ("talking (speech is not yawning)", lambda b: b.talk(30)),
        ("prolonged blink 0.6 s", lambda b: b.eyes_closed(0.6).alert(8)),
        ("mirror glance 1.5 s", lambda b: b.look_away(1.5).alert(8)),
        ("phone visible 2.5 s (< 3 s rule)", lambda b: b.phone(2.5).alert(8)),
        ("road bump 0.2 g", lambda b: b.alert(20)),
    ],
)
def test_no_false_alarms(name, build):
    b = ScenarioBuilder().alert(CAL)
    build(b)
    frames, out, alerts = _run(b, motion_at=(10, 11, 0.2))
    assert alerts == [], name
    assert max_tier(out, CAL, frames) == "NONE"


def test_single_yawn_is_low_risk():
    frames, out, alerts = _run(ScenarioBuilder().alert(CAL).yawn(3).alert(10))
    assert [a.tier for a in alerts] == ["LOW"]
    assert alerts[0].action == "soft_beep"


def test_frequent_yawning_is_medium_risk_with_bounded_buzzing():
    b = ScenarioBuilder().alert(CAL)
    for _ in range(3):
        b.yawn(3).alert(20)
    b.alert(120)
    frames, out, alerts = _run(b)
    assert max_tier(out, CAL, frames) == "MEDIUM"
    medium = [a for a in alerts if a.tier == "MEDIUM"]
    assert 1 <= len(medium) <= AlertConfig().medium_max_repeats


def test_microsleep_escalates_low_then_medium():
    frames, out, alerts = _run(ScenarioBuilder().alert(CAL).eyes_closed(2.0).alert(8))
    tiers = [a.tier for a in alerts]
    assert tiers[0] == "LOW" and "MEDIUM" in tiers and "HIGH" not in tiers


@pytest.mark.parametrize("builder", [lambda b: b.look_away(4.0), lambda b: b.look_down(4.0), lambda b: b.face_lost(4.0)])
def test_sustained_gaze_deviation_is_high_risk_with_push(builder):
    b = ScenarioBuilder().alert(CAL)
    builder(b)
    b.alert(8)
    frames, out, alerts = _run(b)
    assert max_tier(out, CAL, frames) == "HIGH"
    assert any(a.push for a in alerts)
    first_high = next(a for a in alerts if a.tier == "HIGH")
    assert first_high.t - CAL == pytest.approx(3.0, abs=0.15)


def test_phone_use_confirmed_after_three_seconds():
    frames, out, alerts = _run(ScenarioBuilder().alert(CAL).phone(4.0).alert(8))
    assert alerts[0].tier == "HIGH" and alerts[0].push
    assert alerts[0].t - CAL == pytest.approx(3.0, abs=0.15)
    assert any(o.behavior.phone_usage for o in out)


def test_phone_while_stopped_is_downgraded():
    frames, out, _ = _run(ScenarioBuilder().alert(CAL).phone(4.0).alert(8), speed_kmh=0.0)
    assert max_tier(out, CAL, frames) == "MEDIUM"


def test_erratic_motion_is_high_risk():
    frames, out, _ = _run(ScenarioBuilder().alert(20), motion_at=(10, 11, 0.5))
    assert max_tier(out, CAL, frames) == "HIGH"


def test_alarm_releases_after_hysteresis():
    frames, out, _ = _run(ScenarioBuilder().alert(CAL).look_away(4.0).alert(15))
    assert out[-1].active_tier == "NONE"


def test_alert_manager_push_rate_limit_and_release():
    m = AlertManager(AlertConfig())
    cmds = []
    for i in range(1200):  # 120 s continuously HIGH at 10 FPS
        cmds += m.update(i * 0.1, "HIGH", [], "")
    assert sum(c.push for c in cmds) == 2  # t=0 and t=60
    assert m.episodes["HIGH"] == 1
    for i in range(1200, 1225):
        m.update(i * 0.1, "NONE", [], "")
    assert m.active_tier == "HIGH"  # still inside release window at 2.4 s
    for i in range(1225, 1240):
        m.update(i * 0.1, "NONE", [], "")
    assert m.active_tier == "NONE"
