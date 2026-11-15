package edu.dlsu.esca_3_2526_c3.dms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier

/** Small checks of the parts the golden data does not cover directly. */
class CoreTest {

    private fun cmd(event: String, tier: String, action: String) = AlertCommand(0.0, tier, action, false, "", event == "start", event)

    @Test
    fun buzzerLevelsFollowTheAlertCommands() {
        assertEquals(1, TierProtocol.level(cmd("start", "LOW", "soft_beep")))
        assertEquals(3, TierProtocol.level(cmd("escalate", "HIGH", "alarm")))
        assertEquals(2, TierProtocol.level(cmd("keepalive", "MEDIUM", "repeating_buzzer")))
        assertEquals(0, TierProtocol.level(cmd("stop", "HIGH", "off")))
        assertEquals(0, TierProtocol.level(cmd("unavailable", "NONE", "off")))
        assertNull(TierProtocol.level(cmd("push", "HIGH", "alarm"))) // not a buzzer command
    }

    @Test
    fun configJsonOverridesOnlyTheGivenKeys() {
        val cfg = Config.fromJson("""{"drowsiness": {"recovery_s": 10.0}, "alerts": {"medium_max_repeats": 5},
            "severity": {"gaze_s": [[0, 0], [2.5, 0.34], [5, 1]]}, "face": {"model_path": "models/x.task"}}""")
        assertEquals(10.0, cfg.drowsiness.recoveryS, 0.0)
        assertEquals(5, cfg.alerts.mediumMaxRepeats)
        assertEquals(3, cfg.severity.gazeS.size)
        assertEquals(60.0, cfg.drowsiness.windowS, 0.0) // untouched default
        assertEquals("face_landmarker.task", cfg.face.modelPath) // desktop model paths are ignored
    }

    /** All settings of a Config as plain values (reflection), to compare two configs. */
    private fun dump(o: Any?): Any? = when (o) {
        null, is Number, is String, is Boolean -> o
        is DoubleArray -> o.toList()
        is IntArray -> o.toList()
        is Array<*> -> o.map { dump(it) }
        is Collection<*> -> o.map { dump(it) }
        is Map<*, *> -> o.entries.associate { it.key.toString() to dump(it.value) }
        else -> if (o.javaClass.name.startsWith("edu.dlsu")) {
            o.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.sortedBy { it.name }
                .associate { f -> f.isAccessible = true; f.name to dump(f.get(o)) }
        } else o.toString()
    }

    /** Every leaf of the JSON tree: its path and a function that writes a changed value into a copy. */
    private fun leaves(node: Any, path: String, out: MutableList<Pair<String, (JSONObject) -> Unit>>, at: (JSONObject) -> Any) {
        when (node) {
            is JSONObject -> for (k in node.keys()) {
                val child = node.get(k)
                if (child is JSONObject || (child is JSONArray && child.length() > 0 && child.get(0) is JSONArray)) {
                    leaves(child, "$path.$k", out) { root -> (at(root) as JSONObject).get(k) }
                } else {
                    out += "$path.$k" to { root: JSONObject -> (at(root) as JSONObject).put(k, changed(child)) }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                out += "$path[$i]" to { root: JSONObject -> (at(root) as JSONArray).put(i, changed(node.get(i))) }
            }
        }
    }

    private fun changed(v: Any): Any = when (v) {
        is Boolean -> !v
        is Int -> v * 2 + 1
        is Long -> v * 2 + 1
        is Number -> v.toDouble() * 2 + 1
        is String -> v + "_x"
        is JSONArray -> JSONArray(v.toString()).apply { if (length() == 0) put("x") else put(0, changed(get(0))) }
        else -> 1.5 // null -> a number
    }

    @Test
    fun configJsonCoversEverySetting() {
        // config.json = the desktop defaults (scripts/export_android_config.py): the phone must read
        // the same values, and every desktop setting must change something on the phone
        val text = javaClass.getResource("/golden/config.json")!!.readText()
        val defaults = dump(Config())
        assertEquals(defaults, dump(Config.fromJson(text)))
        val all = mutableListOf<Pair<String, (JSONObject) -> Unit>>()
        leaves(JSONObject(text), "", all) { it }
        val ignored = all.filter { (_, edit) ->
            val copy = JSONObject(text)
            edit(copy)
            try { dump(Config.fromJson(copy.toString())) == defaults } catch (_: Exception) { false }
        }.map { it.first }
        assertTrue("settings the phone ignores: $ignored", ignored.isEmpty())
        assertTrue(all.size > 100)
    }

    @Test
    fun aimingAdviceDirections() {
        val a = AimAdvice.of(30.0, 12.0, 25.0)
        assertFalse(a.ok)
        assertEquals("move LEFT 30 deg", a.horizontal)
        assertEquals("move UP 12 deg", a.vertical)
        val b = AimAdvice.of(-14.0, -5.0, 25.0)
        assertTrue(b.ok)
        assertEquals("move RIGHT 14 deg", b.horizontal)
        assertEquals("good", b.vertical)
    }

    @Test
    fun headPoseSignsMatchTheCameraPosition() {
        // a face turned 30 deg toward the image's right -> yaw +30 (camera sits to the driver's right)
        val w = 640; val h = 480; val f = w.toDouble()
        val r = Mat3.rodrigues(Mat3.rvecFromYawPitch(30.0, 0.0))
        val lm = Array(478) { DoubleArray(3) }
        for ((k, idx) in HeadPose.POSE_LANDMARKS.withIndex()) {
            val p = Mat3.mulVec(r, HeadPose.MODEL_POINTS[k])
            val z = p[2] + 3000.0
            lm[idx][0] = f * p[0] / z + w / 2.0
            lm[idx][1] = f * p[1] / z + h / 2.0
        }
        val pose = HeadPose.estimate(lm, w, h)!!
        assertEquals(30.0, pose[0], 0.05)
        assertEquals(0.0, pose[1], 0.05)
    }

    @Test
    fun numpyCompatibleHelpers() {
        assertEquals(2.5, Num.median(listOf(4.0, 1.0, 3.0, 2.0)), 0.0)
        assertEquals(1.75, Num.percentile(listOf(1.0, 2.0, 3.0, 4.0), 25.0), 1e-12)
        assertEquals(0.5, Num.interp(0.5, doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0)), 0.0)
        assertEquals(1.0, Num.interp(9.0, doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0)), 0.0)
        assertEquals(0.12, Num.pyRound(0.125, 2), 0.0) // ties to even, like Python
    }
}
