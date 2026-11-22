package edu.dlsu.esca_3_2526_c3

import android.content.Context
import edu.dlsu.esca_3_2526_c3.dms.Config
import edu.dlsu.esca_3_2526_c3.monitor.ModelFiles
import java.util.Locale

/**
 * Plain-language list of every part of the detection model the phone will use (Settings > Models,
 * Details): the AI models of Stage 1 (built in / imported / off), the evidence weights of Stage 2-3,
 * calibration, the fuzzy risk model and the alert timing. Read from the same files MonitorSession loads.
 */
object ModelStatus {
    private fun src(context: Context, name: String) = when (ModelFiles.source(context, name)) {
        "imported" -> "imported"; "built in" -> "built in"; else -> "off"
    }

    /** One line per model, for the Details screen. */
    fun short(context: Context): String {
        val yolo = src(context, ModelFiles.YOLO)
        val yoloLabels = ModelFiles.yoloLabels(context)
        val cfg = config(context)
        val yoloRoles = yoloLabels?.mapNotNull { cfg.detector.roleOf(it) }?.toSet() ?: setOf("phone")
        return listOf(
            "Face landmarks + blendshapes (MediaPipe): built in",
            "YOLO ($yolo): " + if (yolo == "off") "phone detection off" else yoloRoles.sorted().joinToString(", ").replace('_', ' '),
            "Eye-state classifier: ${src(context, ModelFiles.EYE)}",
            "Fusion model: ${src(context, ModelFiles.FUSION)}",
            "Fuzzy risk model: 81 rules · settings ${if (src(context, ModelFiles.CONFIG) == "off") "defaults" else src(context, ModelFiles.CONFIG)}",
        ).joinToString("\n")
    }

    private fun config(context: Context): Config = try { ModelFiles.config(context) } catch (_: Exception) { Config() }

    /** Full description for Settings > Models. */
    fun describe(context: Context): String {
        val cfg = config(context)
        val ev = cfg.evidence
        val eyeOn = src(context, ModelFiles.EYE) != "off"
        val fusion = try { ModelFiles.fusionModel(context) } catch (_: Exception) { null }
        val fusionProblem = if (fusion == null && src(context, ModelFiles.FUSION) != "off") " (file unreadable)" else ""
        val yoloLabels = ModelFiles.yoloLabels(context)
        val eyeLabels = ModelFiles.eyeLabels(context)
        fun w(v: Double, on: Boolean = true) = if (!on) "off" else String.format(Locale.US, "%.2f", v)
        val yolo = src(context, ModelFiles.YOLO)
        return buildString {
            appendLine("STAGE 1 · AI MODELS")
            appendLine("• Face: MediaPipe Face Landmarker, built in (478 points, 52 blendshapes)")
            appendLine("• YOLO: $yolo" + when {
                yolo == "built in" && yoloLabels?.size == 80 -> " (YOLOv8n COCO: \"cell phone\")"
                yoloLabels != null -> " (${yoloLabels.size} classes: ${yoloLabels.take(6).joinToString(", ")})"
                else -> ""
            })
            appendLine("• Eye-state classifier: ${src(context, ModelFiles.EYE)}" +
                    (eyeLabels?.let { " (classes ${it.joinToString(", ")})" } ?: ""))
            appendLine("• Fusion model: ${src(context, ModelFiles.FUSION)}$fusionProblem" +
                    (fusion?.let { " (outputs ${it.headNames.sorted().joinToString(", ")}, ${it.features.size} inputs)" } ?: ""))
            appendLine()
            appendLine("STAGE 2-3 · EVIDENCE WEIGHTS (0 = source off)")
            appendLine("• Eyes closed: EAR ${w(ev.eyeEar)} · blendshape ${w(ev.eyeBlendshape)} · eye classifier ${w(ev.eyeCnn, eyeOn)} · " +
                    "YOLO ${w(ev.eyeYolo)} · fusion ${w(ev.eyeFusion, fusion != null)}")
            appendLine("• Yawning: MAR ${w(ev.mouthMar)} · blendshape ${w(ev.mouthBlendshape)} · YOLO ${w(ev.mouthYolo)} · " +
                    "fusion ${w(ev.mouthFusion, fusion != null)}")
            appendLine(String.format(Locale.US, "• Calibration: %.0f s, camera within %.0f°", cfg.calibration.durationS, cfg.calibration.maxCameraOffsetDeg))
            appendLine()
            appendLine("STAGE 4-5 · SEVERITY AND FUZZY RISK")
            appendLine(String.format(Locale.US, "• 81 fuzzy rules (fatigue 27, distraction 9, context 9, risk 36); tiers LOW %.1f, MEDIUM %.1f, HIGH %.1f; hysteresis %.0f",
                cfg.risk.tierBounds[0], cfg.risk.tierBounds[1], cfg.risk.tierBounds[2], cfg.risk.tierHysteresis))
            appendLine("• Settings: ${if (src(context, ModelFiles.CONFIG) == "off") "defaults" else "${src(context, ModelFiles.CONFIG)} dms_config.json"}")
            append("Built-in files are in the app (assets); imported files replace them until removed.")
        }
    }
}
