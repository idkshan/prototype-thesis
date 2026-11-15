package edu.dlsu.esca_3_2526_c3.dms

import kotlin.math.abs

/*
 * Stage 3: one class per behaviour (Kotlin port of dms/behaviors/ and
 * dms/classification/classifier.py). Each answers:
 *   frameState()  is the sign present in THIS frame? (thresholds on the AI evidence)
 *   update()      is it a real behaviour, and how much of it recently? (seconds, windows)
 * Order per frame: quality -> gaze -> phone -> yawning -> drowsiness. The eyes are
 * NOT judged while the head is turned away, a phone is in view or the driver yawns,
 * so one behaviour cannot trigger another.
 */

/** Camera stalls and tiny faces must not look like driver behaviour. */
class FrameQuality(private val cfg: QualityConfig) {
    private var lastT: Double? = null
    var gaps = 0
        private set

    fun checkGap(t: Double): Boolean {
        val gap = lastT != null && (t - lastT!!) > cfg.maxFrameGapS
        lastT = t
        if (gap) gaps += 1
        return gap
    }

    fun eyesMeasurable(f: FrameFeatures): Boolean = f.eyeWidthPx == null || f.eyeWidthPx >= cfg.minEyeWidthPx
}

// ------------------------------------------------------------------------- gaze
class GazeStatus(val gazeAwayS: Double, val offroadRatio: Double, val gazeDeviation: Boolean, val noFace: Boolean, val events: List<String>)

/** Head turned away from the driver's forward pose >= 2 s (shorter = mirror check). */
class GazeDetector(private val cfg: Config) {
    private val episode = EpisodeTracker(cfg.temporal.gazeGapS)
    private val offroad = TimeWeightedRatio(cfg.temporal.offroadWindowS)
    private var lastFaceT: Double? = null
    private val recentPose = ArrayDeque<Pair<Double, Double>>() // (t, pose evidence) of recent face frames

    fun frameState(f: FrameFeatures, thr: Thresholds, st: FrameStates) {
        val tc = cfg.temporal; val q = cfg.quality
        if (!f.facePresent) {
            val lf = lastFaceT
            if (lf == null) {
                st.faceStatus = "absent" // never seen a face yet
                return
            }
            st.faceLostS = f.t - lf
            val wasTurning = recentPose.any { it.second >= q.faceLossTurningEvidence }
            if (st.faceLostS > tc.faceLossMaxS) {
                st.faceStatus = "absent"
            } else if (tc.faceLossAsGazeAway && wasTurning) {
                st.faceStatus = "lost_turning"
                st.headAway = true
                st.poseEvidence = 1.0
            } else {
                st.faceStatus = "lost" // gaze unknown (hand over the camera, glare, dropout)
            }
            return
        }
        lastFaceT = f.t
        val yaw = f.yaw ?: return
        val pitch = f.pitch ?: return
        val b = thr.baseline
        val dp = pitch - b.pitch
        val ev = maxOf(
            Evidence.ramp(abs(yaw - b.yaw), 0.0, thr.yawLimit)!!,
            Evidence.ramp(-dp, 0.0, thr.pitchDownLimit)!!,
            Evidence.ramp(dp, 0.0, thr.pitchUpLimit)!!,
        )
        st.poseEvidence = ev
        st.headAway = ev >= thr.decisionLevel
        recentPose.addLast(Pair(f.t, ev))
        while (recentPose.isNotEmpty() && f.t - recentPose.first().first > q.faceLossLookbackS) recentPose.removeFirst()
    }

    fun update(t: Double, st: FrameStates, gap: Boolean): GazeStatus {
        val tc = cfg.temporal
        if (gap) episode.forceEnd()
        episode.update(t, st.headAway)
        val ha = st.headAway
        if (ha == null) offroad.skip(t) else offroad.add(t, ha)
        val events = if (episode.fireOnce("gaze", tc.gazeMinS)) listOf("gaze_deviation") else emptyList()
        return GazeStatus(
            gazeAwayS = episode.recentMax(t, tc.gazeHoldS),
            // off-road time over a fixed window length, so one early glance is not over-weighted
            offroadRatio = offroad.ratio * minOf(1.0, offroad.coverageS / tc.offroadWindowS),
            gazeDeviation = episode.active && episode.duration() >= tc.gazeMinS,
            noFace = st.faceStatus == "absent",
            events = events,
        )
    }

    val active: Boolean get() = episode.active
}

// ------------------------------------------------------------------------- phone
class PhoneStatus(val phoneS: Double, val phoneUsage: Boolean, val events: List<String>)

/** Phone held near the driver >= 3 s; starts at conf 0.35, continues down to 0.20 (hysteresis). */
class PhoneDetector(private val cfg: Config) {
    private val episode = EpisodeTracker(cfg.temporal.phoneGapS)

    fun frameState(f: FrameFeatures, st: FrameStates) {
        val d = cfg.detector
        val threshold = if (episode.active) minOf(d.phoneStayConf, d.conf) else d.conf
        st.phoneVisible = f.phoneConf > 0.0 && f.phoneConf >= threshold
    }

    fun update(t: Double, st: FrameStates, gap: Boolean): PhoneStatus {
        val tc = cfg.temporal
        if (gap) episode.forceEnd()
        episode.update(t, st.phoneVisible)
        val events = if (episode.fireOnce("phone", tc.phoneConfirmS)) listOf("phone_usage") else emptyList()
        return PhoneStatus(
            phoneS = episode.recentMax(t, tc.phoneHoldS),
            phoneUsage = episode.active && episode.duration() >= tc.phoneConfirmS,
            events = events,
        )
    }

    val active: Boolean get() = episode.active
}

// ------------------------------------------------------------------------- yawning
class YawnStatus(val mouthOpenS: Double, val yawnsInWindow: Int, val yawning: Boolean, val events: List<String>)

/** Mouth wide open >= 1.5 s (shorter = speech); yawns counted over 5 minutes. */
class YawningDetector(private val cfg: Config) {
    private val episode = EpisodeTracker(cfg.temporal.yawnGapS)
    val yawns = EventWindow(cfg.temporal.yawnWindowS)
    private var lastMouthActiveT = Double.NEGATIVE_INFINITY

    fun frameState(f: FrameFeatures, thr: Thresholds, st: FrameStates) {
        if (!f.facePresent) return
        val ev = cfg.evidence; val b = thr.baseline
        val sources = linkedMapOf(
            "mar" to Evidence.ramp(f.mar, b.mar, thr.marOpen),
            "blendshape" to Evidence.ramp(f.jawOpen, b.jaw, thr.jawOpen),
            "yolo" to f.yawnYolo,
            "fusion" to f.yawnFusion,
        )
        st.mouthSources = sources.filterValues { it != null }.mapValues { it.value!! }
        val m = Evidence.fuse(sources, mapOf("mar" to ev.mouthMar, "blendshape" to ev.mouthBlendshape, "yolo" to ev.mouthYolo,
            "fusion" to ev.mouthFusion))
        st.mouthEvidence = m
        if (m != null) {
            st.mouthOpen = m >= thr.decisionLevel
            if (m >= cfg.temporal.yawnEyeGuardLevel) lastMouthActiveT = f.t
        }
    }

    /** True while the mouth is opening for a yawn or just closed (eyes squint then). */
    fun eyeGuard(t: Double, st: FrameStates): Boolean =
        st.mouthOpen == true || (t - lastMouthActiveT) <= cfg.temporal.yawnEyeGuardS

    fun update(t: Double, st: FrameStates, gap: Boolean): YawnStatus {
        val tc = cfg.temporal
        if (gap) episode.forceEnd()
        episode.update(t, st.mouthOpen)
        val events = mutableListOf<String>()
        if (episode.fireOnce("yawn", tc.yawnMinS)) {
            yawns.add(t)
            events.add("yawn")
        }
        return YawnStatus(
            mouthOpenS = episode.duration(),
            yawnsInWindow = yawns.count(t),
            yawning = episode.active && episode.duration() >= tc.yawnMinS,
            events = events,
        )
    }

    val active: Boolean get() = episode.active
}

// ------------------------------------------------------------------------- drowsiness
val DROWSINESS_STATES = listOf("alert", "slightly_drowsy", "moderately_drowsy")

class DrowsinessStatus(
    val closureS: Double, val eyeClosure: Boolean, val perclos: Double?, val longClosures: Int,
    val longestClosureS: Double, val blinkRatePerMin: Double?, val eyeLevel: Int, val state: String, val events: List<String>,
)

/**
 * Eyes closed (blinks < 0.5 s, long closures >= 0.5 s, microsleeps >= 1.5 s) and the
 * drowsiness STATE of thesis Table 1.11 (alert / slightly / moderately drowsy), with
 * recovery: 20 s without a new eye sign steps the eye level down one level.
 */
class DrowsinessDetector(private val cfg: Config, private val quality: FrameQuality) {
    private val closure = EpisodeTracker(cfg.temporal.closureGapS)
    private val perclos = TimeWeightedRatio(cfg.drowsiness.windowS)
    private val longClosures = EventWindow(cfg.drowsiness.windowS)
    private val blinks = EventWindow(60.0)
    private val closures = ArrayDeque<Pair<Double, Double>>() // (end time, duration) of long closures
    var eyeLevel = 0
        private set
    private var resetT = Double.NEGATIVE_INFINITY
    private var lastSignT = Double.NEGATIVE_INFINITY
    private var lastChangeT = Double.NEGATIVE_INFINITY
    private var firstT: Double? = null

    fun frameState(f: FrameFeatures, thr: Thresholds, st: FrameStates, mouthGuard: Boolean) {
        val tc = cfg.temporal; val q = cfg.quality; val ev = cfg.evidence
        if (!f.facePresent) {
            st.eyesNotJudged = "no_face"
            return
        }
        val b = thr.baseline
        var ear: Double? = null
        val el = f.earLeft; val er = f.earRight; val bl = b.earLeft; val br = b.earRight
        if (el != null && er != null && bl != null && br != null && bl != 0.0 && br != 0.0) {
            val left = Evidence.ramp(el, bl, bl * thr.earClosedRatio)!!
            val right = Evidence.ramp(er, br, br * thr.earClosedRatio)!!
            ear = (left + right) / 2.0
        } else if (f.ear != null) {
            ear = Evidence.ramp(f.ear, b.ear, thr.earClosed)
        }
        val sources = linkedMapOf(
            "ear" to ear,
            "blendshape" to Evidence.ramp(f.blink, b.blink, thr.blinkClosed),
            "cnn" to f.eyeClosedProb,
            "yolo" to f.eyeClosedYolo,
            "fusion" to f.eyeClosedFusion,
        )
        val weights = mutableMapOf("ear" to ev.eyeEar, "blendshape" to ev.eyeBlendshape, "cnn" to ev.eyeCnn, "yolo" to ev.eyeYolo,
            "fusion" to ev.eyeFusion)
        if (f.lookDown != null && f.lookDown >= q.lookDownEarGuard && sources["blendshape"] != null) {
            weights["ear"] = 0.0 // eyes looking down: lowered lids shrink EAR without closing
        }
        st.eyeSources = sources.filterValues { it != null }.mapValues { it.value!! }
        st.eyeEvidence = Evidence.fuse(sources, weights)

        when {
            st.headAway == true -> st.eyesNotJudged = "head_away"
            tc.suspendEyesWhenPhone && st.phoneVisible -> st.eyesNotJudged = "phone"
            mouthGuard -> st.eyesNotJudged = "yawn"
            !quality.eyesMeasurable(f) -> st.eyesNotJudged = "quality"
            st.eyeEvidence != null -> st.eyesClosed = st.eyeEvidence!! >= thr.decisionLevel
        }
    }

    /** Book an ended closure episode; true if it was a long closure. */
    private fun closureEnded(ended: Pair<Double, Double>?): Boolean {
        if (ended == null) return false
        val duration = ended.second - ended.first
        if (duration >= cfg.temporal.closureEventS) {
            longClosures.add(ended.second)
            closures.addLast(Pair(ended.second, duration))
            return true
        }
        if (duration >= 0.0) blinks.add(ended.second)
        return false
    }

    fun update(t: Double, st: FrameStates, gap: Boolean, yawns: EventWindow): DrowsinessStatus {
        val tc = cfg.temporal; val dc = cfg.drowsiness
        if (firstT == null) firstT = t
        var longEnded = false
        if (gap) longEnded = closureEnded(closure.forceEnd()) || longEnded // a stall must not make a fake microsleep
        longEnded = closureEnded(closure.update(t, st.eyesClosed)) || longEnded
        val ec = st.eyesClosed
        if (ec == null) perclos.skip(t) else perclos.add(t, ec)
        val events = if (closure.fireOnce("closure", tc.closureEventS)) listOf("eye_closure") else emptyList()

        val current = if (closure.active) closure.duration() else 0.0
        val closingNow = closure.active && current >= tc.closureEventS
        while (closures.isNotEmpty() && t - closures.first().first > dc.windowS) closures.removeFirst()
        val since = resetT
        val nLong = longClosures.countSince(t, since) + (if (closingNow) 1 else 0)
        val longest = (closures.filter { it.first > since }.map { it.second } + current).max()
        val pc = if (perclos.coverageS >= dc.perclosMinCoverageS) perclos.ratio else null
        var blinkRate: Double? = null
        if (t - firstT!! >= 60.0) blinkRate = blinks.count(t).toDouble() // blinks in the last 60 s = per minute

        val eyeSign = when {
            (pc != null && pc >= dc.moderatePerclos) || longest >= dc.moderateClosureS || nLong >= dc.moderateLongClosures -> 2
            (pc != null && pc >= dc.slightPerclos) || longest >= dc.slightClosureS || nLong >= dc.slightLongClosures ||
                (dc.blinkRateSlightPerMin > 0 && blinkRate != null && blinkRate >= dc.blinkRateSlightPerMin) -> 1
            else -> 0
        }
        if (closingNow || longEnded) lastSignT = t
        if (eyeSign > eyeLevel) {
            eyeLevel = eyeSign
            lastSignT = t; lastChangeT = t
        } else if (eyeLevel > 0 && t - lastSignT >= dc.recoveryS && t - lastChangeT >= dc.recoveryS) {
            eyeLevel -= 1 // recovery: step down one level and restart the eye windows
            lastChangeT = t; resetT = t
            perclos.reset()
        }

        val nYawns = yawns.count(t)
        val lastYawn = yawns.last()
        val recent = lastYawn != null && t - lastYawn <= dc.yawnHoldS
        var yawnLevel = 0
        if (recent) {
            yawnLevel = if (dc.moderateYawns != 0 && nYawns >= dc.moderateYawns) 2
            else if (dc.slightYawns != 0 && nYawns >= dc.slightYawns) 1 else 0
        }
        var level = maxOf(eyeLevel, yawnLevel)
        if (dc.yawnsPlusEyesModerate && yawnLevel >= 1 && eyeLevel >= 1) level = 2
        return DrowsinessStatus(
            closureS = closure.recentMax(t, tc.closureHoldS),
            eyeClosure = closingNow,
            perclos = pc,
            longClosures = nLong,
            longestClosureS = longest,
            blinkRatePerMin = blinkRate,
            eyeLevel = eyeLevel,
            state = DROWSINESS_STATES[level],
            events = events,
        )
    }

    val active: Boolean get() = closure.active
}

// ------------------------------------------------------------------------- classifier
/** Runs the behaviour modules for every frame and merges their results. */
class BehaviorClassifier(private val cfg: Config) {
    private val quality = FrameQuality(cfg.quality)
    private val gaze = GazeDetector(cfg)
    private val phone = PhoneDetector(cfg)
    private val yawning = YawningDetector(cfg)
    private val drowsiness = DrowsinessDetector(cfg, quality)
    var lastEventT = Double.NEGATIVE_INFINITY
        private set

    fun classifyFrame(f: FrameFeatures, thr: Thresholds): FrameStates {
        val st = FrameStates()
        st.frameGap = quality.checkGap(f.t)
        gaze.frameState(f, thr, st)
        phone.frameState(f, st)
        yawning.frameState(f, thr, st)
        drowsiness.frameState(f, thr, st, mouthGuard = yawning.eyeGuard(f.t, st))
        return st
    }

    fun update(f: FrameFeatures, st: FrameStates): BehaviorStatus {
        val t = f.t; val gap = st.frameGap
        val g = gaze.update(t, st, gap)
        val p = phone.update(t, st, gap)
        val y = yawning.update(t, st, gap)
        val d = drowsiness.update(t, st, gap, yawning.yawns)
        val events = d.events + y.events + g.events + p.events
        if (events.isNotEmpty()) lastEventT = t
        val active = listOf("eye_closure" to d.eyeClosure, "yawning" to y.yawning,
            "gaze_deviation" to g.gazeDeviation, "phone_usage" to p.phoneUsage).filter { it.second }.map { it.first }
        return BehaviorStatus(
            closureS = d.closureS, perclos = d.perclos, mouthOpenS = y.mouthOpenS, yawnsInWindow = y.yawnsInWindow,
            gazeAwayS = g.gazeAwayS, offroadRatio = g.offroadRatio, phoneS = p.phoneS, longClosures = d.longClosures,
            blinkRatePerMin = d.blinkRatePerMin, eyeLevel = d.eyeLevel, drowsinessState = d.state, drowsy = d.state != "alert",
            eyeClosure = d.eyeClosure, yawning = y.yawning, gazeDeviation = g.gazeDeviation, phoneUsage = p.phoneUsage,
            events = events, active = active, noFace = g.noFace, faceStatus = st.faceStatus,
            dataValid = f.facePresent && !gap && st.eyesNotJudged != "quality",
        )
    }

    fun anyEpisodeActive(): Boolean = drowsiness.active || yawning.active || gaze.active || phone.active
}
