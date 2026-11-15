package edu.dlsu.esca_3_2526_c3.dms

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File

/**
 * Session log in the desktop format (dms/session_log.py): frames.jsonl, alerts.jsonl,
 * summary.json. Copy the folder to the laptop and score it with the same tools:
 *     python scripts/evaluate_behaviors.py --session <folder> --labels <labels.csv>
 *     python scripts/analyze_session.py <folder>
 */
class SessionLog(val dir: File, private val meta: JSONObject = JSONObject()) {
    private val frames: BufferedWriter
    private val alerts: BufferedWriter
    private var n = 0
    private var faceFrames = 0
    private var firstT: Double? = null
    private var lastT = 0.0
    private val timings = mutableMapOf<String, MutableList<Double>>()
    private val episodes = linkedMapOf("LOW" to 0, "MEDIUM" to 0, "HIGH" to 0)
    private val alertEvents = linkedMapOf<String, Int>()
    private val flagFrames = linkedMapOf("drowsy" to 0, "eye_closure" to 0, "yawning" to 0, "gaze_deviation" to 0, "phone_usage" to 0)
    private val states = linkedMapOf<String, Int>()
    private val wall0 = System.nanoTime()

    init {
        dir.mkdirs()
        frames = File(dir, "frames.jsonl").bufferedWriter()
        alerts = File(dir, "alerts.jsonl").bufferedWriter()
    }

    private fun r(x: Double?, digits: Int = 4): Any = x?.let { Num.pyRound(it, digits) } ?: JSONObject.NULL
    private fun opt(x: Any?): Any = x ?: JSONObject.NULL

    fun features(f: FrameFeatures) = JSONObject()
        .put("t", r(f.t)).put("face_present", f.facePresent).put("ear", r(f.ear)).put("ear_left", r(f.earLeft))
        .put("ear_right", r(f.earRight)).put("mar", r(f.mar)).put("yaw", r(f.yaw)).put("pitch", r(f.pitch)).put("roll", r(f.roll))
        .put("blink", r(f.blink)).put("jaw_open", r(f.jawOpen)).put("eye_closed_prob", r(f.eyeClosedProb))
        .put("eye_closed_yolo", r(f.eyeClosedYolo)).put("yawn_yolo", r(f.yawnYolo))
        .put("eye_closed_fusion", r(f.eyeClosedFusion)).put("yawn_fusion", r(f.yawnFusion)).put("phone_conf", r(f.phoneConf))
        .put("phone_boxes", f.phoneBoxes).put("eye_width_px", r(f.eyeWidthPx)).put("look_down", r(f.lookDown))

    private fun behavior(b: BehaviorStatus) = JSONObject()
        .put("closure_s", r(b.closureS)).put("perclos", r(b.perclos)).put("mouth_open_s", r(b.mouthOpenS))
        .put("yawns_in_window", b.yawnsInWindow).put("gaze_away_s", r(b.gazeAwayS)).put("offroad_ratio", r(b.offroadRatio))
        .put("phone_s", r(b.phoneS)).put("long_closures", b.longClosures).put("blink_rate_per_min", r(b.blinkRatePerMin))
        .put("eye_level", b.eyeLevel).put("drowsiness_state", b.drowsinessState).put("drowsy", b.drowsy)
        .put("eye_closure", b.eyeClosure).put("yawning", b.yawning).put("gaze_deviation", b.gazeDeviation)
        .put("phone_usage", b.phoneUsage).put("events", JSONArray(b.events)).put("active", JSONArray(b.active))
        .put("no_face", b.noFace).put("face_status", b.faceStatus).put("data_valid", b.dataValid)

    fun alert(a: AlertCommand) = JSONObject()
        .put("t", a.t).put("tier", a.tier).put("action", a.action).put("push", a.push).put("reason", a.reason)
        .put("new_episode", a.newEpisode).put("event", a.event).put("id", a.id).put("alert_id", a.alertId)
        .put("behaviors", JSONArray(a.behaviors))

    @Synchronized
    fun log(index: Int, f: FrameFeatures, out: EngineOutput, timingsMs: Map<String, Double>) {
        n += 1
        if (firstT == null) firstT = f.t
        lastT = f.t
        if (f.facePresent) faceFrames += 1
        val b = out.behavior
        flagFrames["drowsy"] = flagFrames.getValue("drowsy") + if (b.drowsy) 1 else 0
        flagFrames["eye_closure"] = flagFrames.getValue("eye_closure") + if (b.eyeClosure) 1 else 0
        flagFrames["yawning"] = flagFrames.getValue("yawning") + if (b.yawning) 1 else 0
        flagFrames["gaze_deviation"] = flagFrames.getValue("gaze_deviation") + if (b.gazeDeviation) 1 else 0
        flagFrames["phone_usage"] = flagFrames.getValue("phone_usage") + if (b.phoneUsage) 1 else 0
        states[b.drowsinessState] = (states[b.drowsinessState] ?: 0) + 1
        for ((k, v) in timingsMs) timings.getOrPut(k) { mutableListOf() }.add(v)
        val s = out.states
        val sev = JSONObject()
        for ((k, v) in out.severity.asMap()) sev.put(k, JSONObject().put("score", v.score).put("level", v.level))
        val rk = out.risk
        val c = out.context
        val rec = JSONObject()
            .put("t", r(f.t)).put("i", index).put("features", features(f))
            .put("states", JSONObject().put("eyes_closed", opt(s.eyesClosed)).put("eye_evidence", r(s.eyeEvidence))
                .put("mouth_open", opt(s.mouthOpen)).put("mouth_evidence", r(s.mouthEvidence)).put("head_away", opt(s.headAway))
                .put("pose_evidence", r(s.poseEvidence)).put("phone_visible", s.phoneVisible))
            .put("behavior", behavior(b)).put("severity", sev)
            .put("risk", JSONObject().put("F", rk.fatigue).put("D", rk.distraction).put("C", rk.context).put("R", rk.risk)
                .put("tier", rk.tier).put("raw_tier", rk.rawTier).put("fatigue_level", rk.fatigueLevel))
            .put("active_tier", out.activeTier).put("alert_state", out.alertState)
            .put("context", JSONObject().put("speed_kmh", r(c.speedKmh)).put("motion_g", r(c.motionG)).put("driving_s", r(c.drivingS))
                .put("speed_source", c.speedSource).put("gps_age_s", r(c.gpsAgeS)).put("latitude", opt(c.latitude)).put("longitude", opt(c.longitude)))
            .put("calibration", out.calibrationStatus)
            .put("timings_ms", JSONObject(timingsMs.mapValues { Num.pyRound(it.value, 2) }))
        frames.write(rec.toString()); frames.newLine()
        logAlerts(out.alerts)
    }

    @Synchronized
    fun logAlerts(list: List<AlertCommand>) {
        for (a in list) {
            alertEvents[a.event] = (alertEvents[a.event] ?: 0) + 1
            if (a.newEpisode && a.tier in episodes) episodes[a.tier] = episodes.getValue(a.tier) + 1
            alerts.write(alert(a).toString()); alerts.newLine()
        }
        alerts.flush()
    }

    @Synchronized
    fun close(calibration: DriverCalibrator, extra: JSONObject = JSONObject()): JSONObject {
        frames.close(); alerts.close()
        val duration = (lastT - (firstT ?: lastT))
        val wall = (System.nanoTime() - wall0) / 1e9
        val stats = JSONObject()
        for ((k, v) in timings) {
            stats.put(k, JSONObject().put("mean", Num.pyRound(v.average(), 2)).put("median", Num.pyRound(Num.median(v), 2))
                .put("p95", Num.pyRound(Num.percentile(v, 95.0), 2)))
        }
        val bl = calibration.baseline
        val summary = JSONObject(meta.toString())
            .put("code_version", "android-3.0")
            .put("frames", n)
            .put("video_duration_s", Num.pyRound(duration, 2))
            .put("wall_time_s", Num.pyRound(wall, 2))
            .put("input_fps", if (duration > 0) Num.pyRound((n - 1) / duration, 2) else JSONObject.NULL)
            .put("processing_fps", timings["total_ms"]?.let { if (it.isNotEmpty()) Num.pyRound(1000.0 / it.average(), 2) else null } ?: JSONObject.NULL)
            .put("face_detected_ratio", if (n > 0) Num.pyRound(faceFrames.toDouble() / n, 4) else 0.0)
            .put("stage_latency_ms", stats)
            .put("alert_episodes", JSONObject(episodes as Map<*, *>))
            .put("alert_events", JSONObject(alertEvents as Map<*, *>))
            .put("behavior_flag_frames", JSONObject(flagFrames as Map<*, *>))
            .put("drowsiness_state_frames", JSONObject(states as Map<*, *>))
            .put("calibration", JSONObject().put("status", calibration.status).put("warning", calibration.warning)
                .put("last_failure", calibration.lastFailure)
                .put("baseline", JSONObject().put("ear", bl.ear).put("mar", bl.mar).put("yaw", bl.yaw).put("pitch", bl.pitch)
                    .put("blink", bl.blink).put("jaw", bl.jaw).put("ear_left", opt(bl.earLeft)).put("ear_right", opt(bl.earRight))))
        for (k in extra.keys()) summary.put(k, extra.get(k))
        File(dir, "summary.json").writeText(summary.toString(2))
        return summary
    }
}
