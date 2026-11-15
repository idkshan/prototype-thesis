package edu.dlsu.esca_3_2526_c3.dms

import org.json.JSONArray
import org.json.JSONObject

/*
 * Guided recording: the app tells the participant what to do and when, and writes
 * the label file itself (labels.csv in the format of dms/evaluation/labels.py), so
 * nobody has to watch the recording afterwards to label it. Times use the session
 * clock of the frames (MonitorSession.now()), so labels and frames line up.
 *
 *   step with a countdown:  "Get ready: close your eyes … 3, 2, 1"  (not labelled)
 *                           "Now!" … action for N seconds           (labelled)
 *                           "Open your eyes"                         (end cue)
 *   normal driving:         unlabelled = "normal" for every behaviour
 */

class GuidedStep(
    val label: String?, // label name (labels.py); null = normal driving
    val instruction: String,
    val seconds: Double,
    val endCue: String = "",
    val countdown: Boolean = label != null,
)

class GuidedPlan(val name: String, val steps: List<GuidedStep>) {
    val totalSeconds: Double get() = steps.sumOf { it.seconds + if (it.countdown) GuidedRecorder.COUNTDOWN_S else 0.0 }

    companion object {
        private fun normal(s: Double) = GuidedStep(null, "Drive normally: look at the road", s)
        private val CALIBRATE = GuidedStep(null, "Look at the road with a relaxed face (calibration)", 10.0)

        val STANDARD = GuidedPlan("standard", listOf(
            CALIBRATE, normal(30.0),
            GuidedStep("eye_closure", "Close your eyes", 3.0, "Open your eyes"), normal(20.0),
            GuidedStep("yawning", "Yawn: a real, wide yawn", 6.0, "Drive normally"), normal(20.0),
            GuidedStep("gaze_deviation", "Look out of the side window on your left", 4.0, "Look back at the road"), normal(20.0),
            GuidedStep("gaze_deviation", "Look down at your lap", 4.0, "Look back at the road"), normal(20.0),
            GuidedStep("phone_usage", "Hold the phone to your ear as if calling", 8.0, "Put the phone down"), normal(20.0),
            GuidedStep("phone_usage", "Type on the phone in your lap", 8.0, "Put the phone down"), normal(20.0),
            GuidedStep("mirror_check", "Glance at the rear-view mirror", 1.5, "Back to the road"), normal(15.0),
            GuidedStep("talking", "Talk out loud: say your name and today's date", 8.0, "Stop talking"), normal(20.0),
            GuidedStep("drowsy", "Act sleepy: blink slowly, let your eyes close for 1 to 2 seconds now and then", 60.0,
                "Wake up and drive normally"), normal(30.0),
        ))
        val QUICK = GuidedPlan("quick", listOf(
            CALIBRATE, normal(15.0),
            GuidedStep("eye_closure", "Close your eyes", 3.0, "Open your eyes"), normal(15.0),
            GuidedStep("yawning", "Yawn: a real, wide yawn", 6.0, "Drive normally"), normal(15.0),
            GuidedStep("gaze_deviation", "Look out of the side window on your left", 4.0, "Look back at the road"), normal(15.0),
            GuidedStep("phone_usage", "Hold the phone to your ear as if calling", 8.0, "Put the phone down"), normal(15.0),
            GuidedStep("drowsy", "Act sleepy: blink slowly, let your eyes close for 1 to 2 seconds now and then", 30.0,
                "Wake up and drive normally"), normal(15.0),
        ))
        val ALL = listOf(STANDARD, QUICK)
    }
}

class GuidedRecorder(val plan: GuidedPlan, val startT: Double) {
    companion object {
        const val COUNTDOWN_S = 3.0
        private fun fmt(t: Double) = "%.2f".format(java.util.Locale.US, t)
    }

    class Phase(val step: Int, val countdown: Boolean, val start: Double, val end: Double)
    class Segment(val step: Int, val label: String, val start: Double, val end: Double)
    class Cue(val t: Double, val speak: String)
    class State(val step: Int, val text: String, val secondsLeft: Double, val countdown: Boolean, val finished: Boolean)

    val phases: List<Phase>
    val segments: List<Segment>
    val cues: List<Cue>
    private val removed = sortedSetOf<Int>()
    var stoppedAt: Double? = null
        private set

    init {
        val ph = mutableListOf<Phase>(); val sg = mutableListOf<Segment>(); val cu = mutableListOf<Cue>()
        var t = startT
        plan.steps.forEachIndexed { i, s ->
            if (s.countdown) {
                ph += Phase(i, true, t, t + COUNTDOWN_S)
                cu += Cue(t, "Get ready. ${s.instruction}. 3"); cu += Cue(t + 1, "2"); cu += Cue(t + 2, "1")
                t += COUNTDOWN_S
                cu += Cue(t, "Now")
            } else if (i == 0 || plan.steps[i - 1].endCue.isEmpty()) {
                cu += Cue(t, s.instruction) // (after an action its end cue, e.g. "Open your eyes", is enough)
            }
            ph += Phase(i, false, t, t + s.seconds)
            if (s.label != null) sg += Segment(i, s.label, t, t + s.seconds)
            t += s.seconds
            if (s.endCue.isNotEmpty()) cu += Cue(t, s.endCue)
        }
        cu += Cue(t, "Recording finished. Thank you.")
        phases = ph; segments = sg; cues = cu
    }

    val endT: Double get() = phases.last().end

    fun state(t: Double): State {
        if (stoppedAt != null || t >= endT) return State(plan.steps.size, "Finished", 0.0, false, true)
        val p = phases.lastOrNull { it.start <= t } ?: phases.first()
        val s = plan.steps[p.step]
        val text = if (p.countdown) "Get ready: ${s.instruction}" else s.instruction
        return State(p.step, text, p.end - maxOf(t, p.start), p.countdown, false)
    }

    /** Cues to speak for the clock moving from t0 (exclusive) to t1 (inclusive). */
    fun cuesBetween(t0: Double, t1: Double): List<Cue> = cues.filter { it.t > t0 && it.t <= t1 }

    /** "Not done": drop the label of the latest action that has started (the participant did not do it). */
    fun markLastActionNotDone(t: Double): Segment? {
        val seg = segments.lastOrNull { it.start <= t && it.step !in removed } ?: return null
        removed += seg.step
        return seg
    }

    fun stop(t: Double) { if (stoppedAt == null) stoppedAt = t }

    /** Labels actually recorded: removed actions left out, an early stop cuts the last one. */
    fun recordedSegments(): List<Segment> {
        val stop = stoppedAt ?: Double.MAX_VALUE
        return segments.filter { it.step !in removed && it.start < stop }
            .map { if (it.end > stop) Segment(it.step, it.label, it.start, stop) else it }
    }

    fun labelsCsv(): String = buildString {
        append("behavior,start_s,end_s\n")
        for (s in recordedSegments()) append("${s.label},${fmt(s.start)},${fmt(s.end)}\n")
    }

    fun summary(participant: String, condition: String): JSONObject = JSONObject()
        .put("participant", participant).put("condition", condition).put("script", plan.name)
        .put("start_t", startT).put("planned_end_t", endT).put("stopped_t", stoppedAt ?: JSONObject.NULL)
        .put("completed", stoppedAt == null || stoppedAt!! >= endT)
        .put("removed_steps", JSONArray(removed.map { JSONObject().put("step", it).put("label", plan.steps[it].label)
            .put("instruction", plan.steps[it].instruction) }))
        .put("labels", recordedSegments().size)
}
