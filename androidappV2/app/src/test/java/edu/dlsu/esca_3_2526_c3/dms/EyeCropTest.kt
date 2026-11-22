package edu.dlsu.esca_3_2526_c3.dms

import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Eye-state classifier inputs: crop boxes identical to dms/perception/eye_state.py, class lookup, probabilities. */
class EyeCropTest {
    @Test
    fun cropBoxesMatchTheDesktop() {
        val cases = JSONArray(javaClass.getResource("/golden/eye_crops.json")!!.readText())
        var n = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val raw = c.getJSONArray("landmarks")
            val lm = Array(raw.length()) { k -> raw.getJSONArray(k).let { p -> DoubleArray(p.length()) { p.getDouble(it) } } }
            val scales = c.getJSONArray("scales")
            val expected = c.getJSONArray("boxes")
            for (s in 0 until scales.length()) {
                val pair = expected.getJSONArray(s)
                val contours = listOf(EyeCrop.RIGHT_EYE_CONTOUR, EyeCrop.LEFT_EYE_CONTOUR)
                for ((e, contour) in contours.withIndex()) {
                    val got = EyeCrop.box(lm, contour, scales.getDouble(s), c.getInt("w"), c.getInt("h"))
                    if (pair.isNull(e)) assertNull(got)
                    else {
                        val b = pair.getJSONArray(e)
                        assertArrayEquals("case $i scale ${scales.getDouble(s)} eye $e",
                            IntArray(4) { b.getInt(it) }, got)
                    }
                    n++
                }
            }
        }
        assertTrue(n >= 200)
        println("eye crop parity: $n boxes identical to the desktop")
    }

    @Test
    fun closedClassAndProbabilities() {
        assertEquals(0, EyeCrop.closedIndex(listOf("closed", "open"), Config().eyeModel.closedClassNames))
        assertEquals(1, EyeCrop.closedIndex(listOf("Open_Eye", "Closed_Eye"), Config().eyeModel.closedClassNames))
        assertNull(EyeCrop.closedIndex(listOf("cat", "dog"), Config().eyeModel.closedClassNames))
        // already softmaxed (Ultralytics classifiers) -> unchanged; logits -> softmax
        assertEquals(0.8, EyeCrop.probabilities(floatArrayOf(0.8f, 0.2f))[0], 1e-6)
        val p = EyeCrop.probabilities(floatArrayOf(2f, 0f))
        assertEquals(1.0, p.sum(), 1e-9)
        assertEquals(0.8808, p[0], 1e-4)
        assertArrayEquals(intArrayOf(10, 0, 40, 40), EyeCrop.centreSquare(60, 40))
    }
}
