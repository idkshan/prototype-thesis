package edu.dlsu.esca_3_2526_c3

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local history of monitoring sessions (History tab): risk timeline and alerts.
 * The full measurement log of a session (frames.jsonl, alerts.jsonl, summary.json, used by
 * scripts/evaluate_behaviors.py) is in the folder named by logDir. No Firebase schema changes.
 */
object SessionHistoryStore {
    private const val PREFS = "driver_session_history_v1"
    private const val KEY = "sessions"
    private const val MIN_DURATION_MS = 10_000L
    private const val MAX_SESSIONS = 100
    private const val MAX_ALERTS = 300

    data class RiskPoint(val timestampMs: Long, val level: String)
    data class Alert(val timestampMs: Long, val behavior: String, val severity: String, val simulated: Boolean)
    data class Session(
        val id: String, val startedMs: Long, val endedMs: Long, val active: Boolean,
        val timeline: List<RiskPoint>, val alerts: List<Alert>, val logDir: String,
        val syncTracked: Boolean, // false: recorded before the supervisor sync was tracked per session
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

    private fun isRunning(session: JSONObject) = session.optBoolean("active") && DriverMonitoringService.active &&
            session.optString("id") == DriverMonitoringService.sessionId

    /** Discard sessions shorter than 10 seconds (except the running one); keep the newest 100. */
    private fun prune(sessions: JSONArray): JSONArray {
        val kept = mutableListOf<JSONObject>()
        for (i in 0 until sessions.length()) {
            val session = sessions.optJSONObject(i) ?: continue
            if (isRunning(session) || session.optLong("endedMs") - session.optLong("startedMs") >= MIN_DURATION_MS)
                kept.add(session)
        }
        return JSONArray(kept.takeLast(MAX_SESSIONS))
    }

    private fun current(sessions: JSONArray, id: String): JSONObject? =
        (0 until sessions.length()).mapNotNull { sessions.optJSONObject(it) }
            .lastOrNull { it.optString("id") == id && it.optBoolean("active") }

    @Synchronized fun start(context: Context, id: String, startedMs: Long) {
        val sessions = prune(read(context))
        sessions.put(JSONObject().put("id", id).put("startedMs", startedMs)
            .put("endedMs", startedMs).put("active", true)
            .put("timeline", JSONArray().put(point(startedMs, "NORMAL")))
            .put("alerts", JSONArray()).put("logDir", "").put("cloud", true))
        save(context, sessions)
    }

    private fun point(at: Long, level: String) =
        JSONObject().put("timestampMs", at).put("level", level)

    private fun overall(statuses: Map<String, String>): String = when {
        statuses.values.any { it == "High" } -> "HIGH"
        statuses.values.any { it == "Warning" } -> "MEDIUM"
        else -> "NORMAL"
    }

    private fun addPoint(session: JSONObject, now: Long, level: String) {
        val timeline = session.optJSONArray("timeline") ?: JSONArray()
        if (timeline.length() == 0 || timeline.optJSONObject(timeline.length() - 1)?.optString("level") != level)
            timeline.put(point(now, level))
        session.put("timeline", timeline)
    }

    private fun addAlert(session: JSONObject, now: Long, behavior: String, severity: String, simulated: Boolean) {
        val alerts = session.optJSONArray("alerts") ?: JSONArray()
        if (alerts.length() < MAX_ALERTS) alerts.put(JSONObject().put("timestampMs", now).put("behavior", behavior)
            .put("severity", severity).put("simulated", simulated))
        session.put("alerts", alerts)
    }

    /** Developer-mode test: record a simulated alert and the overall level of the test statuses. */
    @Synchronized fun record(context: Context, id: String, behavior: String, severity: String,
                             statuses: Map<String, String>) {
        val sessions = read(context)
        val session = current(sessions, id) ?: return
        val now = System.currentTimeMillis()
        session.put("endedMs", now)
        if (severity != "Normal") addAlert(session, now, behavior, severity, simulated = true)
        addPoint(session, now, overall(statuses))
        save(context, sessions)
    }

    /** Real alert from the detection program (alert start or escalation). */
    @Synchronized fun recordAlert(context: Context, id: String, behavior: String, tier: String) {
        val sessions = read(context)
        val session = current(sessions, id) ?: return
        val now = System.currentTimeMillis()
        session.put("endedMs", now)
        addAlert(session, now, behavior, tier, simulated = false)
        save(context, sessions)
    }

    /** Real risk level change (NORMAL / LOW / MEDIUM / HIGH, or UNAVAILABLE without a camera picture). */
    @Synchronized fun recordRisk(context: Context, id: String, level: String) {
        val sessions = read(context)
        val session = current(sessions, id) ?: return
        val now = System.currentTimeMillis()
        session.put("endedMs", now)
        addPoint(session, now, level)
        save(context, sessions)
    }

    /** Folder of the session's measurement log, for sharing it from History. */
    @Synchronized fun setLogDir(context: Context, id: String, dir: String) {
        val sessions = read(context)
        val session = current(sessions, id) ?: return
        session.put("logDir", dir)
        save(context, sessions)
    }

    /** Save a recovery point while monitoring continues in the foreground. */
    @Synchronized fun checkpoint(context: Context, id: String, now: Long = System.currentTimeMillis()) {
        val sessions = read(context)
        val session = current(sessions, id) ?: return
        session.put("endedMs", now)
        save(context, sessions)
    }

    @Synchronized fun stop(context: Context, id: String, endedMs: Long = System.currentTimeMillis()) {
        val sessions = read(context)
        val session = current(sessions, id) ?: return
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
                // Sessions saved by the first V2 app were all simulated (no "simulated" field).
                Alert(it.optLong("timestampMs"), it.optString("behavior"), it.optString("severity"),
                    it.optBoolean("simulated", true))
            }, session.optString("logDir"), session.optBoolean("cloud", false))
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

    /** Remove a finished session and its measurement log folder from the phone. */
    @Synchronized fun delete(context: Context, id: String) {
        val sessions = read(context)
        val kept = JSONArray()
        for (i in 0 until sessions.length()) {
            val session = sessions.optJSONObject(i) ?: continue
            if (session.optString("id") == id && !isRunning(session)) {
                val dir = session.optString("logDir")
                if (dir.isNotBlank()) try { java.io.File(dir).deleteRecursively() } catch (_: Exception) {}
            } else kept.put(session)
        }
        save(context, kept)
    }

    @Synchronized fun get(context: Context, id: String): Session? = list(context).firstOrNull { it.id == id }
}
