package edu.dlsu.esca_3_2526_c3.monitor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import edu.dlsu.esca_3_2526_c3.dms.Detection
import edu.dlsu.esca_3_2526_c3.dms.ObjectDetectorConfig
import edu.dlsu.esca_3_2526_c3.dms.YoloDecoder
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Stage 1 AI: a YOLO .tflite model (YOLOv8 / YOLO11 / YOLO26 exported with
 * scripts/export_models.py). The COCO model finds phones; your trained driver-state
 * model also finds open / closed eyes and yawns. Optional: without a model the app
 * still runs, with phone detection off.
 */
class YoloAi(model: ByteBuffer, names: List<String>?, private val cfg: ObjectDetectorConfig) : AutoCloseable {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
    private val inTensor = interpreter.getInputTensor(0)
    private val outTensor = interpreter.getOutputTensor(0)
    val inputSize: Int = inTensor.shape()[1] // [1, H, W, 3], square
    private val outShape: IntArray = outTensor.shape()
    val classNames: List<String>
    val roles: Set<String>

    init {
        val endToEnd = outShape.size == 3 && outShape[2] == 6 && outShape[1] <= 1000
        val nc = if (endToEnd) (names?.size ?: 80) else minOf(outShape[1], outShape[2]) - 4
        classNames = if (names != null && names.size >= nc) names else YoloDecoder.defaultNames(nc)
        roles = classNames.mapNotNull { cfg.roleOf(it) }.toSet()
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val size = inputSize
        val lb = YoloDecoder.Letterbox.of(bitmap.width, bitmap.height, size)
        val boxed = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(boxed)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val dst = RectF(lb.padX.toFloat(), lb.padY.toFloat(),
            (lb.padX + bitmap.width * lb.scale).toFloat(), (lb.padY + bitmap.height * lb.scale).toFloat())
        canvas.drawBitmap(bitmap, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(size * size)
        boxed.getPixels(pixels, 0, size, 0, 0, size, size)
        boxed.recycle()

        val input: ByteBuffer
        when (inTensor.dataType()) {
            DataType.FLOAT32 -> {
                input = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
                for (p in pixels) {
                    input.putFloat(((p shr 16) and 0xFF) / 255f)
                    input.putFloat(((p shr 8) and 0xFF) / 255f)
                    input.putFloat((p and 0xFF) / 255f)
                }
            }
            DataType.UINT8, DataType.INT8 -> {
                val q = inTensor.quantizationParams()
                val signed = inTensor.dataType() == DataType.INT8
                input = ByteBuffer.allocateDirect(size * size * 3).order(ByteOrder.nativeOrder())
                fun put(v: Int) {
                    val real = v / 255f
                    val scale = if (q.scale == 0f) 1f / 255f else q.scale
                    val qv = (real / scale + q.zeroPoint).roundToInt().coerceIn(if (signed) -128 else 0, if (signed) 127 else 255)
                    input.put(qv.toByte())
                }
                for (p in pixels) { put((p shr 16) and 0xFF); put((p shr 8) and 0xFF); put(p and 0xFF) }
            }
            else -> throw IllegalStateException("unsupported YOLO input type ${inTensor.dataType()}")
        }
        input.rewind()

        val output = ByteBuffer.allocateDirect(outTensor.numBytes()).order(ByteOrder.nativeOrder())
        interpreter.run(input, output)
        output.rewind()
        val n = outShape.fold(1) { a, b -> a * b }
        val values = FloatArray(n)
        when (outTensor.dataType()) {
            DataType.FLOAT32 -> output.asFloatBuffer().get(values)
            DataType.UINT8, DataType.INT8 -> {
                val q = outTensor.quantizationParams()
                val signed = outTensor.dataType() == DataType.INT8
                for (i in 0 until n) {
                    val raw = output.get(i).toInt()
                    val v = if (signed) raw else raw and 0xFF
                    values[i] = (v - q.zeroPoint) * q.scale
                }
            }
            else -> throw IllegalStateException("unsupported YOLO output type ${outTensor.dataType()}")
        }
        val minConf = minOf(cfg.conf, cfg.phoneStayConf) // phone episodes continue down to phone_stay_conf
        return YoloDecoder.decode(values, outShape, classNames, lb, minConf, cfg.iou) { cfg.roleOf(it) }
    }

    override fun close() = interpreter.close()
}
