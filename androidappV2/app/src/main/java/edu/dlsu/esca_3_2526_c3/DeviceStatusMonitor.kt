package edu.dlsu.esca_3_2526_c3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import edu.dlsu.esca_3_2526_c3.dms.AimAdvice
import edu.dlsu.esca_3_2526_c3.dms.CameraAimer
import edu.dlsu.esca_3_2526_c3.dms.FeatureExtractor
import edu.dlsu.esca_3_2526_c3.dms.PerceptionResult
import edu.dlsu.esca_3_2526_c3.monitor.DeviceSettings
import edu.dlsu.esca_3_2526_c3.monitor.FaceAi
import edu.dlsu.esca_3_2526_c3.monitor.ModelFiles
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Checks the boards off the UI thread while monitoring is NOT running (Setup, Home, Details):
 * a camera picture (http://<camera>/jpg) and the sensor board (http://<sensor>/data, or /sensors on
 * older test boards). With aim = true, the picture also goes through the same MediaPipe face model as
 * monitoring, giving the camera-angle advice of scripts/aim_camera.py ("move the camera LEFT ...").
 * While monitoring runs, the monitoring service owns the camera; use its live frames instead.
 */
class DeviceStatusMonitor(private val context: Context, private val aim: Boolean = false,
                          private val onChange: () -> Unit) {
    data class State(
        val camera: Boolean = false,
        val sensor: Boolean = false,
        val gps: Boolean = false,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val speedKph: Double? = null,
        val photo: Bitmap? = null,
        val faceOffsetPercent: Int? = null, // face centre left (-) / right (+) of the picture centre, % of half width
        val faceFound: Boolean = false,
        val aim: AimAdvice? = null, // camera angle advice (MediaPipe head pose); null without aim mode or face
        val checked: Boolean = false, // false until the first check has finished
    )

    var state = State()
        private set
    @Volatile private var running = false
    private val handler = Handler(Looper.getMainLooper())
    private val settings = DeviceSettings(context)
    private var face: FaceAi? = null
    private val faceLock = Any() // the face model is used by one check thread and closed by stop()
    private var faceFailed = false
    private val cfg by lazy { ModelFiles.config(context) }
    private val extractor by lazy { FeatureExtractor(cfg.detector) }
    private val aimer by lazy { CameraAimer(cfg.calibration.maxCameraOffsetDeg) }
    private val t0 = SystemClock.elapsedRealtime()

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            Thread {
                val checked = if (DriverMonitoringService.active) State() else check()
                handler.post {
                    if (!running) return@post
                    // A failed check supplies photo=null, so an old image cannot remain visible.
                    state = checked
                    onChange()
                    // Faster refresh while aiming the camera.
                    handler.postDelayed(this, if (aim) 1_000L else 5_000L)
                }
            }.start()
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(poll)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(poll)
        state = State()
        Thread { synchronized(faceLock) { try { face?.close() } catch (_: Exception) {}; face = null } }.start()
    }

    private fun connect(url: String): HttpURLConnection {
        val target = URL(url)
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = manager.allNetworks.firstOrNull {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val connection = (if (wifi == null) target.openConnection() else wifi.openConnection(target)) as HttpURLConnection
        return connection.apply {
            connectTimeout = 2500
            readTimeout = 2500
            useCaches = false
        }
    }

    private fun json(url: String): JSONObject {
        val c = connect(url)
        return try {
            require(c.responseCode == 200) { "Device unavailable" }
            JSONObject(c.inputStream.bufferedReader().use { it.readText().take(4000) })
        } finally { c.disconnect() }
    }

    private fun photo(url: String): Bitmap {
        val c = connect(url)
        return try {
            require(c.responseCode == 200 && c.contentType?.startsWith("image/jpeg") == true)
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeStream(c.inputStream, null, options) ?: error("No camera picture")
        } finally { c.disconnect() }
    }

    /** Sensor board JSON: /data (firmware 06), else /sensors (older test boards). */
    private fun sensors(base: String): JSONObject? =
        runCatching { json("$base/data") }.getOrNull() ?: runCatching { json("$base/sensors") }.getOrNull()

    /** MediaPipe face on one picture: face position and camera-angle advice. */
    private fun analyse(frame: Bitmap): Triple<Boolean, Int?, AimAdvice?> = synchronized(faceLock) {
        if (!aim || faceFailed || !running) return Triple(false, null, null)
        val model = face ?: try { FaceAi(context, cfg.face).also { face = it } }
        catch (_: Throwable) { faceFailed = true; return Triple(false, null, null) }
        val ms = SystemClock.elapsedRealtime() - t0
        val obs = model.detect(frame, ms) ?: return Triple(false, null, aimer.update(null, null))
        val f = extractor.extract(PerceptionResult(frame.width, frame.height, obs), ms / 1000.0)
        val middle = (obs.bbox[0] + obs.bbox[2]) / 2.0
        val offset = ((middle - frame.width / 2.0) / (frame.width / 2.0) * 100.0).roundToInt()
        return Triple(true, offset, aimer.update(f.yaw, f.pitch))
    }

    private fun check(): State {
        val cameraUrl = settings.cameraUrl.trim().trimEnd('/')
        val sensorUrl = settings.sensorUrl.trim().trimEnd('/')

        val frame = try { if (cameraUrl.isBlank()) null else photo("$cameraUrl/jpg") } catch (_: Exception) { null }
        val sensor = if (sensorUrl.isBlank()) null else sensors(sensorUrl)
        val (found, offset, advice) = try { frame?.let { analyse(it) } ?: Triple(false, null, null) }
        catch (_: Exception) { Triple(false, null, null) }

        val fix = sensor?.optBoolean("fix", false) == true
        val lat = sensor?.optDouble("lat", Double.NaN) ?: Double.NaN
        val lng = sensor?.optDouble("lng", Double.NaN) ?: Double.NaN
        val valid = fix && lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 &&
                lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)
        val speed = sensor?.optDouble("kmh", Double.NaN) ?: Double.NaN
        return State(camera = frame != null, sensor = sensor != null,
            gps = valid, latitude = if (valid) lat else null,
            longitude = if (valid) lng else null,
            speedKph = if (valid && speed.isFinite() && speed >= 0.0) speed else null,
            photo = frame, faceOffsetPercent = offset, faceFound = found, aim = advice, checked = true)
    }
}
