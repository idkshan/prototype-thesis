package edu.dlsu.esca_3_2526_c3.dms

import java.util.UUID

/*
 * Alert state machine (SO5), Kotlin port of dms/alerts/state_machine.py.
 *
 *   detected behaviour  what the driver is doing NOW                  BehaviorStatus.active
 *   assessed risk       NONE / LOW / MEDIUM / HIGH (remembers recent history)
 *   active alert        the buzzer sounds (LOW one beep, MEDIUM repeating, HIGH alarm + push)
 *   cleared alert       stopped although the risk may still be elevated
 *
 * An alert sounds only while a behaviour is happening and stops 2 s after the driver
 * is normal again (explicit "stop"), even if the risk level is still elevated.
 * One push per HIGH alert, at most one per minute. Keepalives every second while
 * MEDIUM / HIGH sound. No frames for 3 s -> stop + "unavailable".
 */

const val MONITORING = "MONITORING"
const val ALERTING = "ALERTING"
const val CLEARED = "CLEARED"
const val UNAVAILABLE = "UNAVAILABLE"

val ALERT_ACTIONS = mapOf("NONE" to "off", "LOW" to "soft_beep", "MEDIUM" to "repeating_buzzer", "HIGH" to "alarm")

private fun rank(tier: String) = RISK_TIERS.indexOf(tier)
private fun maxTier(a: String, b: String) = if (rank(b) > rank(a)) b else a

class AlertStateMachine(private val cfg: AlertConfig, idPrefix: String? = null) {
    var state = MONITORING
        private set
    var tier = "NONE" // tier of the sounding alert
        private set
    var alertId = ""
        private set
    val episodes = linkedMapOf("LOW" to 0, "MEDIUM" to 0, "HIGH" to 0)
    val transitions = mutableListOf<Triple<Double, String, String>>() // (t, from, to)
    private val prefix = idPrefix ?: UUID.randomUUID().toString().replace("-", "").take(8)
    private var seq = 0
    private var pendingTier = "NONE"
    private var pendingSince = Double.NEGATIVE_INFINITY
    private var eligible = "NONE"
    private var startedT = Double.NEGATIVE_INFINITY
    private var lastBeepT = Double.NEGATIVE_INFINITY
    private var lastKeepaliveT = Double.NEGATIVE_INFINITY
    private var lastPushT = Double.NEGATIVE_INFINITY
    private var lastActiveT = Double.NEGATIVE_INFINITY
    private var lastEventT = Double.NEGATIVE_INFINITY
    private var lastLowBeepT = Double.NEGATIVE_INFINITY
    private var clearedT = Double.NEGATIVE_INFINITY
    private var beepsSinceTrigger = 0
    private var belowSince: Double? = null
    private var lastAlertedTier = "NONE"
    private var closureSince: Double? = null
    private var pushedThisAlert = false

    /** Tier of the alert that is sounding now ("NONE" when silent). */
    val activeTier: String get() = if (state == ALERTING) tier else "NONE"

    private fun cmd(t: Double, event: String, tier: String, reason: String, behaviors: List<String>, push: Boolean = false): AlertCommand {
        seq += 1
        val action = if (event in setOf("stop", "unavailable", "available", "push_suppressed")) "off" else ALERT_ACTIONS.getValue(tier)
        return AlertCommand(Num.pyRound(t, 3), tier, action, push, reason, event == "start", event,
            "%s-%06d".format(prefix, seq), alertId, behaviors.toList())
    }

    private fun transition(t: Double, newState: String) {
        if (newState != state) {
            transitions.add(Triple(t, state, newState))
            state = newState
        }
    }

    private fun push(t: Double, reason: String, behaviors: List<String>): List<AlertCommand> {
        if (pushedThisAlert) return emptyList()
        pushedThisAlert = true
        if (t - lastPushT < cfg.pushIntervalS) {
            return listOf(cmd(t, "push_suppressed", "HIGH", "push cooldown (%.0f s): %s".format(cfg.pushIntervalS, reason), behaviors))
        }
        lastPushT = t
        return listOf(cmd(t, "push", "HIGH", reason, behaviors, push = true))
    }

    private fun start(t: Double, tier: String, reason: String, behaviors: List<String>): List<AlertCommand> {
        alertId = "%s-A%05d".format(prefix, seq + 1)
        this.tier = tier
        episodes[tier] = episodes.getValue(tier) + 1
        startedT = t; lastBeepT = t; lastKeepaliveT = t
        beepsSinceTrigger = 1
        belowSince = null
        pushedThisAlert = false
        if (tier == "LOW") lastLowBeepT = t
        lastAlertedTier = maxTier(lastAlertedTier, tier)
        transition(t, ALERTING)
        val cmds = mutableListOf(cmd(t, "start", tier, reason, behaviors))
        if (tier == "HIGH") cmds += push(t, reason, behaviors)
        return cmds
    }

    private fun stop(t: Double, reason: String, behaviors: List<String>, riskTier: String): List<AlertCommand> {
        val c = cmd(t, "stop", tier, reason, behaviors)
        tier = "NONE"
        clearedT = t
        transition(t, if (rank(riskTier) > 0) CLEARED else MONITORING)
        return listOf(c)
    }

    fun update(t: Double, riskTier: String, active: List<String>, events: List<String>, reason: String): List<AlertCommand> {
        val cmds = mutableListOf<AlertCommand>()
        if (state == UNAVAILABLE) {
            transition(t, MONITORING)
            cmds += cmd(t, "available", "NONE", "camera frames are back", active)
        }
        // risk tier debounce: a higher tier must hold activateS; a lower one counts at once
        if (riskTier != pendingTier) {
            pendingTier = riskTier; pendingSince = t
        }
        if (rank(riskTier) < rank(eligible) || t - pendingSince >= cfg.activateS) eligible = riskTier
        val tierNow = eligible

        if (active.isNotEmpty()) lastActiveT = t
        if (events.isNotEmpty()) lastEventT = t
        val recentEvent = t - lastEventT <= cfg.activateS + 1e-6
        closureSince = if ("eye_closure" in active) (closureSince ?: t) else null
        if (rank(tierNow) == 0) lastAlertedTier = "NONE"

        if (state == MONITORING || state == CLEARED) {
            if (rank(tierNow) == 0) {
                transition(t, MONITORING)
                return cmds
            }
            val startReason: String? = when {
                active.isNotEmpty() || recentEvent -> reason.ifEmpty { "behaviour at risk $tierNow" }
                rank(tierNow) > rank(lastAlertedTier) -> "risk rose to $tierNow: $reason"
                cfg.reminderS > 0 && rank(tierNow) >= rank("MEDIUM") && t - clearedT >= cfg.reminderS -> "reminder (risk still $tierNow): $reason"
                else -> null
            }
            if (startReason != null) cmds += start(t, tierNow, startReason, active)
            else transition(t, CLEARED)
            return cmds
        }

        // ---- ALERTING
        if (rank(tierNow) > rank(tier)) {
            tier = tierNow
            beepsSinceTrigger = 1
            belowSince = null
            lastBeepT = t; lastKeepaliveT = t
            lastAlertedTier = maxTier(lastAlertedTier, tierNow)
            cmds += cmd(t, "escalate", tierNow, reason, active)
            if (tierNow == "HIGH") cmds += push(t, reason, active)
        } else if (rank(tierNow) < rank(tier)) {
            val bs = belowSince
            if (bs == null) {
                belowSince = t
            } else if (t - bs >= cfg.releaseS) {
                if (rank(tierNow) == 0) return (cmds + stop(t, "risk back to NONE", active, tierNow)).toMutableList()
                tier = tierNow
                belowSince = null
                beepsSinceTrigger = 1
                cmds += cmd(t, "deescalate", tierNow, reason, active)
            }
        } else {
            belowSince = null
        }

        // optional safety escalation: eyes closed for a long time during an alert
        val cs = closureSince
        if (cfg.escalateClosureS > 0 && tier != "HIGH" && cs != null && t - cs >= cfg.escalateClosureS) {
            tier = "HIGH"
            lastBeepT = t; lastKeepaliveT = t
            val why = "eyes closed >= %s s".format(cfg.escalateClosureS.toString().removeSuffix(".0"))
            cmds += cmd(t, "escalate", "HIGH", why, active)
            cmds += push(t, why, active)
        }

        if (events.isNotEmpty()) beepsSinceTrigger = 0 // a new behaviour event re-arms the burst

        val burst = when (tier) { "LOW" -> 0; "MEDIUM" -> cfg.mediumMaxRepeats; else -> cfg.highMaxRepeats }
        val burstDone = burst <= 0 || beepsSinceTrigger >= burst
        if (active.isEmpty() && t - lastActiveT >= cfg.clearS && t - startedT >= cfg.minAlertS && burstDone && !recentEvent) {
            return (cmds + stop(t, "driver back to normal for %s s".format(cfg.clearS.toString().removeSuffix(".0")), active, tierNow)).toMutableList()
        }

        // sound pattern
        val sinceBeep = t - lastBeepT
        if (tier == "LOW") {
            if (events.isNotEmpty() && t - lastLowBeepT >= cfg.minIntervalS) {
                lastLowBeepT = t; lastBeepT = t
                cmds += cmd(t, "beep", "LOW", reason, active)
            }
        } else {
            val period = if (tier == "HIGH") cfg.highRepeatS else cfg.mediumRepeatS
            val allowed = active.isNotEmpty() || burst <= 0 || beepsSinceTrigger < burst
            if (sinceBeep >= period && allowed) {
                lastBeepT = t
                beepsSinceTrigger += 1
                cmds += cmd(t, "beep", tier, reason, active)
            }
            if (t - lastKeepaliveT >= cfg.keepaliveS) {
                lastKeepaliveT = t
                cmds += cmd(t, "keepalive", tier, reason, active)
            }
        }
        return cmds
    }

    /** No frames for cameraTimeoutS: stop sounding, report monitoring unavailable. */
    fun cameraLost(t: Double, reason: String = "no camera frames"): List<AlertCommand> {
        if (state == UNAVAILABLE) return emptyList()
        val cmds = mutableListOf<AlertCommand>()
        if (state == ALERTING) cmds += stop(t, "monitoring unavailable: $reason", emptyList(), "NONE")
        tier = "NONE"
        transition(t, UNAVAILABLE)
        cmds += cmd(t, "unavailable", "NONE", reason, emptyList())
        pendingTier = "NONE"; eligible = "NONE"; lastAlertedTier = "NONE"
        return cmds
    }
}

/** Buzzer level for the sensor board's /tier?level=N (06_devkit_wifi.ino): 0 off, 1 LOW, 2 MEDIUM, 3 HIGH. */
object TierProtocol {
    val SENT_EVENTS = setOf("start", "escalate", "deescalate", "beep", "keepalive", "stop", "unavailable")

    fun level(c: AlertCommand): Int? {
        if (c.event !in SENT_EVENTS) return null
        if (c.event == "stop" || c.event == "unavailable" || c.action == "off") return 0
        return rank(c.tier).coerceAtLeast(0)
    }
}
