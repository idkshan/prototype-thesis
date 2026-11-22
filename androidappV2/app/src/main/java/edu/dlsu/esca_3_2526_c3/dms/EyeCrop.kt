package edu.dlsu.esca_3_2526_c3.dms

/**
 * Eye crops for the optional eye-state classifier, exactly as dms/perception/eye_state.py:
 * a square around each eye's 16 contour landmarks, side = max(width, height) x crop_scale,
 * clipped to the picture. Pure Kotlin (tested against the Python crop boxes).
 */
object EyeCrop {
    // MediaPipe face-mesh eye contours (16 points each), as in eye_state.py
    val RIGHT_EYE_CONTOUR = intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246)
    val LEFT_EYE_CONTOUR = intArrayOf(362, 382, 381, 380, 374, 373, 390, 249, 263, 466, 388, 387, 386, 385, 384, 398)

    /** Crop box x1, y1, x2, y2 (pixels, x2 / y2 exclusive) for one eye, or null if the eye is too small. */
    fun box(landmarks: Array<DoubleArray>, contour: IntArray, scale: Double, width: Int, height: Int): IntArray? {
        var sx = 0.0; var sy = 0.0
        var minX = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        for (i in contour) {
            val x = landmarks[i][0]; val y = landmarks[i][1]
            sx += x; sy += y
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        val cx = sx / contour.size
        val cy = sy / contour.size
        val side = maxOf(maxX - minX, maxY - minY) * scale
        if (side < 8) return null
        // Python int() truncates toward zero
        val x1 = maxOf(0.0, cx - side / 2).toInt()
        val y1 = maxOf(0.0, cy - side / 2).toInt()
        val x2 = minOf(width.toDouble(), cx + side / 2).toInt()
        val y2 = minOf(height.toDouble(), cy + side / 2).toInt()
        if (x2 - x1 < 4 || y2 - y1 < 4) return null
        return intArrayOf(x1, y1, x2, y2)
    }

    /** Boxes for the right and left eye (null entries dropped). */
    fun boxes(landmarks: Array<DoubleArray>, scale: Double, width: Int, height: Int): List<IntArray> =
        listOfNotNull(box(landmarks, RIGHT_EYE_CONTOUR, scale, width, height),
            box(landmarks, LEFT_EYE_CONTOUR, scale, width, height))

    /** Index of the "closed" class in the classifier's class names, or null if none matches. */
    fun closedIndex(names: List<String>, closedNames: List<String>): Int? {
        val wanted = closedNames.map { it.lowercase() }.toSet()
        return names.indexOfFirst { it.lowercase() in wanted }.takeIf { it >= 0 }
    }

    /** Classifier outputs -> probabilities (softmax only if the model did not apply it already). */
    fun probabilities(raw: FloatArray): DoubleArray {
        val sum = raw.sumOf { it.toDouble() }
        if (raw.all { it >= 0f } && kotlin.math.abs(sum - 1.0) < 0.02) return DoubleArray(raw.size) { raw[it].toDouble() }
        val max = raw.maxOrNull()?.toDouble() ?: 0.0
        val e = DoubleArray(raw.size) { kotlin.math.exp(raw[it] - max) }
        val total = e.sum()
        return DoubleArray(raw.size) { e[it] / total }
    }

    /**
     * Ultralytics classification preprocessing (classify_transforms, crop_fraction 1.0):
     * resize the shorter side to [size] keeping the aspect ratio, then a centred size x size crop.
     * Returns the source rectangle (x, y, w, h) within a crop of cropW x cropH that maps onto the square.
     */
    fun centreSquare(cropW: Int, cropH: Int): IntArray {
        val side = minOf(cropW, cropH)
        return intArrayOf((cropW - side) / 2, (cropH - side) / 2, side, side)
    }
}
