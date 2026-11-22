package edu.dlsu.esca_3_2526_c3.dms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guided recording: schedule, spoken cues and the label file (read by Python too: tests/test_guided_labels.py). */
class GuidedTest {
    private fun resource(name: String) = javaClass.getResource("/golden/$name")!!.readText()

    @Test
    fun labelFilesMatchTheSharedReference() {
        assertEquals(resource("guided_quick_labels.csv"), GuidedRecorder(GuidedPlan.QUICK, 2.5).labelsCsv())
        assertEquals(resource("guided_standard_labels.csv"), GuidedRecorder(GuidedPlan.STANDARD, 2.5).labelsCsv())
    }

    @Test
    fun scheduleAndCues() {
        val r = GuidedRecorder(GuidedPlan.QUICK, 2.5)
        assertEquals(2.5 + GuidedPlan.QUICK.totalSeconds, r.endT, 1e-9)
        var s = r.state(5.0)
        assertEquals(0, s.step); assertFalse(s.countdown); assertEquals(7.5, s.secondsLeft, 1e-9)
        s = r.state(28.0) // countdown before "close your eyes"
        assertEquals(2, s.step); assertTrue(s.countdown); assertTrue(s.text.startsWith("Get ready"))
        s = r.state(31.0)
        assertEquals("Close your eyes", s.text); assertFalse(s.countdown)
        val spoken = r.cuesBetween(27.0, 34.0).map { it.speak }
        assertEquals(listOf("Get ready. Close your eyes. 3", "2", "1", "Now", "Open your eyes"), spoken)
        assertTrue(r.state(r.endT + 1).finished)
    }

    @Test
    fun notDoneAndEarlyStop() {
        val r = GuidedRecorder(GuidedPlan.QUICK, 0.0)
        assertNull(r.markLastActionNotDone(5.0)) // nothing labelled has started yet
        assertEquals("eye_closure", r.markLastActionNotDone(29.0)!!.label)
        r.stop(52.0) // stopped during the yawn (49-55 s)
        assertEquals("behavior,start_s,end_s\nyawning,49.00,52.00\n", r.labelsCsv())
        val sum = r.summary("P01", "day")
        assertFalse(sum.getBoolean("completed"))
        assertEquals(1, sum.getJSONArray("removed_steps").length())
    }
}
