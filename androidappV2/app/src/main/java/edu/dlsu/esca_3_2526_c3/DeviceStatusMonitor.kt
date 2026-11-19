package edu.dlsu.esca_3_2526_c3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.media.FaceDetector
import kotlin.math.roundToInt
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks configured prototype endpoints off the UI thread. No detector is implemented here. */
class DeviceStatusMonitor(private val context: Context, private val onChange: () -> Unit) {
    data class State(
        val camera: Boolean = false,
        val sensor: Boolean = false,
        val gps: Boolean = false,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val speedKph: Double? = null,
        val photo: Bitmap? = null,
        val faceOffsetPercent: Int? = null // Percentage of half-frame width, not a yaw angle.
    )

    var state = State()
        private set
    private var running = false
    private val handler = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            Thread {
                val checked = check()
                handler.post {
                    if (!running) return@post
                    // A failed check supplies photo=null, so an old image cannot remain visible.
                    state = checked
                    onChange()
                    handler.postDelayed(this, 5_000L)
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
            BitmapFactory.decodeStream(c.inputStream) ?: error("No camera picture")
        } finally { c.disconnect() }
    }

    /** Estimate the horizontal position of the visible face from one JPEG snapshot. */
    private fun faceOffset(frame: Bitmap): Int? {
        if (frame.width < 2 || frame.height < 2) return null

        val width = (minOf(frame.width, 640) / 2) * 2 // FaceDetector requires even width.
        val height = (frame.height.toDouble() * width / frame.width).roundToInt().coerceAtLeast(2)
        val resized = Bitmap.createScaledBitmap(frame, width, height, false)
        val rgb565 = resized.copy(Bitmap.Config.RGB_565, false)

        try {
            val faces = arrayOfNulls<FaceDetector.Face>(1)
            if (FaceDetector(width, height, 1).findFaces(rgb565, faces) == 0) return null
            val middle = PointF()
            faces[0]?.getMidPoint(middle) ?: return null
            return (((middle.x - width / 2f) / (width / 2f)) * 100f).roundToInt()
        } finally {
            rgb565.recycle()
            if (resized !== frame) resized.recycle()
        }
    }

    private fun check(): State {
        val prefs = context.getSharedPreferences("device_setup_v1", Context.MODE_PRIVATE)
        val cameraUrl = (prefs.getString("camera_url", "http://192.168.4.1") ?: "").trimEnd('/')
        val sensorUrl = (prefs.getString("sensor_url", "") ?: "").trimEnd('/')

        val frame = try {
            if (cameraUrl.isBlank() || !json("$cameraUrl/api/status").optBoolean("cameraReady", false)) null
            else photo("$cameraUrl/jpg")
        } catch (_: Exception) { null }

        val sensor = try {
            if (sensorUrl.isBlank()) null else json("$sensorUrl/sensors")
        } catch (_: Exception) { null }

        val offset = try { frame?.let { faceOffset(it) } } catch (_: Exception) { null }
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
            photo = frame, faceOffsetPercent = offset)
    }
}
