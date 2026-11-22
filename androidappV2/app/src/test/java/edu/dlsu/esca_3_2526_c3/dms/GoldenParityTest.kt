package edu.dlsu.esca_3_2526_c3.dms

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * The phone must decide exactly like the desktop program. These tests replay the
 * reference data written by scripts/export_android_golden.py (desktop = reference)
 * and compare every frame: states, behaviour, severity, fuzzy risk, alert commands.
 */
class GoldenParityTest {

    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("golden/$name")
        ?: error("missing test resource golden/$name: run python scripts/export_android_golden.py")

    private fun JSONObject.d(key: String): Double? = if (isNull(key)) null else getDouble(key)
    private fun JSONObject.b(key: String): Boolean? = if (isNull(key)) null else getBoolean(key)
    private fun JSONObject.s(key: String): String? = if (isNull(key)) null else getString(key)

    private fun features(o: JSONObject) = FrameFeatures(
        t = o.getDouble("t"), facePresent = o.getBoolean("face_present"),
        ear = o.d("ear"), earLeft = o.d("ear_left"), earRight = o.d("ear_right"), mar = o.d("mar"),
        yaw = o.d("yaw"), pitch = o.d("pitch"), roll = o.d("roll"), blink = o.d("blink"), jawOpen = o.d("jaw_open"),
        eyeClosedProb = o.d("eye_closed_prob"), eyeClosedYolo = o.d("eye_closed_yolo"), yawnYolo = o.d("yawn_yolo"),
        eyeClosedFusion = o.d("eye_closed_fusion"), yawnFusion = o.d("yawn_fusion"),
        phoneConf = o.getDouble("phone_conf"), phoneBoxes = o.getInt("phone_boxes"), eyeWidthPx = o.d("eye_width_px"),
        lookDown = o.d("look_down"),
    )

    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    private fun close(where: String, expected: Double?, actual: Double?, tol: Double = 1e-6) {
        if (expected == null || actual == null) {
            assertEquals(where, expected, actual)
            return
        }
        if (abs(expected - actual) > tol) fail("$where: expected $expected, got $actual")
    }

    private fun commands(a: List<AlertCommand>) = a.map { listOf(it.event, it.tier, it.action, it.push.toString(), it.id, it.alertId) }
    private fun commands(a: JSONArray) = (0 until a.length()).map { i ->
        a.getJSONArray(i).let { c -> listOf(c.getString(0), c.getString(1), c.getString(2), c.getBoolean(3).toString(), c.getString(4), c.getString(5)) }
    }

    @Test
    fun engineMatchesDesktopFrameByFrame() {
        val reader = GZIPInputStream(resource("engine.jsonl.gz")).bufferedReader()
        var sessions = 0
        var frames = 0
        reader.forEachLine { line ->
            val session = JSONObject(line)
            val name = session.getString("name")
            val cfg = Config().apply { applyJson(session.getJSONObject("config")) }
            val engine = DecisionEngine(cfg, alertIdPrefix = "g")
            val rows = session.getJSONArray("frames")
            var lastT: Double? = null
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val f = features(row.getJSONObject("f"))
                val exp = row.getJSONObject("exp")
                val where = "$name frame $i (t=${f.t})"
                val lost = if (lastT != null && f.t - lastT!! > cfg.alerts.cameraTimeoutS) engine.cameraLost(lastT!! + cfg.alerts.cameraTimeoutS, "no frames") else emptyList()
                assertEquals("$where lost", commands(exp.getJSONArray("lost")), commands(lost))
                lastT = f.t
                val c = row.getJSONObject("ctx")
                val ctx = ContextReading(speedKmh = c.d("speed_kmh"), motionG = c.d("motion_g"), drivingS = c.getDouble("driving_s"), speedSource = c.getString("speed_source"))
                val out = engine.step(f, ctx)

                val st = exp.getJSONObject("states")
                assertEquals("$where eyes_closed", st.b("eyes_closed"), out.states.eyesClosed)
                close("$where eye_evidence", st.d("eye_evidence"), out.states.eyeEvidence)
                assertEquals("$where mouth_open", st.b("mouth_open"), out.states.mouthOpen)
                close("$where mouth_evidence", st.d("mouth_evidence"), out.states.mouthEvidence)
                assertEquals("$where head_away", st.b("head_away"), out.states.headAway)
                close("$where pose_evidence", st.d("pose_evidence"), out.states.poseEvidence)
                assertEquals("$where phone_visible", st.getBoolean("phone_visible"), out.states.phoneVisible)
                assertEquals("$where face_status", st.getString("face_status"), out.states.faceStatus)
                assertEquals("$where eyes_not_judged", st.s("eyes_not_judged"), out.states.eyesNotJudged)
                assertEquals("$where frame_gap", st.getBoolean("frame_gap"), out.states.frameGap)

                val b = exp.getJSONObject("behavior")
                val ob = out.behavior
                close("$where closure_s", b.d("closure_s"), ob.closureS)
                close("$where perclos", b.d("perclos"), ob.perclos)
                close("$where mouth_open_s", b.d("mouth_open_s"), ob.mouthOpenS)
                assertEquals("$where yawns_in_window", b.getInt("yawns_in_window"), ob.yawnsInWindow)
                close("$where gaze_away_s", b.d("gaze_away_s"), ob.gazeAwayS)
                close("$where offroad_ratio", b.d("offroad_ratio"), ob.offroadRatio)
                close("$where phone_s", b.d("phone_s"), ob.phoneS)
                assertEquals("$where long_closures", b.getInt("long_closures"), ob.longClosures)
                close("$where blink_rate", b.d("blink_rate_per_min"), ob.blinkRatePerMin)
                assertEquals("$where eye_level", b.getInt("eye_level"), ob.eyeLevel)
                assertEquals("$where drowsiness_state", b.getString("drowsiness_state"), ob.drowsinessState)
                assertEquals("$where eye_closure", b.getBoolean("eye_closure"), ob.eyeClosure)
                assertEquals("$where yawning", b.getBoolean("yawning"), ob.yawning)
                assertEquals("$where gaze_deviation", b.getBoolean("gaze_deviation"), ob.gazeDeviation)
                assertEquals("$where phone_usage", b.getBoolean("phone_usage"), ob.phoneUsage)
                assertEquals("$where events", strings(b.getJSONArray("events")), ob.events)
                assertEquals("$where active", strings(b.getJSONArray("active")), ob.active)
                assertEquals("$where no_face", b.getBoolean("no_face"), ob.noFace)
                assertEquals("$where data_valid", b.getBoolean("data_valid"), ob.dataValid)

                val sev = exp.getJSONObject("severity")
                for ((key, s) in out.severity.asMap()) {
                    val e = sev.getJSONArray(key)
                    close("$where severity $key", e.getDouble(0), s.score, 1e-9)
                    assertEquals("$where severity level $key", e.getString(1), s.level)
                }
                val r = exp.getJSONObject("risk")
                close("$where fatigue", r.getDouble("fatigue"), out.risk.fatigue, 1e-9)
                close("$where distraction", r.getDouble("distraction"), out.risk.distraction, 1e-9)
                close("$where context", r.getDouble("context"), out.risk.context, 1e-9)
                close("$where risk", r.getDouble("risk"), out.risk.risk, 1e-9)
                assertEquals("$where tier", r.getString("tier"), out.risk.tier)
                assertEquals("$where raw_tier", r.getString("raw_tier"), out.risk.rawTier)
                assertEquals("$where fatigue_level", r.getString("fatigue_level"), out.risk.fatigueLevel)

                assertEquals("$where calibration", exp.getString("calibration"), out.calibrationStatus)
                assertEquals("$where alert_state", exp.getString("alert_state"), out.alertState)
                assertEquals("$where active_tier", exp.getString("active_tier"), out.activeTier)
                assertEquals("$where alerts", commands(exp.getJSONArray("alerts")), commands(out.alerts))
                frames++
            }
            val fin = session.getJSONObject("final")
            val base = fin.getJSONArray("baseline")
            val bl = engine.calibrator.baseline
            val got = listOf(bl.ear, bl.mar, bl.yaw, bl.pitch, bl.blink, bl.jaw, bl.earLeft, bl.earRight)
            for (k in got.indices) close("$name baseline[$k]", if (base.isNull(k)) null else base.getDouble(k), got[k], 1e-9)
            assertEquals("$name warning", fin.getString("warning"), engine.calibrator.warning)
            val ep = fin.getJSONObject("episodes")
            for (tier in listOf("LOW", "MEDIUM", "HIGH")) assertEquals("$name episodes $tier", ep.getInt(tier), engine.alerts.episodes.getValue(tier))
            sessions++
        }
        assertTrue("expected test drives, found $sessions", sessions >= 20)
        println("engine parity: $sessions test drives, $frames frames identical to the desktop")
    }

    @Test
    fun featuresMatchDesktop() {
        val cases = JSONArray(resource("features.json").bufferedReader().readText())
        val cfg = Config()
        val extractor = FeatureExtractor(cfg.detector)
        val poseDiffs = mutableListOf<Double>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val face = if (c.isNull("landmarks")) null else {
                val lm = c.getJSONArray("landmarks")
                val pts = Array(lm.length()) { k -> lm.getJSONArray(k).let { p -> doubleArrayOf(p.getDouble(0), p.getDouble(1), p.getDouble(2)) } }
                val bs = c.getJSONObject("blendshapes")
                FaceObservation.fromLandmarks(pts, bs.keys().asSequence().associateWith { bs.getDouble(it) })
            }
            val dets = c.getJSONArray("detections").let { a ->
                (0 until a.length()).map { k ->
                    val d = a.getJSONArray(k)
                    val box = d.getJSONArray(2)
                    Detection(d.getString(0), d.getDouble(1), DoubleArray(4) { box.getDouble(it) }, if (d.isNull(3)) null else d.getString(3))
                }
            }
            val roles = strings(c.getJSONArray("roles")).toSet()
            val f = extractor.extract(PerceptionResult(c.getInt("w"), c.getInt("h"), face, dets, null, roles), if (face == null) 2.0 else 1.5)
            val e = c.getJSONObject("expected")
            val where = "features case $i"
            assertEquals("$where face_present", e.getBoolean("face_present"), f.facePresent)
            for ((key, got) in listOf("ear" to f.ear, "ear_left" to f.earLeft, "ear_right" to f.earRight, "mar" to f.mar,
                    "blink" to f.blink, "jaw_open" to f.jawOpen, "eye_closed_yolo" to f.eyeClosedYolo, "yawn_yolo" to f.yawnYolo,
                    "eye_width_px" to f.eyeWidthPx, "look_down" to f.lookDown)) {
                close("$where $key", e.d(key), got, 1e-9)
            }
            close("$where phone_conf", e.getDouble("phone_conf"), f.phoneConf, 1e-12)
            assertEquals("$where phone_boxes", e.getInt("phone_boxes"), f.phoneBoxes)
            val inRoi = c.getJSONArray("in_roi")
            for (k in dets.indices) assertEquals("$where in_roi[$k]", inRoi.getBoolean(k), dets[k].inDriverRoi)
            // head pose: the same objective as OpenCV's SQPnP, solved independently. SQPnP sometimes stops
            // slightly short of the minimum of its own objective when the landmarks fit the generic face
            // model poorly (checked in Python: e.g. cost 4787.7 vs 4781.7 -> 0.85 deg); the rules use
            // changes from the driver's own baseline with 20-30 deg limits, so up to 1.5 deg is accepted.
            for ((key, got) in listOf("yaw" to f.yaw, "pitch" to f.pitch, "roll" to f.roll)) {
                close("$where $key", e.d(key), got, 1.5)
                if (got != null && !e.isNull(key)) poseDiffs.add(abs(got - e.getDouble(key)))
            }
        }
        poseDiffs.sort()
        println("features parity: ${cases.length()} cases; head pose vs OpenCV: median %.3f, max %.3f deg"
            .format(poseDiffs[poseDiffs.size / 2], poseDiffs.last()))
    }

    @Test
    fun fusionModelMatchesDesktop() {
        val g = JSONObject(resource("fusion.json").bufferedReader().readText())
        val model = FusionModel.fromJson(g.getJSONObject("model").toString())
        assertEquals(setOf("eye_closed", "yawn"), model.headNames)
        val cases = g.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val inp = c.getJSONObject("inputs")
            val values = inp.keys().asSequence().associateWith { k -> inp.d(k) }
            val got = model.predict(values)
            val e = c.getJSONObject("expected")
            for (head in listOf("eye_closed", "yawn")) close("fusion case $i $head", e.d(head), got[head], 1e-12)
        }
        // wiring: the extractor adds the model's outputs to the features of a real face
        val face = JSONArray(resource("features.json").bufferedReader().readText()).let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.first { !it.isNull("landmarks") }
        }
        val lm = face.getJSONArray("landmarks")
        val bs = face.getJSONObject("blendshapes")
        val obs = FaceObservation.fromLandmarks(Array(lm.length()) { k -> lm.getJSONArray(k).let { p -> doubleArrayOf(p.getDouble(0), p.getDouble(1), p.getDouble(2)) } },
            bs.keys().asSequence().associateWith { bs.getDouble(it) })
        val p = PerceptionResult(face.getInt("w"), face.getInt("h"), obs, emptyList(), null, emptySet())
        val plain = FeatureExtractor(Config().detector).extract(p, 1.0)
        val fused = FeatureExtractor(Config().detector, model).extract(p, 1.0)
        val want = model.predict(FusionInputs.of(obs, plain))
        assertEquals(null, plain.eyeClosedFusion)
        close("extractor eye_closed_fusion", want["eye_closed"], fused.eyeClosedFusion, 0.0)
        close("extractor yawn_fusion", want["yawn"], fused.yawnFusion, 0.0)
        assertTrue(fused.eyeClosedFusion != null && plain.copy(eyeClosedFusion = fused.eyeClosedFusion, yawnFusion = fused.yawnFusion) == fused)
        println("fusion parity: ${cases.length()} cases identical to the desktop")
    }

    @Test
    fun sensorContextMatchesDesktop() {
        val rows = JSONArray(resource("context.json").bufferedReader().readText())
        val ctx = SensorContext(Config().context)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val t = row.getDouble("t")
            if (!row.isNull("reading")) ctx.ingest(row.getJSONObject("reading"), t)
            val r = ctx.read(t)
            val e = row.getJSONObject("expected")
            val where = "context t=$t"
            close("$where speed", e.d("speed_kmh"), r.speedKmh, 1e-12)
            close("$where motion", e.d("motion_g"), r.motionG, 1e-12)
            close("$where driving_s", e.getDouble("driving_s"), r.drivingS, 1e-9)
            assertEquals("$where source", e.getString("speed_source"), r.speedSource)
            close("$where gps_age", e.d("gps_age_s"), r.gpsAgeS, 1e-9)
            close("$where lat", e.d("latitude"), r.latitude, 1e-12)
            close("$where lon", e.d("longitude"), r.longitude, 1e-12)
        }
    }
}
