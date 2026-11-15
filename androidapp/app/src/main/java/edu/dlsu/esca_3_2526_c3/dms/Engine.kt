package edu.dlsu.esca_3_2526_c3.dms

/*
 * Stages 3-5 for one frame (Kotlin port of dms/engine.py):
 *   rules (what is the driver doing?) -> severity -> calibration / adaptation
 *   -> fuzzy risk -> alert state machine.
 */

class EngineOutput(
    val states: FrameStates,
    val behavior: BehaviorStatus,
    val severity: SeverityReport,
    val risk: RiskAssessment,
    val alerts: List<AlertCommand>,
    val activeTier: String,
    val calibrationStatus: String,
    val context: ContextReading,
    val alertState: String,
)

class DecisionEngine(val cfg: Config, alertIdPrefix: String? = null) {
    val calibrator = DriverCalibrator(cfg.calibration, cfg.thresholds, phoneConfLimit = cfg.detector.conf)
    val classifier = BehaviorClassifier(cfg)
    private val grader = SeverityGrader(cfg.severity, cfg.drowsiness.stateScores)
    private val riskModel = DriverRiskModel(cfg)
    val alerts = AlertStateMachine(cfg.alerts, alertIdPrefix)

    fun recalibrate() = calibrator.restart()

    fun step(f: FrameFeatures, ctx: ContextReading): EngineOutput {
        val thr = calibrator.thresholds
        val states = classifier.classifyFrame(f, thr)
        val behavior = classifier.update(f, states)
        val severity = grader.grade(behavior)

        when {
            calibrator.status == CALIBRATING -> calibrator.collect(f)
            confidentlyAlert(f, states, severity) -> calibrator.adapt(f)
            else -> calibrator.pauseAdaptation()
        }

        val risk = riskModel.assess(severity, ctx)
        if ((risk.inputs["motion"] ?: 0.0) >= cfg.context.suddenMotionScore) behavior.active = behavior.active + "sudden_motion"
        val alertCmds = alerts.update(f.t, risk.tier, behavior.active, behavior.events, reason(severity, risk))
        return EngineOutput(states, behavior, severity, risk, alertCmds, alerts.activeTier, calibrator.status, ctx, alerts.state)
    }

    /** Call when no frame arrived for alerts.cameraTimeoutS. */
    fun cameraLost(t: Double, reason: String = "no camera frames") = alerts.cameraLost(t, reason)

    private fun confidentlyAlert(f: FrameFeatures, s: FrameStates, sev: SeverityReport) =
        f.facePresent && !s.phoneVisible &&
            (s.eyeEvidence ?: 0.0) < 0.3 && (s.mouthEvidence ?: 0.0) < 0.3 && (s.poseEvidence ?: 0.0) < 0.25 &&
            sev.maxScore() < 0.1 && !classifier.anyEpisodeActive() &&
            f.t - classifier.lastEventT >= cfg.calibration.adaptQuietS

    private fun reason(sev: SeverityReport, risk: RiskAssessment): String {
        val parts = sev.asMap().filter { it.value.level != "NONE" }.map { (name, s) -> "$name=${s.level}(${s.reason})" }
        val ctx = "F=%.2f D=%.2f C=%.2f R=%.0f".format(risk.fatigue, risk.distraction, risk.context, risk.risk)
        return (if (parts.isNotEmpty()) parts.joinToString(", ") + " | " else "") + ctx
    }
}
