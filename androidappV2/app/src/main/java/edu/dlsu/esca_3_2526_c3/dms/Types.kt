package edu.dlsu.esca_3_2526_c3.dms

import java.math.BigDecimal
import java.math.RoundingMode

/*
 * Data passed between the pipeline stages (Kotlin port of dms/types.py).
 *
 *   Stage 1  AI perception        -> PerceptionResult (FaceObservation, Detection)
 *   Stage 2  visual features      -> FrameFeatures     (plain numbers)
 *   Stage 3  threshold rules      -> FrameStates + BehaviorStatus
 *   Stage 4  severity grading     -> SeverityReport
 *   Stage 5  fuzzy driver risk    -> RiskAssessment -> AlertCommand
 */

val SEVERITY_LEVELS = listOf("NONE", "MILD", "MODERATE", "SEVERE")
val RISK_TIERS = listOf("NONE", "LOW", "MEDIUM", "HIGH")
val BEHAVIORS = listOf("drowsiness", "yawning", "gaze_deviation", "phone_usage")

// ----------------------------------------------------------------- Stage 1
/** MediaPipe Face Landmarker output: 478 landmarks (x, y in pixels, z), 52 blendshapes, face box. */
class FaceObservation(
    val landmarks: Array<DoubleArray>,
    val blendshapes: Map<String, Double>,
    val bbox: DoubleArray, // x1, y1, x2, y2
) {
    companion object {
        /** Face box = extent of the landmarks (as in dms/perception/face_landmarker.py). */
        fun fromLandmarks(landmarks: Array<DoubleArray>, blendshapes: Map<String, Double>): FaceObservation {
            var x1 = Double.MAX_VALUE; var y1 = Double.MAX_VALUE; var x2 = -Double.MAX_VALUE; var y2 = -Double.MAX_VALUE
            for (p in landmarks) {
                x1 = minOf(x1, p[0]); y1 = minOf(y1, p[1]); x2 = maxOf(x2, p[0]); y2 = maxOf(y2, p[1])
            }
            return FaceObservation(landmarks, blendshapes, doubleArrayOf(x1, y1, x2, y2))
        }
    }
}

/** One YOLO box. role = phone | eye_open | eye_closed | yawn | no_yawn (from the class name). */
class Detection(
    val label: String,
    val confidence: Double,
    val box: DoubleArray, // x1, y1, x2, y2 in pixels
    val role: String? = null,
    var inDriverRoi: Boolean = true,
)

class PerceptionResult(
    val width: Int,
    val height: Int,
    val face: FaceObservation?,
    val detections: List<Detection> = emptyList(),
    val eyeClosedProb: Double? = null,
    val detectorRoles: Set<String> = emptySet(),
)

// ----------------------------------------------------------------- Stage 2
data class FrameFeatures(
    val t: Double,
    val facePresent: Boolean,
    val ear: Double? = null,
    val earLeft: Double? = null,
    val earRight: Double? = null,
    val mar: Double? = null,
    val yaw: Double? = null, // degrees, + = face turned toward image right
    val pitch: Double? = null, // degrees, + = face tilted up
    val roll: Double? = null,
    val blink: Double? = null,
    val jawOpen: Double? = null,
    val eyeClosedProb: Double? = null,
    val eyeClosedYolo: Double? = null,
    val yawnYolo: Double? = null,
    val eyeClosedFusion: Double? = null, // fusion model: P(eyes closed) from all numbers of this picture
    val yawnFusion: Double? = null, // fusion model: P(yawning)
    val phoneConf: Double = 0.0,
    val phoneBoxes: Int = 0,
    val eyeWidthPx: Double? = null,
    val lookDown: Double? = null,
)

// ----------------------------------------------------------------- Stage 3
class FrameStates {
    var eyesClosed: Boolean? = null
    var eyeEvidence: Double? = null
    var eyeSources: Map<String, Double> = emptyMap()
    var mouthOpen: Boolean? = null
    var mouthEvidence: Double? = null
    var mouthSources: Map<String, Double> = emptyMap()
    var headAway: Boolean? = null
    var poseEvidence: Double? = null
    var phoneVisible = false
    var faceLostS = 0.0
    var faceStatus = "ok" // ok | lost_turning | lost | absent
    var eyesNotJudged: String? = null // head_away | phone | yawn | quality | no_face
    var frameGap = false
}

class BehaviorStatus(
    val closureS: Double = 0.0,
    val perclos: Double? = null,
    val mouthOpenS: Double = 0.0,
    val yawnsInWindow: Int = 0,
    val gazeAwayS: Double = 0.0,
    val offroadRatio: Double = 0.0,
    val phoneS: Double = 0.0,
    val longClosures: Int = 0,
    val blinkRatePerMin: Double? = null,
    val eyeLevel: Int = 0,
    val drowsinessState: String = "alert", // alert | slightly_drowsy | moderately_drowsy
    val drowsy: Boolean = false,
    val eyeClosure: Boolean = false,
    val yawning: Boolean = false,
    val gazeDeviation: Boolean = false,
    val phoneUsage: Boolean = false,
    val events: List<String> = emptyList(),
    var active: List<String> = emptyList(),
    val noFace: Boolean = false,
    val faceStatus: String = "ok",
    val dataValid: Boolean = true,
)

// ----------------------------------------------------------------- Stage 4
data class Severity(val score: Double, val level: String, val reason: String = "")

class SeverityReport(val drowsiness: Severity, val yawning: Severity, val gazeDeviation: Severity, val phoneUsage: Severity) {
    fun asMap(): Map<String, Severity> =
        linkedMapOf("drowsiness" to drowsiness, "yawning" to yawning, "gaze_deviation" to gazeDeviation, "phone_usage" to phoneUsage)

    fun maxScore(): Double = asMap().values.maxOf { it.score }
}

// ----------------------------------------------------------------- Stage 5
data class ContextReading(
    val speedKmh: Double? = null,
    val motionG: Double? = null,
    val drivingS: Double = 0.0,
    val speedSource: String = "unknown", // gps | fixed | assumed | unknown
    val gpsAgeS: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

class RiskAssessment(
    val fatigue: Double,
    val distraction: Double,
    val context: Double,
    val risk: Double, // 0..100
    val tier: String, // NONE / LOW / MEDIUM / HIGH (with hysteresis)
    val fatigueLevel: String = "Low",
    val rawTier: String = "NONE",
    val inputs: Map<String, Double> = emptyMap(),
    val topRules: List<String> = emptyList(),
)

/** One entry of the alert stream (see AlertStateMachine). action = soft_beep | repeating_buzzer | alarm | off. */
data class AlertCommand(
    val t: Double,
    val tier: String,
    val action: String,
    val push: Boolean,
    val reason: String,
    val newEpisode: Boolean,
    val event: String = "beep",
    val id: String = "",
    val alertId: String = "",
    val behaviors: List<String> = emptyList(),
)

// ----------------------------------------------------------------- numeric helpers (numpy / Python behaviour)
object Num {
    /** Python round(x, n): exact binary value, ties to even. */
    fun pyRound(x: Double, digits: Int): Double =
        if (x.isNaN() || x.isInfinite()) x else BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()

    /** numpy.interp for increasing xs, clamped at both ends. */
    fun interp(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
        if (x <= xs[0]) return ys[0]
        val n = xs.size
        if (x >= xs[n - 1]) return ys[n - 1]
        var i = 0
        while (i < n - 1 && x >= xs[i + 1]) i++
        val slope = (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]) // same operation order as numpy
        val r = slope * (x - xs[i]) + ys[i]
        return if (r.isNaN()) slope * (x - xs[i + 1]) + ys[i + 1] else r
    }

    fun piecewise(anchors: Anchors, x: Double): Double =
        interp(x, DoubleArray(anchors.size) { anchors[it][0] }, DoubleArray(anchors.size) { anchors[it][1] })

    /** numpy.median. */
    fun median(values: List<Double>): Double {
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** numpy.percentile with linear interpolation. */
    fun percentile(values: List<Double>, q: Double): Double {
        val s = values.sorted()
        val pos = q / 100.0 * (s.size - 1)
        val lo = kotlin.math.floor(pos).toInt()
        val hi = kotlin.math.min(lo + 1, s.size - 1)
        val frac = pos - lo
        return s[lo] + (s[hi] - s[lo]) * frac
    }
}
