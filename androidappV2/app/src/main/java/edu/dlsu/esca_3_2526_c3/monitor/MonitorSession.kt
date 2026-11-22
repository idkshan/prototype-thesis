package edu.dlsu.esca_3_2526_c3.monitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import edu.dlsu.esca_3_2526_c3.dms.AimAdvice
import edu.dlsu.esca_3_2526_c3.dms.AlertCommand
import edu.dlsu.esca_3_2526_c3.dms.BuzzerLink
import edu.dlsu.esca_3_2526_c3.dms.CALIBRATED
import edu.dlsu.esca_3_2526_c3.dms.CameraAimer
import edu.dlsu.esca_3_2526_c3.dms.Config
import edu.dlsu.esca_3_2526_c3.dms.DecisionEngine
import edu.dlsu.esca_3_2526_c3.dms.Detection
import edu.dlsu.esca_3_2526_c3.dms.EngineOutput
import edu.dlsu.esca_3_2526_c3.dms.FaceObservation
import edu.dlsu.esca_3_2526_c3.dms.FeatureExtractor
import edu.dlsu.esca_3_2526_c3.dms.FrameFeatures
import edu.dlsu.esca_3_2526_c3.dms.FusionModel
import edu.dlsu.esca_3_2526_c3.dms.Geometry
import edu.dlsu.esca_3_2526_c3.dms.HeadPose
import edu.dlsu.esca_3_2526_c3.dms.MjpegReader
import edu.dlsu.esca_3_2526_c3.dms.PerceptionResult
import edu.dlsu.esca_3_2526_c3.dms.SensorContext
import edu.dlsu.esca_3_2526_c3.dms.SensorPoller
import edu.dlsu.esca_3_2526_c3.dms.SessionLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One processed frame for the screen. */
class LiveFrame(
    val image: Bitmap,
    val features: FrameFeatures,
    val out: EngineOutput,
    val fps: Double,
    val totalMs: Double,
    val aim: AimAdvice?,
    val calibrationProgress: Double,
    val warning: String,
    val calibrationProblem: String,
    val sensors: JSONObject?,
    val yoloOn: Boolean,
)

/**
 * The whole pipeline on the phone, like scripts/run_monitor.py:
 *   ESP32-CAM stream (newest frame only) -> MediaPipe (+ YOLO) -> features -> rules,
 *   severity, fuzzy risk -> alert state machine -> buzzer on the sensor board,
 *   GPS / motion from the sensor board, session log, camera watchdog (3 s -> unavailable).
 * Callbacks arrive on background threads.
 */
/** logName: guided recording (log always saved, folder "<logName>_<date>"); null = normal monitoring ("phone_<date>"). */
class MonitorSession(private val context: Context, private val settings: DeviceSettings, private val listener: Listener,
                     private val logName: String? = null) {

    interface Listener {
        fun onFrame(frame: LiveFrame)
        /** pushed: a supervisor push belongs to this start / escalate (sent once per HIGH alert, at most one per minute). */
        fun onAlert(cmd: AlertCommand, pushed: Boolean)
        fun onStatus(text: String)
    }

    val cfg: Config = ModelFiles.config(context)
    private val engine = DecisionEngine(cfg)
    private var fusionProblem = ""
    val fusion: FusionModel? = try { ModelFiles.fusionModel(context) } catch (e: Exception) { fusionProblem = e.message ?: "unreadable"; null }
    private val extractor = FeatureExtractor(cfg.detector, fusion)
    private val sensorLock = Any()
    private val sensors = SensorContext(cfg.context)
    private val aimer = CameraAimer(cfg.calibration.maxCameraOffsetDeg)
    private val wifi = WifiNet(context)
    private val running = AtomicBoolean(false)
    private val latest = AtomicReference<Pair<ByteArray, Double>?>(null) // newest JPEG + receive time
    private val t0 = SystemClock.elapsedRealtimeNanos()
    private var face: FaceAi? = null
    private var yolo: YoloAi? = null
    private var eye: EyeAi? = null
    /** True when the optional eye-state classifier runs (Details shows its output). */
    val eyeModelOn: Boolean get() = eye != null
    private var buzzer: BuzzerLink? = null
    private var poller: SensorPoller? = null
    private var log: SessionLog? = null
    private var tone: ToneGenerator? = null
    private var streamThread: Thread? = null
    private var procThread: Thread? = null
    @Volatile var framesIn = 0
        private set
    @Volatile var reconnects = 0
        private set
    private var cameraLostEvents = 0
    val logDir: File? get() = log?.dir

    fun now(): Double = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9

    fun start() {
        if (!running.compareAndSet(false, true)) return
        wifi.start()
        try {
            face = FaceAi(context, cfg.face)
        } catch (e: Throwable) { // includes UnsatisfiedLinkError from the native MediaPipe library
            listener.onStatus("Face model failed to load: ${e.message}")
            running.set(false)
            wifi.stop()
            return
        }
        yolo = try {
            ModelFiles.yoloModel(context)?.let { YoloAi(it, ModelFiles.yoloLabels(context), cfg.detector) }
        } catch (e: Throwable) {
            listener.onStatus("YOLO model not usable (${e.message}); phone detection off")
            null
        }
        eye = try {
            ModelFiles.eyeModel(context)?.let { EyeAi(it, ModelFiles.eyeLabels(context), cfg.eyeModel) }
        } catch (e: Throwable) {
            listener.onStatus("Eye-state model not usable (${e.message}); running without it")
            null
        }
        val sensorUrl = settings.sensorUrl.trim()
        if (sensorUrl.isNotEmpty()) {
            buzzer = BuzzerLink(sensorUrl, { url -> wifi.getBytes(url, 1000) }, { listener.onStatus(it) })
            poller = SensorPoller(sensorUrl, settings.pollMs.toLong(), { url -> wifi.getText(url, 1200) }, ::now, sensors, sensorLock,
                { listener.onStatus(it) })
        }
        if (settings.phoneSound) tone = try { ToneGenerator(AudioManager.STREAM_ALARM, 90) } catch (_: Exception) { null }
        if (settings.saveLogs || logName != null) {
            val root = context.getExternalFilesDir("sessions") ?: File(context.filesDir, "sessions")
            val name = (logName ?: "phone") + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val meta = JSONObject()
                .put("source", settings.streamUrl)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
                .put("models", JSONObject()
                    .put("face", "face_landmarker.task")
                    .put("detector", yolo?.let { JSONObject().put("file", ModelFiles.source(context, ModelFiles.YOLO))
                        .put("input", it.inputSize).put("classes", JSONArray(it.classNames)) } ?: JSONObject.NULL))
                .put("config", ModelFiles.source(context, ModelFiles.CONFIG))
                .put("eye_model", eye?.let { JSONObject().put("file", ModelFiles.source(context, ModelFiles.EYE))
                    .put("input", it.inputSize).put("classes", JSONArray(it.classNames)) } ?: JSONObject.NULL)
                .put("fusion", ModelFiles.source(context, ModelFiles.FUSION))
            log = try { SessionLog(File(root, name), meta) } catch (e: Exception) { listener.onStatus("Log not saved: ${e.message}"); null }
        }
        if (fusionProblem.isNotEmpty()) listener.onStatus("Fusion model not usable ($fusionProblem); running without it")
        listener.onStatus("Connecting to the camera ${settings.streamUrl} …")
        streamThread = Thread(::readStream, "camera-stream").apply { isDaemon = true; start() }
        procThread = Thread(::process, "dms-pipeline").apply { isDaemon = true; start() }
    }

    fun recalibrate() = synchronized(engine) { engine.recalibrate() }

    /** Stop everything; the buzzer is switched off; returns the session summary (or null). */
    fun stop(): JSONObject? {
        if (!running.compareAndSet(true, false)) return null
        procThread?.join(2000)
        streamThread?.interrupt()
        streamThread?.join(1000)
        buzzer?.close()
        poller?.close()
        face?.close()
        yolo?.close()
        eye?.close()
        tone?.release()
        val summary = log?.let { l ->
            val extra = JSONObject()
                .put("stream", JSONObject().put("frames_received", framesIn).put("reconnects", reconnects).put("camera_lost_events", cameraLostEvents))
                .put("sensor_poll_errors", poller?.errors ?: 0)
                .put("buzzer_failures", buzzer?.failures ?: 0)
                .put("driving_duration_min", synchronized(sensorLock) { sensors.duration.continuousS / 60.0 })
            try { l.close(engine.calibrator, extra) } catch (_: Exception) { null }
        }
        wifi.stop()
        return summary
    }

    // ------------------------------------------------------------------ camera stream
    private fun readStream() {
        while (running.get()) {
            var conn: HttpURLConnection? = null
            try {
                conn = wifi.open(settings.streamUrl, 5000)
                val reader = MjpegReader(conn.inputStream)
                listener.onStatus("Camera connected")
                while (running.get()) {
                    val jpeg = reader.next()
                    latest.set(Pair(jpeg, now())) // older unprocessed frames are dropped: delay cannot build up
                    framesIn++
                }
            } catch (e: Exception) {
                if (!running.get()) break
                reconnects++
                listener.onStatus("Camera: ${e.message ?: e.javaClass.simpleName} - retrying (close any browser tab showing the stream)")
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            } finally {
                conn?.disconnect()
            }
        }
    }

    // ------------------------------------------------------------------ pipeline
    private fun process() {
        var lastFrameT: Double? = null
        var lostReported = false
        var index = 0
        var lastDets: List<Detection> = emptyList()
        var fpsEma = 0.0
        var lastWall = SystemClock.elapsedRealtimeNanos()
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        while (running.get()) {
            val item = latest.getAndSet(null)
            if (item == null) {
                val lt = lastFrameT
                if (lt != null && !lostReported && now() - lt > cfg.alerts.cameraTimeoutS) {
                    val cmds = synchronized(engine) { engine.cameraLost(lt + cfg.alerts.cameraTimeoutS, "no frame for ${cfg.alerts.cameraTimeoutS} s") }
                    dispatch(cmds)
                    log?.logAlerts(cmds)
                    lostReported = true
                    cameraLostEvents++
                    listener.onStatus("No picture from the camera: monitoring unavailable")
                }
                try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                continue
            }
            lostReported = false
            val (jpeg, t) = item
            val start = SystemClock.elapsedRealtimeNanos()
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: continue
            val timings = linkedMapOf<String, Double>()
            fun ms(from: Long) = (SystemClock.elapsedRealtimeNanos() - from) / 1e6

            var s = SystemClock.elapsedRealtimeNanos()
            val faceObs = try { face?.detect(bmp, (t * 1000).toLong()) } catch (e: Exception) { null }
            timings["face_ms"] = ms(s)
            val e = eye
            var eyeProb: Double? = null
            if (e != null && faceObs != null) {
                s = SystemClock.elapsedRealtimeNanos()
                eyeProb = try { e.predict(bmp, faceObs) } catch (_: Exception) { null }
                timings["eye_ms"] = ms(s)
            }
            val y = yolo
            if (y != null && index % maxOf(1, settings.yoloEvery) == 0) {
                s = SystemClock.elapsedRealtimeNanos()
                lastDets = try { y.detect(bmp) } catch (e: Exception) { emptyList() }
                timings["yolo_ms"] = ms(s)
            }
            val dets = lastDets.map { Detection(it.label, it.confidence, it.box, it.role) }
            timings["perception_ms"] = ms(start)
            s = SystemClock.elapsedRealtimeNanos()
            val f = extractor.extract(PerceptionResult(bmp.width, bmp.height, faceObs, dets, eyeProb, y?.roles ?: emptySet()), t)
            timings["features_ms"] = ms(s)
            s = SystemClock.elapsedRealtimeNanos()
            val ctx = synchronized(sensorLock) { sensors.read(t) }
            val out = synchronized(engine) { engine.step(f, ctx) }
            timings["decision_ms"] = ms(s)
            timings["total_ms"] = ms(start)
            dispatch(out.alerts)
            log?.log(index, f, out, timings)

            val wall = SystemClock.elapsedRealtimeNanos()
            val inst = 1e9 / maxOf(1L, wall - lastWall)
            lastWall = wall
            fpsEma = if (fpsEma == 0.0) inst else 0.9 * fpsEma + 0.1 * inst
            val cal = engine.calibrator
            val aim = if (out.calibrationStatus != CALIBRATED || cal.warning.isNotEmpty()) aimer.update(f.yaw, f.pitch) else null
            listener.onFrame(LiveFrame(draw(bmp, faceObs, dets, out), f, out, fpsEma, timings.getValue("total_ms"), aim,
                cal.progress(t), cal.warning, cal.lastFailure, poller?.lastJson, y != null))
            lastFrameT = t
            index++
        }
    }

    private fun dispatch(cmds: List<AlertCommand>) {
        val pushed = cmds.any { it.event == "push" }
        for (c in cmds) {
            buzzer?.send(c)
            tone?.let { tg ->
                if (c.event == "start" || c.event == "escalate" || c.event == "beep") {
                    when (c.tier) {
                        "LOW" -> tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                        "MEDIUM" -> tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
                        "HIGH" -> tg.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 500)
                    }
                }
                if (c.event == "stop" || c.event == "unavailable") tg.stopTone()
            }
            listener.onAlert(c, pushed && (c.event == "start" || c.event == "escalate"))
        }
    }

    // ------------------------------------------------------------------ overlay
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun draw(src: Bitmap, face: FaceObservation?, dets: List<Detection>, out: EngineOutput): Bitmap {
        val img = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(img)
        val r = maxOf(2f, img.width / 220f)
        if (face != null) {
            val p = face.landmarks
            paint.style = Paint.Style.FILL
            paint.color = Color.CYAN
            for (i in Geometry.RIGHT_EYE_EAR + Geometry.LEFT_EYE_EAR) c.drawCircle(p[i][0].toFloat(), p[i][1].toFloat(), r, paint)
            paint.color = Color.MAGENTA
            for (i in Geometry.MOUTH_MAR) c.drawCircle(p[i][0].toFloat(), p[i][1].toFloat(), r, paint)
            paint.color = Color.GREEN
            for (i in HeadPose.POSE_LANDMARKS) c.drawCircle(p[i][0].toFloat(), p[i][1].toFloat(), r * 1.3f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = r / 2
            paint.color = Color.LTGRAY
            val b = face.bbox
            c.drawRect(b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3].toFloat(), paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r
        for (d in dets) {
            paint.color = when (d.role) {
                "phone" -> Color.RED; "eye_closed" -> Color.rgb(255, 140, 0); "eye_open" -> Color.GREEN; "yawn" -> Color.MAGENTA
                else -> continue
            }
            if (!d.inDriverRoi) paint.color = Color.GRAY
            c.drawRect(d.box[0].toFloat(), d.box[1].toFloat(), d.box[2].toFloat(), d.box[3].toFloat(), paint)
        }
        paint.style = Paint.Style.FILL
        val tierColor = when (out.activeTier) { "HIGH" -> Color.RED; "MEDIUM" -> Color.rgb(255, 140, 0); "LOW" -> Color.YELLOW; else -> Color.GREEN }
        paint.color = Color.argb(150, 0, 0, 0)
        c.drawRect(0f, 0f, img.width.toFloat(), r * 9, paint)
        paint.color = tierColor
        paint.textSize = r * 6
        val label = "${out.alertState} ${if (out.activeTier != "NONE") out.activeTier else ""}  risk ${out.risk.tier}  " +
            out.behavior.drowsinessState.replace('_', ' ')
        c.drawText(label, r * 2, r * 7, paint)
        return img
    }
}
