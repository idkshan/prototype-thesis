package edu.dlsu.esca_3_2526_c3.dms

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.nio.file.Files
import java.util.Collections

/** Camera stream, YOLO output, sensor board link and session log. */
class DeviceTest {

    @Test
    fun readsTheEsp32CamStreamFormat() {
        // the bytes streamHandler() in 07_esp32cam_prototype.ino sends for each frame
        val jpegs = listOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte()), ByteArray(5000) { (it % 251).toByte() })
        val out = ByteArrayOutputStream()
        for (j in jpegs) {
            out.write("\r\n--frame\r\n".toByteArray())
            out.write("Content-Type: image/jpeg\r\nContent-Length: ${j.size}\r\n\r\n".toByteArray())
            out.write(j)
        }
        val reader = MjpegReader(ByteArrayInputStream(out.toByteArray()))
        assertArrayEquals(jpegs[0], reader.next())
        assertArrayEquals(jpegs[1], reader.next())
        try {
            reader.next(); throw AssertionError("expected end of stream")
        } catch (_: EOFException) {
        }
    }

    @Test
    fun decodesYoloV8OutputAndMapsBoxesBackToThePicture() {
        // [1, 4 + 80, 3]: two overlapping phones (one suppressed) and a person; normalised xywh
        val nc = 80; val anchors = 3
        val out = FloatArray((4 + nc) * anchors)
        fun set(c: Int, j: Int, v: Float) { out[c * anchors + j] = v }
        val phone = YoloDecoder.COCO.indexOf("cell phone")
        set(0, 0, 0.5f); set(1, 0, 0.5f); set(2, 0, 0.1f); set(3, 0, 0.2f); set(4 + phone, 0, 0.8f)
        set(0, 1, 0.51f); set(1, 1, 0.5f); set(2, 1, 0.1f); set(3, 1, 0.2f); set(4 + phone, 1, 0.6f)
        set(0, 2, 0.2f); set(1, 2, 0.3f); set(2, 2, 0.2f); set(3, 2, 0.4f); set(4, 2, 0.9f)
        val lb = YoloDecoder.Letterbox.of(480, 320, 640) // HVGA camera picture into a 640 input
        val cfg = ObjectDetectorConfig()
        val dets = YoloDecoder.decode(out, intArrayOf(1, 4 + nc, anchors), YoloDecoder.COCO, lb, 0.2, 0.5) { cfg.roleOf(it) }
        assertEquals(2, dets.size)
        val p = dets.first { it.role == "phone" }
        assertEquals(0.8, p.confidence, 1e-6)
        // centre of the input (320, 320) -> centre of the picture (240, 160)
        assertEquals(240.0, (p.box[0] + p.box[2]) / 2, 1e-3)
        assertEquals(160.0, (p.box[1] + p.box[3]) / 2, 1e-3)
        assertEquals(0.1 * 640 / lb.scale, p.box[2] - p.box[0], 1e-3)
    }

    @Test
    fun decodesEndToEndOutput() {
        // [1, 300, 6] x1 y1 x2 y2 score class, pixels of a 320 input; driver-state class order
        val out = FloatArray(300 * 6)
        floatArrayOf(100f, 100f, 140f, 120f, 0.7f, 0f).copyInto(out, 0)
        floatArrayOf(10f, 10f, 20f, 20f, 0.1f, 2f).copyInto(out, 6) // below the confidence limit
        val cfg = ObjectDetectorConfig()
        val dets = YoloDecoder.decode(out, intArrayOf(1, 300, 6), YoloDecoder.DRIVER_STATE, YoloDecoder.Letterbox.of(320, 320, 320), 0.2, 0.5) { cfg.roleOf(it) }
        assertEquals(1, dets.size)
        assertEquals("eye_closed", dets[0].role)
        assertArrayEquals(doubleArrayOf(100.0, 100.0, 140.0, 120.0), dets[0].box, 1e-6)
    }

    @Test
    fun buzzerRequestsGoOutInOrderAndAStopIsRetried() {
        val sent = Collections.synchronizedList(mutableListOf<String>())
        var failStops = 2
        val link = BuzzerLink("http://192.168.4.1/", { url ->
            if (url.endsWith("level=0") && failStops-- > 0) throw java.io.IOException("timeout")
            sent.add(url.substringAfter("?"))
        })
        fun c(event: String, tier: String, action: String) = AlertCommand(0.0, tier, action, false, "", event == "start", event)
        link.send(c("start", "MEDIUM", "repeating_buzzer"))
        link.send(c("escalate", "HIGH", "alarm"))
        link.send(c("push", "HIGH", "alarm")) // not a buzzer command
        link.send(c("keepalive", "HIGH", "alarm"))
        link.send(c("stop", "HIGH", "off"))
        link.flush()
        assertEquals(listOf("level=2", "level=3", "level=3", "level=0"), sent.toList())
        link.close() // quitting switches the buzzer off
        assertEquals("level=0", sent.last())
    }

    @Test
    fun sensorPollerFeedsTheContext() {
        val ctx = SensorContext(Config().context)
        val lock = Any()
        val board = """{"fix":true,"lat":14.5649,"lng":120.9932,"kmh":31.5,"sats":8,"hdop":1.1,"jolt":0.3,"motion_g":0.12,"tier":0,"clients":2}"""
        val poller = SensorPoller("http://192.168.4.1", 20, { url -> assertTrue(url.endsWith("/data")); board }, { 1.0 }, ctx, lock)
        Thread.sleep(150)
        poller.close()
        val r = synchronized(lock) { ctx.read(1.0) }
        assertEquals(31.5, r.speedKmh!!, 0.0)
        assertEquals(0.12, r.motionG!!, 0.0) // horizontal motion_g wins over jolt
        assertEquals("gps", r.speedSource)
    }

    @Test
    fun sessionLogUsesTheDesktopFormat() {
        val dir = Files.createTempDirectory("dms-session").toFile()
        val engine = DecisionEngine(Config(), "t")
        val log = SessionLog(File(dir, "s1"), JSONObject().put("source", "unit test"))
        for (i in 0 until 80) {
            val f = FrameFeatures(t = i * 0.1, facePresent = true, ear = 0.3, earLeft = 0.3, earRight = 0.3, mar = 0.05,
                yaw = 1.0, pitch = 0.0, roll = 0.0, blink = 0.1, jawOpen = 0.02, eyeWidthPx = 30.0, lookDown = 0.1)
            log.log(i, f, engine.step(f, ContextReading(speedKmh = 40.0, speedSource = "fixed")), mapOf("total_ms" to 40.0))
        }
        val summary = log.close(engine.calibrator)
        assertEquals(80, summary.getInt("frames"))
        assertEquals("CALIBRATED", summary.getJSONObject("calibration").getString("status"))
        val first = JSONObject(File(dir, "s1/frames.jsonl").readLines().first())
        for (key in listOf("t", "features", "states", "behavior", "severity", "risk", "active_tier", "context", "calibration")) {
            assertTrue("frames.jsonl misses $key", first.has(key))
        }
        assertTrue(first.getJSONObject("behavior").has("drowsiness_state"))
        dir.deleteRecursively()
    }
}
