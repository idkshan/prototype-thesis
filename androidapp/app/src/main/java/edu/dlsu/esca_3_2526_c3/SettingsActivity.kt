package edu.dlsu.esca_3_2526_c3

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import edu.dlsu.esca_3_2526_c3.dms.Config
import edu.dlsu.esca_3_2526_c3.dms.FusionModel
import edu.dlsu.esca_3_2526_c3.monitor.DeviceSettings
import edu.dlsu.esca_3_2526_c3.monitor.ModelFiles
import edu.dlsu.esca_3_2526_c3.monitor.WifiNet
import edu.dlsu.esca_3_2526_c3.monitor.YoloAi
import org.json.JSONObject
import java.io.File
import java.net.URL

/** Device addresses, connection tests, model / config import and options. */
class SettingsActivity : Activity() {
    private lateinit var settings: DeviceSettings
    private lateinit var wifi: WifiNet
    private lateinit var cameraUrl: EditText
    private lateinit var sensorUrl: EditText
    private lateinit var pollMs: EditText
    private lateinit var yoloEvery: EditText
    private lateinit var phoneSound: CheckBox
    private lateinit var saveLogs: CheckBox
    private lateinit var status: TextView
    private lateinit var models: TextView
    private lateinit var snapshot: ImageView
    private var importTarget: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.navy
        window.navigationBarColor = Ui.navy
        settings = DeviceSettings(this)
        wifi = WifiNet(this).apply { start() }
        val scroller = ScrollView(this).apply { setBackgroundColor(Ui.background) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 28))
        }
        scroller.addView(body)
        setContentView(scroller)
        body.addView(Ui.title(this, "Device settings"))

        status = Ui.note(this, "")
        val network = Ui.card(this)
        network.addView(Ui.title(this, "Wi-Fi: ${DeviceSettings.DMS_NET}"))
        network.addView(Ui.note(this, "The sensor board creates the Wi-Fi \"${DeviceSettings.DMS_NET}\" (password ${DeviceSettings.DMS_NET_PASSWORD}). " +
            "Join it, and tap \"Yes / Stay connected\" when Android says it has no internet. Keep mobile data on for Firebase."))
        network.addView(action("Open Wi-Fi settings") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) })
        body.addView(network, Ui.blockParams(this))

        val endpoints = Ui.card(this)
        endpoints.addView(Ui.title(this, "Device addresses"))
        endpoints.addView(Ui.note(this, "Camera (ESP32-CAM) - video at :81/stream"))
        cameraUrl = field(settings.cameraUrl)
        endpoints.addView(cameraUrl)
        endpoints.addView(Ui.note(this, "Sensor board (GPS, motion, buzzer) - leave empty to run without it"))
        sensorUrl = field(settings.sensorUrl)
        endpoints.addView(sensorUrl)
        endpoints.addView(Ui.note(this, "Sensor polling interval (ms)"))
        pollMs = field(settings.pollMs.toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        endpoints.addView(pollMs)
        endpoints.addView(action("Use DMS-NET defaults (192.168.4.200 / 192.168.4.1)") {
            cameraUrl.setText(DeviceSettings.DEFAULT_CAMERA); sensorUrl.setText(DeviceSettings.DEFAULT_SENSOR)
        })
        endpoints.addView(action("Save settings") { save() })
        endpoints.addView(action("Test camera picture") { testCamera() })
        endpoints.addView(action("Test sensors (GPS, motion)") { testSensors() })
        endpoints.addView(action("Test buzzer (HIGH for 1.5 s)") { testBuzzer() })
        endpoints.addView(status)
        snapshot = ImageView(this).apply { adjustViewBounds = true; contentDescription = "Last test picture" }
        endpoints.addView(snapshot, LinearLayout.LayoutParams(-1, -2))
        body.addView(endpoints, Ui.blockParams(this))

        val options = Ui.card(this)
        options.addView(Ui.title(this, "Options"))
        phoneSound = CheckBox(this).apply { text = "Phone beeps too (useful without the sensor board)"; isChecked = settings.phoneSound }
        options.addView(phoneSound)
        saveLogs = CheckBox(this).apply { text = "Save a session log (frames.jsonl, alerts.jsonl, summary.json)"; isChecked = settings.saveLogs }
        options.addView(saveLogs)
        options.addView(Ui.note(this, "Run YOLO every N frames (1 = every frame; 2-3 = faster on slow phones)"))
        yoloEvery = field(settings.yoloEvery.toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        options.addView(yoloEvery)
        options.addView(action("Save options") { save() })
        body.addView(options, Ui.blockParams(this))

        val modelCard = Ui.card(this)
        modelCard.addView(Ui.title(this, "Models and settings files"))
        models = Ui.note(this, "")
        modelCard.addView(models)
        modelCard.addView(action("Import YOLO model (.tflite)") { pick(ModelFiles.YOLO) })
        modelCard.addView(action("Import YOLO class names (.txt, one per line)") { pick(ModelFiles.YOLO_LABELS) })
        modelCard.addView(action("Import fusion model (.json from train_fusion.py)") { pick(ModelFiles.FUSION) })
        modelCard.addView(action("Import tuned config (.json from export_android_config.py)") { pick(ModelFiles.CONFIG) })
        modelCard.addView(action("Remove imported files (use the built-in ones)") {
            for (n in listOf(ModelFiles.YOLO, ModelFiles.YOLO_LABELS, ModelFiles.CONFIG, ModelFiles.FUSION)) File(filesDir, n).delete()
            refreshModels(); show("Imported files removed.")
        })
        body.addView(modelCard, Ui.blockParams(this))
        refreshModels()
    }

    override fun onDestroy() {
        wifi.stop()
        super.onDestroy()
    }

    private fun field(initial: String) = EditText(this).apply {
        setSingleLine(true)
        setText(initial)
        textSize = 15f
        setSelectAllOnFocus(true)
    }

    private fun action(label: String, callback: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { callback() }
    }

    private fun show(message: String) { status.text = message }

    private fun base(text: String, optional: Boolean = false): String {
        val value = text.trim().trimEnd('/')
        if (optional && value.isEmpty()) return ""
        val url = URL(value)
        require(url.protocol == "http" || url.protocol == "https") { "Address must start with http://" }
        require(url.host.isNotBlank()) { "Enter an address such as http://192.168.4.1" }
        return value
    }

    private fun save() {
        try {
            settings.cameraUrl = base(cameraUrl.text.toString())
            settings.sensorUrl = base(sensorUrl.text.toString(), optional = true)
            val interval = pollMs.text.toString().toInt()
            require(interval in 200..60000) { "Polling interval must be 200-60000 ms" }
            settings.pollMs = interval
            val every = yoloEvery.text.toString().toInt()
            require(every in 1..10) { "YOLO every N frames must be 1-10" }
            settings.yoloEvery = every
            settings.phoneSound = phoneSound.isChecked
            settings.saveLogs = saveLogs.isChecked
            show("Saved.")
        } catch (e: Exception) { show("Not saved: ${e.message}") }
    }

    private fun worker(label: String, op: () -> Unit) {
        show("$label …")
        Thread {
            try { op() } catch (e: Exception) { runOnUiThread { show("$label failed: ${e.message ?: e.javaClass.simpleName}") } }
        }.start()
    }

    private fun testCamera() {
        val b = try { base(cameraUrl.text.toString()) } catch (e: Exception) { show("Camera address: ${e.message}"); return }
        worker("Camera") {
            val bytes = wifi.getBytes("$b/jpg", 4000)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("not a JPEG picture")
            runOnUiThread { snapshot.setImageBitmap(bmp); show("Camera OK: ${bmp.width} × ${bmp.height}. Close any browser tab showing the stream before monitoring.") }
        }
    }

    private fun testSensors() {
        val b = try { base(sensorUrl.text.toString(), optional = true) } catch (e: Exception) { show("Sensor address: ${e.message}"); return }
        if (b.isEmpty()) { show("Enter the sensor board address first."); return }
        worker("Sensors") {
            val j = JSONObject(wifi.getText("$b/data", 3000))
            runOnUiThread {
                show("Sensors OK. GPS fix ${j.optBoolean("fix")}, ${j.optDouble("kmh", 0.0)} km/h, satellites ${j.optInt("sats")}, " +
                    "motion ${j.optDouble("motion_g", j.optDouble("jolt", 0.0))} g, devices on DMS-NET ${j.optInt("clients")}.")
            }
        }
    }

    private fun testBuzzer() {
        val b = try { base(sensorUrl.text.toString(), optional = true) } catch (e: Exception) { show("Sensor address: ${e.message}"); return }
        if (b.isEmpty()) { show("Enter the sensor board address first."); return }
        worker("Buzzer") {
            wifi.getBytes("$b/tier?level=3", 3000)
            Thread.sleep(1500)
            wifi.getBytes("$b/tier?level=0", 3000)
            runOnUiThread { show("Buzzer test sent (HIGH for 1.5 s, then off).") }
        }
    }

    // ------------------------------------------------------------------ import files
    private fun pick(target: String) {
        importTarget = target
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 41)
    }

    @Deprecated("Activity result API of android.app.Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val target = importTarget ?: return
        val uri: Uri = data?.data ?: return
        if (requestCode != 41 || resultCode != RESULT_OK) return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot read the file")
            when (target) { // check before replacing the working file
                ModelFiles.CONFIG -> Config.fromJson(bytes.toString(Charsets.UTF_8))
                ModelFiles.FUSION -> FusionModel.fromJson(bytes.toString(Charsets.UTF_8))
                ModelFiles.YOLO -> {
                    val buf = java.nio.ByteBuffer.allocateDirect(bytes.size).order(java.nio.ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
                    YoloAi(buf, null, Config().detector).close()
                }
            }
            File(filesDir, target).writeBytes(bytes)
            show("Imported $target (${bytes.size / 1024} KB).")
        } catch (e: Throwable) {
            show("Import failed: ${e.message}")
        }
        refreshModels()
    }

    private fun refreshModels() {
        val labels = ModelFiles.yoloLabels(this)
        models.text = "Face model: built in (face_landmarker.task)\n" +
            "YOLO model: ${ModelFiles.source(this, ModelFiles.YOLO)}" +
            (if (labels != null) " · ${labels.size} class names" else "") + "\n" +
            "Fusion model: ${ModelFiles.source(this, ModelFiles.FUSION)} (none = rules only)\n" +
            "Tuned config: ${ModelFiles.source(this, ModelFiles.CONFIG)} (none = thesis defaults)\n" +
            "Phone detection uses the built-in YOLOv8n (COCO \"cell phone\") until you import your own model."
    }
}
