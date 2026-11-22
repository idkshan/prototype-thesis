package edu.dlsu.esca_3_2526_c3.monitor

import android.content.Context
import edu.dlsu.esca_3_2526_c3.dms.Config
import edu.dlsu.esca_3_2526_c3.dms.FusionModel
import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Device settings (Settings screen) and model / config files.
 * Defaults match the boards on their own Wi-Fi "DMS-NET" (firmware/):
 * camera 192.168.4.200, sensor board 192.168.4.1.
 */
class DeviceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("device_setup_v1", Context.MODE_PRIVATE)

    var cameraUrl: String
        get() = prefs.getString("camera_url", DEFAULT_CAMERA) ?: DEFAULT_CAMERA
        set(v) = prefs.edit().putString("camera_url", v).apply()
    var sensorUrl: String
        get() = prefs.getString("sensor_url", DEFAULT_SENSOR) ?: DEFAULT_SENSOR
        set(v) = prefs.edit().putString("sensor_url", v).apply()
    var pollMs: Int
        get() = prefs.getInt("poll_ms", 1000)
        set(v) = prefs.edit().putInt("poll_ms", v).apply()
    var phoneSound: Boolean
        get() = prefs.getBoolean("phone_sound", false)
        set(v) = prefs.edit().putBoolean("phone_sound", v).apply()
    var saveLogs: Boolean
        get() = prefs.getBoolean("save_logs", true)
        set(v) = prefs.edit().putBoolean("save_logs", v).apply()
    var yoloEvery: Int
        get() = prefs.getInt("yolo_every", 1)
        set(v) = prefs.edit().putInt("yolo_every", v).apply()
    /** The phone speaks a short warning for MEDIUM / HIGH alerts ("Eyes on the road"). */
    var speakAlerts: Boolean
        get() = prefs.getBoolean("speak_alerts", true)
        set(v) = prefs.edit().putBoolean("speak_alerts", v).apply()
    /** DMS-NET password as last set from this phone (Settings > Device settings), for the setup texts. */
    var dmsNetPassword: String
        get() = prefs.getString("dms_net_password", DMS_NET_PASSWORD) ?: DMS_NET_PASSWORD
        set(v) = prefs.edit().putString("dms_net_password", v).apply()
    /** Break reminder every N minutes of a monitoring session (0 = off). */
    var breakMinutes: Int
        get() = prefs.getInt("break_minutes", 120)
        set(v) = prefs.edit().putInt("break_minutes", v).apply()
    /** Keep the driver screen on while monitoring and the app is open. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(v) = prefs.edit().putBoolean("keep_screen_on", v).apply()
    /** The phone vibrates for MEDIUM / HIGH alerts. */
    var vibrateAlerts: Boolean
        get() = prefs.getBoolean("vibrate_alerts", true)
        set(v) = prefs.edit().putBoolean("vibrate_alerts", v).apply()

    /** ESP32-CAM stream: http://<camera host>:81/stream (07_esp32cam_prototype.ino). */
    val streamUrl: String
        get() {
            val u = URL(cameraUrl.trimEnd('/'))
            return "${u.protocol}://${u.host}:81/stream"
        }

    companion object {
        const val DEFAULT_CAMERA = "http://192.168.4.200"
        const val DEFAULT_SENSOR = "http://192.168.4.1"
        const val DMS_NET = "DMS-NET"
        const val DMS_NET_PASSWORD = "drivesafe1"
    }
}

/** Model and config files: an imported copy (Settings) wins over the one packed in the app (assets). */
object ModelFiles {
    const val YOLO = "yolo.tflite"
    const val YOLO_LABELS = "yolo_labels.txt"
    const val CONFIG = "dms_config.json"
    const val FUSION = "fusion_model.json"
    const val EYE = "eye_state.tflite"
    const val EYE_LABELS = "eye_state_labels.txt"

    private fun assetBytes(context: Context, name: String): ByteArray? =
        try { context.assets.open(name).use { it.readBytes() } } catch (_: Exception) { null }

    fun bytes(context: Context, name: String): ByteArray? {
        val f = File(context.filesDir, name)
        return if (f.isFile) f.readBytes() else assetBytes(context, name)
    }

    private fun assetExists(context: Context, name: String): Boolean =
        try { context.assets.open(name).close(); true } catch (_: Exception) { false }

    fun source(context: Context, name: String): String = when {
        File(context.filesDir, name).isFile -> "imported"
        assetExists(context, name) -> "built in"
        else -> "none"
    }

    fun yoloModel(context: Context): ByteBuffer? = bytes(context, YOLO)?.let { b ->
        ByteBuffer.allocateDirect(b.size).order(ByteOrder.nativeOrder()).apply { put(b); rewind() }
    }

    /** One class name per line, in class order (optional: COCO / driver-state names are recognised). */
    fun yoloLabels(context: Context): List<String>? =
        bytes(context, YOLO_LABELS)?.toString(Charsets.UTF_8)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }

    /** Optional eye-state classifier (.tflite from scripts/export_models.py), or null = off. */
    fun eyeModel(context: Context): ByteBuffer? = bytes(context, EYE)?.let { b ->
        ByteBuffer.allocateDirect(b.size).order(ByteOrder.nativeOrder()).apply { put(b); rewind() }
    }

    fun eyeLabels(context: Context): List<String>? =
        bytes(context, EYE_LABELS)?.toString(Charsets.UTF_8)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }

    /** Picture-based fusion model (python scripts/train_fusion.py), or null = off. */
    fun fusionModel(context: Context): FusionModel? =
        bytes(context, FUSION)?.let { FusionModel.fromJson(it.toString(Charsets.UTF_8)) }

    /** Defaults, overridden by a tuned config (python scripts/export_android_config.py) if present. */
    fun config(context: Context): Config =
        bytes(context, CONFIG)?.let { Config.fromJson(it.toString(Charsets.UTF_8)) } ?: Config()
}
