package edu.dlsu.esca_3_2526_c3.dms

import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.tanh

/*
 * Picture-based fusion model (dms/fusion in Python): a small trained network per
 * sign looks at all numbers of one picture together (EAR per eye, MAR, head
 * angles, the 52 blendshapes) and gives P(eyes closed) / P(yawning). The output
 * is one more evidence source in Stage 3 (evidence.eyeFusion / mouthFusion).
 * Model file: fusion_model.json from scripts/train_fusion.py (Settings > Import).
 * The forward pass uses the same loops and operation order as model.py, so the
 * phone and the laptop give the same numbers.
 */

object FusionInputs {
    val BLENDSHAPE_NAMES = listOf(
        "_neutral", "browDownLeft", "browDownRight", "browInnerUp", "browOuterUpLeft", "browOuterUpRight",
        "cheekPuff", "cheekSquintLeft", "cheekSquintRight", "eyeBlinkLeft", "eyeBlinkRight",
        "eyeLookDownLeft", "eyeLookDownRight", "eyeLookInLeft", "eyeLookInRight", "eyeLookOutLeft",
        "eyeLookOutRight", "eyeLookUpLeft", "eyeLookUpRight", "eyeSquintLeft", "eyeSquintRight",
        "eyeWideLeft", "eyeWideRight", "jawForward", "jawLeft", "jawOpen", "jawRight", "mouthClose",
        "mouthDimpleLeft", "mouthDimpleRight", "mouthFrownLeft", "mouthFrownRight", "mouthFunnel",
        "mouthLeft", "mouthLowerDownLeft", "mouthLowerDownRight", "mouthPressLeft", "mouthPressRight",
        "mouthPucker", "mouthRight", "mouthRollLower", "mouthRollUpper", "mouthShrugLower",
        "mouthShrugUpper", "mouthSmileLeft", "mouthSmileRight", "mouthStretchLeft", "mouthStretchRight",
        "mouthUpperUpLeft", "mouthUpperUpRight", "noseSneerLeft", "noseSneerRight",
    )

    /** All candidate inputs by name (null = not measured on this picture). */
    fun of(face: FaceObservation?, f: FrameFeatures): Map<String, Double?> {
        val values = linkedMapOf<String, Double?>(
            "ear_left" to f.earLeft, "ear_right" to f.earRight, "mar" to f.mar,
            "yaw" to f.yaw, "pitch" to f.pitch, "roll" to f.roll,
        )
        val blend = face?.blendshapes ?: emptyMap()
        for (name in BLENDSHAPE_NAMES) values["bs_$name"] = blend[name]
        return values
    }
}

class FusionModel private constructor(
    val features: List<String>,
    private val mean: DoubleArray,
    private val scale: DoubleArray,
    private val heads: Map<String, List<Layer>>,
    val info: JSONObject?,
) {
    class Layer(val weights: Array<DoubleArray>, val bias: DoubleArray, val activation: String)

    val headNames: Set<String> get() = heads.keys

    companion object {
        const val FORMAT = "dms-fusion-1"
        private val ACTIVATIONS = setOf("tanh", "relu", "sigmoid", "linear")

        fun fromJson(text: String): FusionModel {
            val o = JSONObject(text)
            require(o.optString("format") == FORMAT) { "not a fusion model file (format '${o.optString("format")}', expected '$FORMAT')" }
            val fa = o.getJSONArray("features")
            val features = List(fa.length()) { fa.getString(it) }
            val ma = o.getJSONArray("mean"); val sa = o.getJSONArray("scale")
            val n = features.size
            require(ma.length() == n && sa.length() == n) { "fusion model: mean / scale do not match the feature list" }
            val mean = DoubleArray(n) { ma.getDouble(it) }
            val scale = DoubleArray(n) { sa.getDouble(it) }
            require(scale.all { it > 0 }) { "fusion model: scale must be positive" }
            val heads = linkedMapOf<String, List<Layer>>()
            val ho = o.getJSONObject("heads")
            for (name in ho.keys()) {
                val la = ho.getJSONObject(name).getJSONArray("layers")
                var width = n
                val layers = mutableListOf<Layer>()
                for (i in 0 until la.length()) {
                    val l = la.getJSONObject(i)
                    val wa = l.getJSONArray("weights"); val ba = l.getJSONArray("bias")
                    val act = l.optString("activation", "linear")
                    require(act in ACTIVATIONS) { "fusion model head $name: unknown activation '$act'" }
                    require(wa.length() == ba.length()) { "fusion model head $name: layer $i has the wrong shape" }
                    val w = Array(wa.length()) { r ->
                        val row = wa.getJSONArray(r)
                        require(row.length() == width) { "fusion model head $name: layer $i has the wrong shape" }
                        DoubleArray(row.length()) { row.getDouble(it) }
                    }
                    layers += Layer(w, DoubleArray(ba.length()) { ba.getDouble(it) }, act)
                    width = ba.length()
                }
                require(width == 1) { "fusion model head $name: the last layer must have one output" }
                heads[name] = layers
            }
            return FusionModel(features, mean, scale, heads, o.optJSONObject("info"))
        }

        private fun activate(name: String, x: Double): Double = when (name) {
            "tanh" -> tanh(x)
            "relu" -> if (x > 0.0) x else 0.0
            "sigmoid" -> if (x >= 0.0) 1.0 / (1.0 + exp(-x)) else exp(x) / (1.0 + exp(x))
            else -> x
        }
    }

    fun standardise(values: Map<String, Double?>): DoubleArray? {
        val x = DoubleArray(features.size)
        for (i in features.indices) {
            val v = values[features[i]] ?: return null
            x[i] = (v - mean[i]) / scale[i]
        }
        return x
    }

    fun headOutput(name: String, x: DoubleArray): Double {
        var h = x
        for (layer in heads.getValue(name)) {
            val out = DoubleArray(layer.bias.size)
            for (j in layer.bias.indices) {
                var s = layer.bias[j]
                val row = layer.weights[j]
                for (i in row.indices) s += row[i] * h[i]
                out[j] = activate(layer.activation, s)
            }
            h = out
        }
        return h[0]
    }

    /** Probability per head (null for every head when an input is missing). */
    fun predict(values: Map<String, Double?>): Map<String, Double?> {
        val x = standardise(values)
        return heads.keys.associateWith { if (x == null) null else headOutput(it, x) }
    }
}
