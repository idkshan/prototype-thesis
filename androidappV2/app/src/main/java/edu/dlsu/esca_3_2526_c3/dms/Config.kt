package edu.dlsu.esca_3_2526_c3.dms

import org.json.JSONArray
import org.json.JSONObject

/*
 * Every tunable number of the detection pipeline. Kotlin port of dms/config.py:
 * the defaults are the same values as configs/default.yaml. A tuned config
 * (configs/tuned.yaml) is brought to the phone as JSON with
 *     python scripts/export_android_config.py --config configs/tuned.yaml
 * and loaded with Config.fromJson() (only the keys present are changed).
 *
 * Piecewise-linear "anchor" lists are [[x0, y0], [x1, y1], ...], evaluated by
 * linear interpolation and clamped to the first/last y outside the range.
 */

typealias Anchors = List<DoubleArray>

fun anchors(vararg xy: Double): Anchors = xy.toList().chunked(2).map { doubleArrayOf(it[0], it[1]) }

class FaceModelConfig {
    var modelPath = "face_landmarker.task"
    var minDetectionConfidence = 0.5
    var minPresenceConfidence = 0.5
    var minTrackingConfidence = 0.5
}

class ObjectDetectorConfig {
    var modelPath = "yolo.tflite"
    var imgsz = 640
    var conf = 0.35
    var phoneStayConf = 0.20
    var iou = 0.5
    var everyNFrames = 1
    var phoneClassNames = listOf("cell phone", "phone", "mobile phone", "cellphone", "mobile_phone")
    var eyeOpenClassNames = listOf("eye_open", "open_eye", "open", "eyes_open")
    var eyeClosedClassNames = listOf("eye_closed", "closed_eye", "closed", "eyes_closed")
    var yawnClassNames = listOf("yawn", "yawning")
    var noYawnClassNames = listOf("no_yawn", "mouth_closed", "not_yawning")
    var facePartMargin = 0.15
    var roiWidthFactor = 2.5
    var roiAboveFactor = 0.5

    /** Class name -> role (phone, eye_open, eye_closed, yawn, no_yawn), case-insensitive. */
    fun roleOf(label: String): String? {
        val l = label.lowercase()
        return when {
            phoneClassNames.any { it.lowercase() == l } -> "phone"
            eyeOpenClassNames.any { it.lowercase() == l } -> "eye_open"
            eyeClosedClassNames.any { it.lowercase() == l } -> "eye_closed"
            yawnClassNames.any { it.lowercase() == l } -> "yawn"
            noYawnClassNames.any { it.lowercase() == l } -> "no_yawn"
            else -> null
        }
    }
}

/** Optional eye-state classifier (eye_model in the YAML; the phone imports the .tflite in Settings). */
class EyeModelConfig {
    var closedClassNames = listOf("closed", "close", "closed_eye", "closed_eyes")
    var cropScale = 1.8
    var grayscale = true // MRL Eye images are infrared / greyscale
}

class EvidenceWeights {
    var eyeEar = 0.5
    var eyeBlendshape = 0.5
    var eyeCnn = 0.5
    var eyeYolo = 0.5
    var eyeFusion = 0.5 // only used when a fusion model is loaded
    var mouthMar = 0.5
    var mouthBlendshape = 0.5
    var mouthYolo = 0.5
    var mouthFusion = 0.5
}

class ThresholdConfig {
    var earClosedRatio = 0.70
    var blinkClosedDelta = 0.35
    var marOpenDelta = 0.40
    var jawOpenDelta = 0.35
    var yawDeg = 30.0
    var pitchDownDeg = 20.0
    var pitchUpDeg = 25.0
    var decisionLevel = 0.5
    var uncalibratedPoseScale = 1.5
}

class CalibrationDefaults {
    var ear = 0.28
    var mar = 0.05
    var yaw = 0.0
    var pitch = 0.0
    var blink = 0.15
    var jaw = 0.03
}

class CalibrationConfig {
    var enabled = true
    var durationS = 5.0
    var minSamples = 20
    var maxPoseStdDeg = 5.0
    var minEar = 0.18
    var maxMar = 0.25
    var maxBlink = 0.5
    var maxCameraOffsetDeg = 60.0
    var adapt = true
    var adaptTauS = 120.0
    var adaptQuietS = 10.0
    var maxEarDrift = 0.20
    var maxMarDrift = 0.10
    var maxPoseDriftDeg = 10.0
    val defaults = CalibrationDefaults()
}

class TemporalConfig {
    var closureGapS = 0.15
    var closureEventS = 0.5
    var yawnMinS = 1.5
    var yawnGapS = 0.3
    var gazeMinS = 2.0
    var gazeGapS = 0.3
    var phoneConfirmS = 3.0
    var phoneGapS = 1.0
    var offroadWindowS = 30.0
    var yawnWindowS = 300.0
    var closureHoldS = 3.0
    var gazeHoldS = 1.0
    var phoneHoldS = 1.0
    var faceLossAsGazeAway = true
    var faceLossMaxS = 10.0
    var suspendEyesWhenPhone = true
    var yawnEyeGuardLevel = 0.3
    var yawnEyeGuardS = 0.5
}

class QualityConfig {
    var maxFrameGapS = 1.0
    var minEyeWidthPx = 12.0
    var faceLossTurningEvidence = 0.3
    var faceLossLookbackS = 0.5
    var lookDownEarGuard = 1.0
}

class DrowsinessConfig {
    var windowS = 60.0
    var perclosMinCoverageS = 30.0
    var slightPerclos = 0.08
    var moderatePerclos = 0.15
    var slightClosureS = 0.8
    var moderateClosureS = 1.5
    var slightLongClosures = 2
    var moderateLongClosures = 4
    var slightYawns = 2
    var yawnHoldS = 60.0
    var moderateYawns = 0
    var yawnsPlusEyesModerate = true
    var blinkRateSlightPerMin = 0.0
    var recoveryS = 20.0
    var stateScores = listOf(0.0, 0.45, 0.75)
}

class SeverityConfig {
    var closureS = anchors(0.0, 0.0, 0.4, 0.0, 0.8, 0.34, 1.5, 0.66, 2.0, 0.9, 3.0, 1.0)
    var yawnCount = anchors(0.0, 0.0, 1.0, 0.4, 2.0, 0.5, 3.0, 0.7, 4.0, 0.9)
    var gazeS = anchors(0.0, 0.0, 1.0, 0.0, 2.0, 0.34, 3.0, 0.66, 4.0, 0.9, 5.0, 1.0)
    var offroadRatio = anchors(0.0, 0.0, 0.1, 0.0, 0.25, 0.34, 0.4, 0.66, 0.6, 1.0)
    var phoneS = anchors(0.0, 0.0, 1.0, 0.0, 2.99, 0.3, 3.0, 0.7, 5.0, 1.0)
}

class ContextConfig {
    var speedKmh = anchors(0.0, 0.0, 5.0, 0.1, 10.0, 0.34, 20.0, 0.5, 60.0, 0.5, 80.0, 0.66, 100.0, 0.9, 120.0, 1.0)
    var motionG = anchors(0.0, 0.0, 0.1, 0.0, 0.25, 0.34, 0.45, 0.66, 0.6, 1.0)
    var drivingHours = anchors(0.0, 0.0, 1.0, 0.34, 2.0, 0.66, 3.0, 1.0)
    var assumedSpeedKmh = 40.0
    var gpsStaleS = 3.0
    var movingSpeedKmh = 5.0
    var breakResetS = 900.0
    var suddenMotionScore = 0.65
}

class RiskConfig {
    var tierBounds = listOf(25.0, 52.5, 77.5)
    var tierHysteresis = 5.0
    var resolution = 201
}

class AlertConfig {
    var activateS = 0.3
    var clearS = 2.0
    var minAlertS = 2.0
    var releaseS = 3.0
    var minIntervalS = 5.0
    var mediumRepeatS = 2.0
    var mediumMaxRepeats = 3
    var highRepeatS = 1.0
    var highMaxRepeats = 0
    var pushIntervalS = 60.0
    var reminderS = 0.0
    var escalateClosureS = 0.0
    var keepaliveS = 1.0
    var cameraTimeoutS = 3.0
}

class Config {
    val face = FaceModelConfig()
    val detector = ObjectDetectorConfig()
    val eyeModel = EyeModelConfig()
    val evidence = EvidenceWeights()
    val thresholds = ThresholdConfig()
    val calibration = CalibrationConfig()
    val temporal = TemporalConfig()
    val quality = QualityConfig()
    val drowsiness = DrowsinessConfig()
    val severity = SeverityConfig()
    val context = ContextConfig()
    val risk = RiskConfig()
    val alerts = AlertConfig()

    companion object {
        /** Defaults overridden by the keys present in a Config.to_dict() JSON (snake_case, as in the YAML files). */
        fun fromJson(text: String): Config = Config().apply { applyJson(JSONObject(text)) }
    }

    fun applyJson(root: JSONObject) {
        // model paths are not taken from the desktop config: the phone uses its own asset files
        root.optJSONObject("face")?.let { o ->
            o.num("min_detection_confidence") { face.minDetectionConfidence = it }
            o.num("min_presence_confidence") { face.minPresenceConfidence = it }
            o.num("min_tracking_confidence") { face.minTrackingConfidence = it }
        }
        root.optJSONObject("detector")?.let { o ->
            o.int("imgsz") { detector.imgsz = it }
            o.num("conf") { detector.conf = it }
            o.num("phone_stay_conf") { detector.phoneStayConf = it }
            o.num("iou") { detector.iou = it }
            o.int("every_n_frames") { detector.everyNFrames = it }
            o.strings("phone_class_names") { detector.phoneClassNames = it }
            o.strings("eye_open_class_names") { detector.eyeOpenClassNames = it }
            o.strings("eye_closed_class_names") { detector.eyeClosedClassNames = it }
            o.strings("yawn_class_names") { detector.yawnClassNames = it }
            o.strings("no_yawn_class_names") { detector.noYawnClassNames = it }
            o.num("face_part_margin") { detector.facePartMargin = it }
            o.num("roi_width_factor") { detector.roiWidthFactor = it }
            o.num("roi_above_factor") { detector.roiAboveFactor = it }
        }
        root.optJSONObject("eye_model")?.let { o ->
            o.strings("closed_class_names") { eyeModel.closedClassNames = it }
            o.num("crop_scale") { eyeModel.cropScale = it }
            o.bool("grayscale") { eyeModel.grayscale = it }
        }
        root.optJSONObject("evidence")?.let { o ->
            o.num("eye_ear") { evidence.eyeEar = it }
            o.num("eye_blendshape") { evidence.eyeBlendshape = it }
            o.num("eye_cnn") { evidence.eyeCnn = it }
            o.num("eye_yolo") { evidence.eyeYolo = it }
            o.num("eye_fusion") { evidence.eyeFusion = it }
            o.num("mouth_mar") { evidence.mouthMar = it }
            o.num("mouth_blendshape") { evidence.mouthBlendshape = it }
            o.num("mouth_yolo") { evidence.mouthYolo = it }
            o.num("mouth_fusion") { evidence.mouthFusion = it }
        }
        root.optJSONObject("thresholds")?.let { o ->
            o.num("ear_closed_ratio") { thresholds.earClosedRatio = it }
            o.num("blink_closed_delta") { thresholds.blinkClosedDelta = it }
            o.num("mar_open_delta") { thresholds.marOpenDelta = it }
            o.num("jaw_open_delta") { thresholds.jawOpenDelta = it }
            o.num("yaw_deg") { thresholds.yawDeg = it }
            o.num("pitch_down_deg") { thresholds.pitchDownDeg = it }
            o.num("pitch_up_deg") { thresholds.pitchUpDeg = it }
            o.num("decision_level") { thresholds.decisionLevel = it }
            o.num("uncalibrated_pose_scale") { thresholds.uncalibratedPoseScale = it }
        }
        root.optJSONObject("calibration")?.let { o ->
            o.bool("enabled") { calibration.enabled = it }
            o.num("duration_s") { calibration.durationS = it }
            o.int("min_samples") { calibration.minSamples = it }
            o.num("max_pose_std_deg") { calibration.maxPoseStdDeg = it }
            o.num("min_ear") { calibration.minEar = it }
            o.num("max_mar") { calibration.maxMar = it }
            o.num("max_blink") { calibration.maxBlink = it }
            o.num("max_camera_offset_deg") { calibration.maxCameraOffsetDeg = it }
            o.bool("adapt") { calibration.adapt = it }
            o.num("adapt_tau_s") { calibration.adaptTauS = it }
            o.num("adapt_quiet_s") { calibration.adaptQuietS = it }
            o.num("max_ear_drift") { calibration.maxEarDrift = it }
            o.num("max_mar_drift") { calibration.maxMarDrift = it }
            o.num("max_pose_drift_deg") { calibration.maxPoseDriftDeg = it }
            o.optJSONObject("defaults")?.let { d ->
                val c = calibration.defaults
                d.num("ear") { c.ear = it }; d.num("mar") { c.mar = it }; d.num("yaw") { c.yaw = it }
                d.num("pitch") { c.pitch = it }; d.num("blink") { c.blink = it }; d.num("jaw") { c.jaw = it }
            }
        }
        root.optJSONObject("temporal")?.let { o ->
            o.num("closure_gap_s") { temporal.closureGapS = it }
            o.num("closure_event_s") { temporal.closureEventS = it }
            o.num("yawn_min_s") { temporal.yawnMinS = it }
            o.num("yawn_gap_s") { temporal.yawnGapS = it }
            o.num("gaze_min_s") { temporal.gazeMinS = it }
            o.num("gaze_gap_s") { temporal.gazeGapS = it }
            o.num("phone_confirm_s") { temporal.phoneConfirmS = it }
            o.num("phone_gap_s") { temporal.phoneGapS = it }
            o.num("offroad_window_s") { temporal.offroadWindowS = it }
            o.num("yawn_window_s") { temporal.yawnWindowS = it }
            o.num("closure_hold_s") { temporal.closureHoldS = it }
            o.num("gaze_hold_s") { temporal.gazeHoldS = it }
            o.num("phone_hold_s") { temporal.phoneHoldS = it }
            o.bool("face_loss_as_gaze_away") { temporal.faceLossAsGazeAway = it }
            o.num("face_loss_max_s") { temporal.faceLossMaxS = it }
            o.bool("suspend_eyes_when_phone") { temporal.suspendEyesWhenPhone = it }
            o.num("yawn_eye_guard_level") { temporal.yawnEyeGuardLevel = it }
            o.num("yawn_eye_guard_s") { temporal.yawnEyeGuardS = it }
        }
        root.optJSONObject("quality")?.let { o ->
            o.num("max_frame_gap_s") { quality.maxFrameGapS = it }
            o.num("min_eye_width_px") { quality.minEyeWidthPx = it }
            o.num("face_loss_turning_evidence") { quality.faceLossTurningEvidence = it }
            o.num("face_loss_lookback_s") { quality.faceLossLookbackS = it }
            o.num("look_down_ear_guard") { quality.lookDownEarGuard = it }
        }
        root.optJSONObject("drowsiness")?.let { o ->
            o.num("window_s") { drowsiness.windowS = it }
            o.num("perclos_min_coverage_s") { drowsiness.perclosMinCoverageS = it }
            o.num("slight_perclos") { drowsiness.slightPerclos = it }
            o.num("moderate_perclos") { drowsiness.moderatePerclos = it }
            o.num("slight_closure_s") { drowsiness.slightClosureS = it }
            o.num("moderate_closure_s") { drowsiness.moderateClosureS = it }
            o.int("slight_long_closures") { drowsiness.slightLongClosures = it }
            o.int("moderate_long_closures") { drowsiness.moderateLongClosures = it }
            o.int("slight_yawns") { drowsiness.slightYawns = it }
            o.num("yawn_hold_s") { drowsiness.yawnHoldS = it }
            o.int("moderate_yawns") { drowsiness.moderateYawns = it }
            o.bool("yawns_plus_eyes_moderate") { drowsiness.yawnsPlusEyesModerate = it }
            o.num("blink_rate_slight_per_min") { drowsiness.blinkRateSlightPerMin = it }
            o.num("recovery_s") { drowsiness.recoveryS = it }
            o.nums("state_scores") { drowsiness.stateScores = it }
        }
        root.optJSONObject("severity")?.let { o ->
            o.anchors("closure_s") { severity.closureS = it }
            o.anchors("yawn_count") { severity.yawnCount = it }
            o.anchors("gaze_s") { severity.gazeS = it }
            o.anchors("offroad_ratio") { severity.offroadRatio = it }
            o.anchors("phone_s") { severity.phoneS = it }
        }
        root.optJSONObject("context")?.let { o ->
            o.anchors("speed_kmh") { context.speedKmh = it }
            o.anchors("motion_g") { context.motionG = it }
            o.anchors("driving_hours") { context.drivingHours = it }
            o.num("assumed_speed_kmh") { context.assumedSpeedKmh = it }
            o.num("gps_stale_s") { context.gpsStaleS = it }
            o.num("moving_speed_kmh") { context.movingSpeedKmh = it }
            o.num("break_reset_s") { context.breakResetS = it }
            o.num("sudden_motion_score") { context.suddenMotionScore = it }
        }
        root.optJSONObject("risk")?.let { o ->
            o.nums("tier_bounds") { risk.tierBounds = it }
            o.num("tier_hysteresis") { risk.tierHysteresis = it }
            o.int("resolution") { risk.resolution = it }
        }
        root.optJSONObject("alerts")?.let { o ->
            o.num("activate_s") { alerts.activateS = it }
            o.num("clear_s") { alerts.clearS = it }
            o.num("min_alert_s") { alerts.minAlertS = it }
            o.num("release_s") { alerts.releaseS = it }
            o.num("min_interval_s") { alerts.minIntervalS = it }
            o.num("medium_repeat_s") { alerts.mediumRepeatS = it }
            o.int("medium_max_repeats") { alerts.mediumMaxRepeats = it }
            o.num("high_repeat_s") { alerts.highRepeatS = it }
            o.int("high_max_repeats") { alerts.highMaxRepeats = it }
            o.num("push_interval_s") { alerts.pushIntervalS = it }
            o.num("reminder_s") { alerts.reminderS = it }
            o.num("escalate_closure_s") { alerts.escalateClosureS = it }
            o.num("keepalive_s") { alerts.keepaliveS = it }
            o.num("camera_timeout_s") { alerts.cameraTimeoutS = it }
        }
    }
}

private inline fun JSONObject.num(key: String, set: (Double) -> Unit) { if (has(key) && !isNull(key)) set(getDouble(key)) }
private inline fun JSONObject.int(key: String, set: (Int) -> Unit) { if (has(key) && !isNull(key)) set(getInt(key)) }
private inline fun JSONObject.bool(key: String, set: (Boolean) -> Unit) { if (has(key) && !isNull(key)) set(getBoolean(key)) }
private inline fun JSONObject.strings(key: String, set: (List<String>) -> Unit) {
    optJSONArray(key)?.let { a -> set((0 until a.length()).map { a.getString(it) }) }
}
private inline fun JSONObject.nums(key: String, set: (List<Double>) -> Unit) {
    optJSONArray(key)?.let { a -> set((0 until a.length()).map { a.getDouble(it) }) }
}
private inline fun JSONObject.anchors(key: String, set: (Anchors) -> Unit) {
    optJSONArray(key)?.let { a: JSONArray ->
        set((0 until a.length()).map { i -> a.getJSONArray(i).let { p -> doubleArrayOf(p.getDouble(0), p.getDouble(1)) } })
    }
}
