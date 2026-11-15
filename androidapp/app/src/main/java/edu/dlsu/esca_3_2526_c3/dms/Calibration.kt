package edu.dlsu.esca_3_2526_c3.dms

import kotlin.math.exp
import kotlin.math.hypot

/*
 * Stage 3 helpers (Kotlin port of dms/classification/evidence.py, temporal.py, calibration.py).
 *
 * Evidence scale shared by every signal: 0 = the driver's own baseline,
 * 0.5 = the driver's threshold, 1 = clearly past it. Sources are fused by a
 * weighted mean and a state is ON when the fused evidence reaches 0.5.
 */

object Evidence {
    /** Linear evidence: 0 at zeroAt (baseline), 0.5 at halfAt (threshold), clamped to 0..1. */
    fun ramp(x: Double?, zeroAt: Double, halfAt: Double): Double? {
        if (x == null) return null
        val span = halfAt - zeroAt
        if (kotlin.math.abs(span) < 1e-9) return if ((x - zeroAt) * (if (span >= 0) 1 else -1) > 0) 1.0 else 0.0
        return minOf(1.0, maxOf(0.0, 0.5 * (x - zeroAt) / span))
    }

    /** Weighted mean of the available (non-null, weight > 0) sources, in insertion order. */
    fun fuse(sources: Map<String, Double?>, weights: Map<String, Double>): Double? {
        var total = 0.0
        var weightSum = 0.0
        for ((name, value) in sources) {
            val w = weights[name] ?: 0.0
            if (value == null || w <= 0) continue
            total += w * value
            weightSum += w
        }
        return if (weightSum == 0.0) null else total / weightSum
    }
}

/** How long a per-frame state has been continuously ON; gaps up to gapS do not end an episode. */
class EpisodeTracker(private val gapS: Double) {
    var active = false
        private set
    var start = 0.0
        private set
    var lastOn = 0.0
        private set
    private val fired = mutableSetOf<String>()
    private val ended = ArrayDeque<Pair<Double, Double>>() // (start, end), at most 64

    /** Feed one frame (null = unknown, treated like OFF). Returns the episode that just ended, if any. */
    fun update(t: Double, on: Boolean?): Pair<Double, Double>? {
        if (on == true) {
            if (!active) {
                active = true; start = t
                fired.clear()
            }
            lastOn = t
        } else if (active && t - lastOn > gapS) {
            return forceEnd()
        }
        return null
    }

    /** End the ongoing episode at its last ON frame (e.g. after a camera stall). */
    fun forceEnd(): Pair<Double, Double>? {
        if (!active) return null
        val e = Pair(start, lastOn)
        ended.addLast(e)
        if (ended.size > 64) ended.removeFirst()
        active = false
        return e
    }

    fun duration(): Double = if (active) lastOn - start else 0.0

    /** Longest episode that is ongoing or ended within the last holdS seconds. */
    fun recentMax(t: Double, holdS: Double): Double {
        var best = duration()
        for ((s, e) in ended) if (t - e <= holdS) best = maxOf(best, e - s)
        return best
    }

    /** True exactly once per episode, when its duration first reaches thresholdS. */
    fun fireOnce(key: String, thresholdS: Double): Boolean {
        if (active && key !in fired && duration() >= thresholdS) {
            fired.add(key)
            return true
        }
        return false
    }
}

/** Fraction of time a state was ON over a sliding window (e.g. PERCLOS). */
class TimeWeightedRatio(private val windowS: Double, private val maxDt: Double = 0.5) {
    private data class Sample(val t: Double, val dt: Double, val on: Boolean)
    private val samples = ArrayDeque<Sample>()
    private var lastT: Double? = null
    private var onTime = 0.0
    private var total = 0.0

    fun add(t: Double, on: Boolean) {
        val dt = lastT?.let { minOf(maxDt, maxOf(0.0, t - it)) } ?: 0.0
        lastT = t
        samples.addLast(Sample(t, dt, on))
        total += dt
        onTime += if (on) dt else 0.0
        while (samples.isNotEmpty() && t - samples.first().t > windowS) {
            val old = samples.removeFirst()
            total -= old.dt
            onTime -= if (old.on) old.dt else 0.0
        }
    }

    /** Advance time without a sample (state not observable on this frame). */
    fun skip(t: Double) { lastT = t }

    fun reset() {
        samples.clear(); lastT = null; onTime = 0.0; total = 0.0
    }

    val coverageS: Double get() = maxOf(0.0, total)
    val ratio: Double get() = if (total > 1e-9) onTime / total else 0.0
}

/** Counts events within a sliding time window (e.g. yawns in 5 minutes). */
class EventWindow(private val windowS: Double) {
    private val times = ArrayDeque<Double>()

    fun add(t: Double) = times.addLast(t)

    fun count(t: Double): Int {
        while (times.isNotEmpty() && t - times.first() > windowS) times.removeFirst()
        return times.size
    }

    fun countSince(t: Double, since: Double): Int {
        count(t)
        return times.count { it > since }
    }

    fun last(): Double? = times.lastOrNull()
}

// ----------------------------------------------------------------------- calibration
const val CALIBRATING = "CALIBRATING"
const val CALIBRATED = "CALIBRATED"
const val DEFAULT = "DEFAULT"

class Baseline(
    var ear: Double, var mar: Double, var yaw: Double, var pitch: Double, var blink: Double, var jaw: Double,
    var earLeft: Double? = null, var earRight: Double? = null,
) {
    fun copy() = Baseline(ear, mar, yaw, pitch, blink, jaw, earLeft, earRight)
}

/** Absolute thresholds for the current driver, derived from the baseline. */
class Thresholds(
    val baseline: Baseline,
    val earClosed: Double,
    val blinkClosed: Double,
    val marOpen: Double,
    val jawOpen: Double,
    val yawLimit: Double,
    val pitchDownLimit: Double,
    val pitchUpLimit: Double,
    val decisionLevel: Double,
    val calibrated: Boolean,
    val earClosedRatio: Double = 0.70,
) {
    companion object {
        fun fromBaseline(b: Baseline, cfg: ThresholdConfig, calibrated: Boolean): Thresholds {
            val poseScale = if (calibrated) 1.0 else cfg.uncalibratedPoseScale
            return Thresholds(
                baseline = b,
                earClosed = b.ear * cfg.earClosedRatio,
                blinkClosed = minOf(0.95, b.blink + cfg.blinkClosedDelta),
                marOpen = b.mar + cfg.marOpenDelta,
                jawOpen = minOf(0.95, b.jaw + cfg.jawOpenDelta),
                yawLimit = cfg.yawDeg * poseScale,
                pitchDownLimit = cfg.pitchDownDeg * poseScale,
                pitchUpLimit = cfg.pitchUpDeg * poseScale,
                decisionLevel = cfg.decisionLevel,
                calibrated = calibrated,
                earClosedRatio = cfg.earClosedRatio,
            )
        }
    }
}

/**
 * Per-driver baseline (Section 1.7.9): medians of the first few seconds while the
 * driver looks ahead with a relaxed face; then slow adaptation on confidently alert,
 * forward-facing frames, clamped near the calibrated values.
 */
class DriverCalibrator(private val cfg: CalibrationConfig, private val thrCfg: ThresholdConfig, private val phoneConfLimit: Double = 0.35) {
    var warning = "" // e.g. camera far off the face axis (calibration still accepted)
        private set
    private val defaultBaseline = cfg.defaults.let { Baseline(it.ear, it.mar, it.yaw, it.pitch, it.blink, it.jaw) }
    var baseline = defaultBaseline.copy()
        private set
    var anchor: Baseline? = null
        private set
    var status = if (cfg.enabled) CALIBRATING else DEFAULT
        private set
    var lastFailure = ""
        private set
    private var windowStart: Double? = null
    private var samples = mutableListOf<FrameFeatures>()
    private var lastAdaptT: Double? = null
    var thresholds = Thresholds.fromBaseline(baseline, thrCfg, calibrated = false)
        private set

    /** Begin a new calibration window (e.g. new driver). */
    fun restart() {
        if (!cfg.enabled) return
        status = CALIBRATING
        windowStart = null
        samples = mutableListOf()
    }

    fun progress(t: Double): Double {
        val ws = windowStart
        if (status != CALIBRATING || ws == null) return if (status == CALIBRATED) 1.0 else 0.0
        return minOf(1.0, (t - ws) / cfg.durationS)
    }

    fun collect(f: FrameFeatures) {
        if (status != CALIBRATING) return
        if (windowStart == null) windowStart = f.t
        val usable = f.facePresent && f.ear != null && f.mar != null && f.yaw != null && f.phoneConf < phoneConfLimit
        if (usable) samples.add(f)
        if (f.t - windowStart!! >= cfg.durationS) finishWindow()
    }

    fun adapt(f: FrameFeatures) {
        val anc = anchor
        if (status != CALIBRATED || !cfg.adapt || anc == null) {
            lastAdaptT = null
            return
        }
        val last = lastAdaptT
        if (last == null) {
            lastAdaptT = f.t
            return
        }
        val dt = minOf(1.0, maxOf(0.0, f.t - last))
        lastAdaptT = f.t
        val a = 1.0 - exp(-dt / cfg.adaptTauS)
        val b = baseline
        val c = cfg
        fun step(cur: Double, x: Double?, lo: Double, hi: Double) = if (x == null) cur else minOf(hi, maxOf(lo, cur + a * (x - cur)))

        b.ear = step(b.ear, f.ear, anc.ear * (1 - c.maxEarDrift), anc.ear * (1 + c.maxEarDrift))
        val al = anc.earLeft; val ar = anc.earRight
        if (al != null && ar != null && al != 0.0 && ar != 0.0) {
            b.earLeft = step(b.earLeft!!, f.earLeft, al * (1 - c.maxEarDrift), al * (1 + c.maxEarDrift))
            b.earRight = step(b.earRight!!, f.earRight, ar * (1 - c.maxEarDrift), ar * (1 + c.maxEarDrift))
        }
        b.mar = step(b.mar, f.mar, maxOf(0.0, anc.mar - c.maxMarDrift), anc.mar + c.maxMarDrift)
        b.yaw = step(b.yaw, f.yaw, anc.yaw - c.maxPoseDriftDeg, anc.yaw + c.maxPoseDriftDeg)
        b.pitch = step(b.pitch, f.pitch, anc.pitch - c.maxPoseDriftDeg, anc.pitch + c.maxPoseDriftDeg)
        b.blink = step(b.blink, f.blink, 0.0, anc.blink + 0.15)
        b.jaw = step(b.jaw, f.jawOpen, 0.0, anc.jaw + 0.10)
        thresholds = Thresholds.fromBaseline(b, thrCfg, calibrated = true)
    }

    /** Called whenever a drowsy/distracted state is present (no baseline drift). */
    fun pauseAdaptation() { lastAdaptT = null }

    private fun robustStd(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        return (Num.percentile(values, 75.0) - Num.percentile(values, 25.0)) / 1.349
    }

    private fun finishWindow() {
        val s = samples
        samples = mutableListOf()
        windowStart = null
        if (s.size < cfg.minSamples) {
            lastFailure = "only ${s.size} usable frames (need ${cfg.minSamples})"
            return
        }
        fun arr(get: (FrameFeatures) -> Double?) = s.mapNotNull(get)
        fun med(a: List<Double>, default: Double) = if (a.isNotEmpty()) Num.median(a) else default
        val ear = arr { it.ear }; val mar = arr { it.mar }; val yaw = arr { it.yaw }; val pitch = arr { it.pitch }
        val blink = arr { it.blink }; val jaw = arr { it.jawOpen }
        val earL = arr { it.earLeft }; val earR = arr { it.earRight }
        val candidate = Baseline(
            ear = med(ear, defaultBaseline.ear),
            mar = med(mar, defaultBaseline.mar),
            yaw = med(yaw, 0.0),
            pitch = med(pitch, 0.0),
            blink = med(blink, defaultBaseline.blink),
            jaw = med(jaw, defaultBaseline.jaw),
            earLeft = if (earL.isNotEmpty()) Num.median(earL) else null,
            earRight = if (earR.isNotEmpty()) Num.median(earR) else null,
        )
        val poseStd = maxOf(robustStd(yaw), robustStd(pitch))
        val problems = mutableListOf<String>()
        if (poseStd > cfg.maxPoseStdDeg) problems.add("head not steady (pose std %.1f deg)".format(poseStd))
        if (candidate.ear < cfg.minEar) problems.add("eyes not open (EAR %.3f)".format(candidate.ear))
        if (candidate.mar > cfg.maxMar) problems.add("mouth not neutral (MAR %.3f)".format(candidate.mar))
        if (candidate.blink > cfg.maxBlink) problems.add("eyeBlink too high (%.2f)".format(candidate.blink))
        if (problems.isNotEmpty()) {
            lastFailure = problems.joinToString("; ")
            return // stay CALIBRATING with defaults; the next window retries
        }
        baseline = candidate
        anchor = candidate.copy()
        status = CALIBRATED
        lastFailure = ""
        val offset = hypot(candidate.yaw, candidate.pitch)
        warning = if (offset > cfg.maxCameraOffsetDeg)
            "camera is %.0f deg off the driver's face axis (limit %.0f): eye/mouth measurements are unreliable - mount the camera in front of the driver"
                .format(offset, cfg.maxCameraOffsetDeg)
        else ""
        thresholds = Thresholds.fromBaseline(baseline, thrCfg, calibrated = true)
    }
}
