package edu.dlsu.esca_3_2526_c3.dms

/**
 * Turns the raw output of an Ultralytics YOLO .tflite model into boxes in the
 * camera picture. Handles the usual export layouts:
 *   [1, 4 + classes, anchors]  YOLOv8 / YOLO11 (boxes as centre x, y, width, height)
 *   [1, anchors, 4 + classes]  the same, transposed
 *   [1, N, 6]                  end-to-end models (YOLO26, YOLOv10): x1, y1, x2, y2, score, class
 * Coordinates may be normalised (0..1) or in input pixels; both are recognised.
 * The picture is letterboxed into the square model input (gray padding), and
 * boxes are mapped back to picture pixels.
 */
object YoloDecoder {
    val COCO = listOf("person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
        "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear",
        "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup", "fork",
        "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
        "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone",
        "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush")
    // build_driver_state_dataset.py: alphabetical class order
    val DRIVER_STATE = listOf("eye_closed", "eye_open", "phone", "yawn")

    fun defaultNames(classes: Int): List<String> = when (classes) {
        80 -> COCO
        4 -> DRIVER_STATE
        else -> List(classes) { "class_$it" }
    }

    /** Letterbox geometry: picture (w, h) scaled by `scale` and shifted by (padX, padY) into the input square. */
    class Letterbox(val scale: Double, val padX: Double, val padY: Double, val inputSize: Int) {
        companion object {
            fun of(w: Int, h: Int, inputSize: Int): Letterbox {
                val scale = minOf(inputSize.toDouble() / w, inputSize.toDouble() / h)
                return Letterbox(scale, (inputSize - w * scale) / 2.0, (inputSize - h * scale) / 2.0, inputSize)
            }
        }

        fun toPicture(x1: Double, y1: Double, x2: Double, y2: Double) =
            doubleArrayOf((x1 - padX) / scale, (y1 - padY) / scale, (x2 - padX) / scale, (y2 - padY) / scale)
    }

    private class Raw(val box: DoubleArray, val score: Double, val cls: Int)

    /**
     * @param out   flattened output tensor
     * @param shape output tensor shape, e.g. [1, 84, 8400]
     */
    fun decode(out: FloatArray, shape: IntArray, names: List<String>, lb: Letterbox, minConf: Double, iou: Double,
               roleOf: (String) -> String?): List<Detection> {
        require(shape.size == 3) { "unexpected YOLO output shape ${shape.contentToString()}" }
        val a = shape[1]; val b = shape[2]
        val raw = ArrayList<Raw>()
        val endToEnd = b == 6 && a in 1..1000 // [1, max_det, 6]; a transposed 2-class model has thousands of anchors
        if (endToEnd) {
            for (i in 0 until a) {
                val o = i * 6
                val score = out[o + 4].toDouble()
                if (score < minConf) continue
                raw.add(Raw(doubleArrayOf(out[o].toDouble(), out[o + 1].toDouble(), out[o + 2].toDouble(), out[o + 3].toDouble()), score, out[o + 5].toInt()))
            }
        } else {
            val channelsFirst = when { // [1, 4+nc, anchors] or [1, anchors, 4+nc]
                a == 4 + names.size -> true
                b == 4 + names.size -> false
                else -> a < b
            }
            val ch = if (channelsFirst) a else b
            val anchors = if (channelsFirst) b else a
            val nc = ch - 4
            fun v(c: Int, j: Int) = (if (channelsFirst) out[c * anchors + j] else out[j * ch + c]).toDouble()
            for (j in 0 until anchors) {
                var best = -1.0; var cls = -1
                for (k in 0 until nc) {
                    val s = v(4 + k, j)
                    if (s > best) { best = s; cls = k }
                }
                if (best < minConf) continue
                val cx = v(0, j); val cy = v(1, j); val w = v(2, j); val h = v(3, j)
                raw.add(Raw(doubleArrayOf(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), best, cls))
            }
        }
        // normalised (0..1) coordinates -> input pixels
        val normalised = raw.isNotEmpty() && raw.all { r -> r.box.all { it <= 2.0 } }
        val s = if (normalised) lb.inputSize.toDouble() else 1.0
        val kept = if (endToEnd) raw else nms(raw, iou)
        return kept.map { r ->
            val name = names.getOrElse(r.cls) { "class_${r.cls}" }
            val box = lb.toPicture(r.box[0] * s, r.box[1] * s, r.box[2] * s, r.box[3] * s)
            Detection(name, r.score, box, roleOf(name))
        }
    }

    private fun iouOf(p: DoubleArray, q: DoubleArray): Double {
        val ix = maxOf(0.0, minOf(p[2], q[2]) - maxOf(p[0], q[0]))
        val iy = maxOf(0.0, minOf(p[3], q[3]) - maxOf(p[1], q[1]))
        val inter = ix * iy
        val union = (p[2] - p[0]) * (p[3] - p[1]) + (q[2] - q[0]) * (q[3] - q[1]) - inter
        return if (union <= 0) 0.0 else inter / union
    }

    /** Class-aware non-maximum suppression (as Ultralytics' default). */
    private fun nms(raw: List<Raw>, iou: Double): List<Raw> {
        val kept = ArrayList<Raw>()
        for (r in raw.sortedByDescending { it.score }) {
            if (kept.none { it.cls == r.cls && iouOf(it.box, r.box) > iou }) kept.add(r)
            if (kept.size >= 300) break
        }
        return kept
    }
}
