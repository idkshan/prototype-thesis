package edu.dlsu.esca_3_2526_c3.monitor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import edu.dlsu.esca_3_2526_c3.dms.EyeCrop
import edu.dlsu.esca_3_2526_c3.dms.EyeModelConfig
import edu.dlsu.esca_3_2526_c3.dms.FaceObservation
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stage 1 AI (optional): the learned eye-state classifier of the desktop (dms/perception/eye_state.py),
 * e.g. yolo11n-cls trained on the MRL Eye Dataset and exported with scripts/export_models.py.
 * Both eyes are cropped around the face landmarks (EyeCrop, identical to the desktop), turned grey
 * (MRL images are infrared), classified, and the mean P(closed) becomes the "cnn" eye evidence.
 */
class EyeAi(model: ByteBuffer, names: List<String>?, private val cfg: EyeModelConfig) : AutoCloseable {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    private val inTensor = interpreter.getInputTensor(0)
    private val outTensor = interpreter.getOutputTensor(0)
    private val shape = inTensor.shape()
    private val nchw = shape.size == 4 && shape[1] == 3 && shape[3] != 3
    val inputSize: Int = if (nchw) shape[2] else shape[1]
    val classNames: List<String>
    val closedIndex: Int

    init {
        require(shape.size == 4) { "not an image classifier (input ${shape.toList()})" }
        require(inTensor.dataType() == DataType.FLOAT32) { "use a float32 / fp16 export (input is ${inTensor.dataType()})" }
        val nc = outTensor.shape().last()
        require(outTensor.shape().size == 2 && nc in 2..100) { "not a classifier output (${outTensor.shape().toList()})" }
        // Without a class-name file: Ultralytics sorts the folder names, so MRL (open / closed) gives closed, open.
        classNames = if (names != null && names.size >= nc) names.take(nc) else listOf("closed", "open") + (2 until nc).map { "class$it" }
        closedIndex = EyeCrop.closedIndex(classNames, cfg.closedClassNames)
            ?: throw IllegalArgumentException("class names $classNames contain none of ${cfg.closedClassNames}")
    }

    private val grey = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // cv2 BGR2GRAY weights
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f)))
    }
    private val plain = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Mean probability that the eyes are closed, or null if no usable eye crop. */
    fun predict(frame: Bitmap, face: FaceObservation): Double? {
        val boxes = EyeCrop.boxes(face.landmarks, cfg.cropScale, frame.width, frame.height)
        if (boxes.isEmpty()) return null
        val probs = boxes.map { classify(frame, it) }
        return probs.average()
    }

    private fun classify(frame: Bitmap, b: IntArray): Double {
        val size = inputSize
        // classify_transforms: shorter side -> size, centred square crop
        val sq = EyeCrop.centreSquare(b[2] - b[0], b[3] - b[1])
        var source = Bitmap.createBitmap(frame, b[0] + sq[0], b[1] + sq[1], sq[2], sq[3])
        // Large crops: halve step by step first (close to the antialiased resize of Ultralytics / PIL).
        while (source.width >= 2 * size) {
            val half = Bitmap.createScaledBitmap(source, source.width / 2, source.height / 2, true)
            if (source !== frame) source.recycle()
            source = half
        }
        val img = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(img).drawBitmap(source, Rect(0, 0, source.width, source.height), Rect(0, 0, size, size),
            if (cfg.grayscale) grey else plain)
        if (source !== frame) source.recycle()
        val pixels = IntArray(size * size)
        img.getPixels(pixels, 0, size, 0, 0, size, size)
        img.recycle()
        val input = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
        if (nchw) {
            for (shift in intArrayOf(16, 8, 0)) for (p in pixels) input.putFloat(((p shr shift) and 0xFF) / 255f)
        } else {
            for (p in pixels) {
                input.putFloat(((p shr 16) and 0xFF) / 255f)
                input.putFloat(((p shr 8) and 0xFF) / 255f)
                input.putFloat((p and 0xFF) / 255f)
            }
        }
        input.rewind()
        val out = Array(1) { FloatArray(outTensor.shape().last()) }
        interpreter.run(input, out)
        return EyeCrop.probabilities(out[0])[closedIndex]
    }

    override fun close() = interpreter.close()
}
