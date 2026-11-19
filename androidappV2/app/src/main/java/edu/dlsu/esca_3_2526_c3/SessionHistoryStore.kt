package edu.dlsu.esca_3_2526_c3

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local history for the app's simulated monitoring sessions. No Firebase schema changes. */
object SessionHistoryStore {
    private const val PREFS = "driver_session_history_v1"
    private const val KEY = "sessions"
    private const val MIN_DURATION_MS = 10_000L

    data class RiskPoint(val timestampMs: Long, val level: String)
    data class Alert(val timestampMs: Long, val behavior: String, val severity: String)
    data class Session(
        val id: String, val startedMs: Long, val endedMs: Long, val active: Boolean,
        val timeline: List<RiskPoint>, val alerts: List<Alert>
    ) {
        fun durationMs(now: Long = System.currentTimeMillis()): Long =
            ((if (active && DriverMonitoringService.active && DriverMonitoringService.sessionId == id)
                now else endedMs) - startedMs).coerceAtLeast(0L)
    }

    private fun read(context: Context): JSONArray = try {
        JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
    } catch (_: Exception) { JSONArray() }

    private fun save(context: Context, sessions: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, sessions.toString()).apply()
    }

    /** Discard sessions shorter than 10 seconds, except for the currently running one. */
    private fun prune(sessions: JSONArray): JSONArray {
        val kept = JSONArray()
        for (i in 0 until sessions.length()) {
            val session = sessions.optJSONObject(i) ?: continue
            val running = session.optBoolean("active") && DriverMonitoringService.active &&
                    session.optString("id") == DriverMonitoringService.sessionId
            if (running || session.optLong("endedMs") - session.optLong("startedMs") >= MIN_DURATION_MS)
                kept.put(session)
        }
        return kept
    }

    @Synchronized fun start(context: Context, id: String, startedMs: Long) {
        val sessions = prune(read(context))
        sessions.put(JSONObject().put("id", id).put("startedMs", startedMs)
            .put("endedMs", startedMs).put("active", true)
            .put("timeline", JSONArray().put(point(startedMs, "LOW")))
            .put("alerts", JSONArray()))
        save(context, sessions)
    }

    private fun point(at: Long, level: String) =
        JSONObject().put("timestampMs", at).put("level", level)

    private fun overall(statuses: Map<String, String>): String = when {
        statuses.values.any { it == "High" } -> "HIGH"
        statuses.values.any { it == "Warning" } -> "MEDIUM"
        else -> "LOW"
    }

    /** Record each simulated alert and each overall risk-level transition. */
    @Synchronized fun record(context: Context, id: String, behavior: String, severity: String,
                             statuses: Map<String, String>) {
        val sessions = read(context)
        val session = (0 until sessions.length()).mapNotNull { sessions.optJSONObject(it) }
            .lastOrNull { it.optString("id") == id && it.optBoolean("active") } ?: return
        val now = System.currentTimeMillis()
        session.put("endedMs", now)
        if (severity != "Normal") session.optJSONArray("alerts")?.put(
            JSONObject().put("timestampMs", now).put("behavior", behavior)
                .put("severity", severity))
        val risk = overall(statuses)
        val timeline = session.optJSONArray("timeline") ?: JSONArray()
        if (timeline.length() == 0 || timeline.optJSONObject(timeline.length() - 1)?.optString("level") != risk)
            timeline.put(point(now, risk))
        session.put("timeline", timeline)
        save(context, sessions)
    }

    /** Save a recovery point while monitoring continues in the foreground. */
    @Synchronized fun checkpoint(context: Context, id: String, now: Long = System.currentTimeMillis()) {
        val sessions = read(context)
        val session = (0 until sessions.length()).mapNotNull { sessions.optJSONObject(it) }
            .lastOrNull { it.optString("id") == id && it.optBoolean("active") } ?: return
        session.put("endedMs", now)
        save(context, sessions)
    }

    @Synchronized fun stop(context: Context, id: String, endedMs: Long = System.currentTimeMillis()) {
        val sessions = read(context)
        val session = (0 until sessions.length()).mapNotNull { sessions.optJSONObject(it) }
            .lastOrNull { it.optString("id") == id && it.optBoolean("active") } ?: return
        session.put("endedMs", endedMs).put("active", false)
        save(context, prune(sessions))
    }

    private fun parse(session: JSONObject): Session {
        val timeline = session.optJSONArray("timeline") ?: JSONArray()
        val alerts = session.optJSONArray("alerts") ?: JSONArray()
        return Session(session.optString("id"), session.optLong("startedMs"),
            session.optLong("endedMs"), session.optBoolean("active"),
            (0 until timeline.length()).mapNotNull { timeline.optJSONObject(it) }.map {
                RiskPoint(it.optLong("timestampMs"), it.optString("level"))
            }, (0 until alerts.length()).mapNotNull { alerts.optJSONObject(it) }.map {
                Alert(it.optLong("timestampMs"), it.optString("behavior"), it.optString("severity"))
            })
    }

    /** Keep short active sessions privately until they are long enough to display. */
    @Synchronized fun list(context: Context): List<Session> {
        val original = read(context)
        val sessions = prune(original)
        if (sessions.length() != original.length()) save(context, sessions)
        return (0 until sessions.length()).mapNotNull { sessions.optJSONObject(it) }
            .map(::parse).filter { it.durationMs() >= MIN_DURATION_MS }
            .sortedByDescending { it.startedMs }
    }

    @Synchronized fun get(context: Context, id: String): Session? = list(context).firstOrNull { it.id == id }
}
