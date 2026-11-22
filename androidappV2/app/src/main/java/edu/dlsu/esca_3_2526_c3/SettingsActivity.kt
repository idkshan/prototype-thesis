package edu.dlsu.esca_3_2526_c3

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.net.URLEncoder
import android.net.Uri
import android.widget.CheckBox
import edu.dlsu.esca_3_2526_c3.dms.Config
import edu.dlsu.esca_3_2526_c3.dms.FusionModel
import edu.dlsu.esca_3_2526_c3.dms.MjpegReader
import edu.dlsu.esca_3_2526_c3.monitor.DeviceSettings
import edu.dlsu.esca_3_2526_c3.monitor.ModelFiles
import edu.dlsu.esca_3_2526_c3.monitor.YoloAi
import edu.dlsu.esca_3_2526_c3.monitor.EyeAi
import edu.dlsu.esca_3_2526_c3.dms.EyeCrop
import java.io.File

/**
 * Device settings (Settings > Device settings and tests), for every driver: camera and sensor-board
 * addresses, "find the boards", camera / video / sensor / buzzer tests. In developer mode also the
 * research settings: models and tuned settings, session logs, camera Wi-Fi provisioning.
 * Read-only while monitoring runs.
 */
private const val PASSWORD_INPUT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

class SettingsActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("device_setup_v1", Context.MODE_PRIVATE) }
    private val settings by lazy { DeviceSettings(this) }
    private lateinit var yoloEvery: EditText
    private lateinit var wifiCurrent: EditText
    private lateinit var wifiNew: EditText
    private lateinit var wifiConfirm: EditText
    private lateinit var wifiHelp: LinearLayout
    private lateinit var models: TextView
    private var importTarget: String? = null

    // Input references are kept so tests and monitoring state can read or disable them.
    private lateinit var ssid: EditText
    private lateinit var password: EditText
    private lateinit var cameraUrl: EditText
    private lateinit var sensorUrl: EditText
    private lateinit var pollMs: EditText
    private lateinit var readOnlyNote: TextView
    private lateinit var snapshot: ImageView

    // Only configuration inputs and test buttons belong in this list.
    private val controls = mutableListOf<View>()

    // Each test owns its own badge and explanatory message.
    private data class TestViews(val indicator: TextView, val detail: TextView)

    /** Build the developer-only screen and restore the saved device configuration. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Monitoring state determines whether the configuration is read-only.
        DriverMonitoringService.restore(this)
        Ui.nightMode = Ui.nightPreference(this)

        // Fixed header and bottom navigation, with scrollable settings in between.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.canvas)
        }
        val header = Ui.header(this, "Device settings")
        root.addView(header)
        val scroller = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 16),
                Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 28))
        }
        scroller.addView(body)
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        Ui.fitSystemBars(this, root, header)

        body.addView(Ui.linkRow(this, "‹ Back to Settings", "") { finish() })
        val developer = MainActivity.developerEnabled(this)

        // This note changes when monitoring starts or stops.
        readOnlyNote = Ui.note(this, "")
        body.addView(readOnlyNote)

        // CONNECTION: URLs and hardware checks.
        val endpoints = Ui.card(this)
        endpoints.addView(Ui.title(this, "Camera and sensor board"))
        endpoints.addView(Ui.note(this, "The phone must be on the DMS-NET Wi-Fi. Press \"Find the boards\" to fill in the addresses " +
                "automatically, then test each part."))
        endpoints.addView(Ui.note(this, "Camera address (DMS-NET: ${DeviceSettings.DEFAULT_CAMERA})"))
        cameraUrl = field("Camera base URL", settings.cameraUrl)
        endpoints.addView(cameraUrl)

        // Store a valid base URL as soon as it changes.
        autoSave(cameraUrl) { prefs.edit().putString("camera_url", normalizedBase(it)).apply() }

        endpoints.addView(Ui.note(this, "Sensor board address: GPS, motion, buzzer (DMS-NET: ${DeviceSettings.DEFAULT_SENSOR}). Leave empty to drive without it"))
        sensorUrl = field("Sensor base URL", settings.sensorUrl)
        endpoints.addView(sensorUrl)
        autoSave(sensorUrl) {
            prefs.edit().putString("sensor_url", normalizedBase(it, optional = true)).apply()
        }

        // Every test displays its result next to the action that ran it.
        testAction(endpoints, "Find the boards on Wi-Fi") { discoverBoard(it) }
        testAction(endpoints, "Test camera photo") { testSnapshot(it) }
        testAction(endpoints, "Test camera video (5 s)") { testStream(it) }
        testAction(endpoints, "Test sensors (GPS, MPU6050)") { testSensors(it) }
        testAction(endpoints, "Test buzzer (HIGH 1.5 s)") { testBuzzer(it) }

        // The photo area stays empty until a JPEG test succeeds.
        snapshot = ImageView(this).apply {
            adjustViewBounds = true
            minimumHeight = Ui.dp(this@SettingsActivity, 90)
            contentDescription = "Last test image"
        }
        endpoints.addView(snapshot, LinearLayout.LayoutParams(-1, -2))
        endpoints.addView(action("Use the DMS-NET addresses") {
            cameraUrl.setText(DeviceSettings.DEFAULT_CAMERA); sensorUrl.setText(DeviceSettings.DEFAULT_SENSOR)
        })
        body.addView(endpoints, Ui.blockParams(this))

        // DMS-NET WI-FI PASSWORD: the sensor board creates DMS-NET, the camera joins it.
        val wifiCard = Ui.card(this)
        wifiCard.addView(Ui.title(this, "DMS-NET Wi-Fi password"))
        wifiCard.addView(Ui.note(this, "Changes the password on the sensor board and the camera (both must be on). " +
                "Afterwards every phone joins DMS-NET again with the new password."))
        wifiCurrent = field("Current password", settings.dmsNetPassword).apply { inputType = PASSWORD_INPUT }
        wifiNew = field("New password (8-63 characters)", "").apply { inputType = PASSWORD_INPUT }
        wifiConfirm = field("New password again", "").apply { inputType = PASSWORD_INPUT }
        wifiCard.addView(Ui.note(this, "Current password"))
        wifiCard.addView(wifiCurrent)
        wifiCard.addView(Ui.note(this, "New password"))
        wifiCard.addView(wifiNew)
        wifiCard.addView(wifiConfirm)
        wifiCard.addView(option("Show passwords", false) { show ->
            val type = if (show) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else PASSWORD_INPUT
            for (f in listOf(wifiCurrent, wifiNew, wifiConfirm)) { f.inputType = type; f.setSelection(f.text.length) }
        })
        testAction(wifiCard, "Change the DMS-NET password") { changeDmsNetPassword(it) }
        wifiHelp = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wifiCard.addView(wifiHelp)
        wifiCard.addView(Ui.note(this, "Forgot the password? Hold the BOOT button on the sensor board for 5 seconds " +
                "(3 beeps): DMS-NET goes back to ${DeviceSettings.DMS_NET_PASSWORD}. The camera finds it again by itself."))
        body.addView(wifiCard, Ui.blockParams(this))

        // Research settings: developer mode only.
        models = Ui.note(this, "")
        yoloEvery = field("YOLO every N frames", settings.yoloEvery.toString())
        if (!developer) {
            body.addView(Ui.note(this, "Alert and display options are on the Settings page."))
            applyReadOnlyState()
            return
        }
        val research = Ui.card(this)
        research.addView(Ui.title(this, "Session logs · Developer"))
        research.addView(option("Save a measurement log for each session (History > share)", settings.saveLogs) { settings.saveLogs = it })
        body.addView(research, Ui.blockParams(this))

        // MODELS: built-in models, or files exported on the laptop.
        val modelCard = Ui.card(this)
        modelCard.addView(Ui.title(this, "Models · Developer"))
        modelCard.addView(models)
        modelCard.addView(action("Import YOLO model (.tflite)") { pick(ModelFiles.YOLO) })
        modelCard.addView(action("Import YOLO class names (.txt, one per line)") { pick(ModelFiles.YOLO_LABELS) })
        modelCard.addView(action("Import eye-state model (.tflite, YOLO-cls on MRL Eye)") { pick(ModelFiles.EYE) })
        modelCard.addView(action("Import eye-state class names (.txt)") { pick(ModelFiles.EYE_LABELS) })
        modelCard.addView(action("Import fusion model (.json from train_fusion.py)") { pick(ModelFiles.FUSION) })
        modelCard.addView(action("Import tuned config (.json from export_android_config.py)") { pick(ModelFiles.CONFIG) })
        modelCard.addView(action("Remove imported files (use the built-in ones)") {
            for (n in listOf(ModelFiles.YOLO, ModelFiles.YOLO_LABELS, ModelFiles.CONFIG, ModelFiles.FUSION,
                    ModelFiles.EYE, ModelFiles.EYE_LABELS)) File(filesDir, n).delete()
            refreshModels()
            android.widget.Toast.makeText(this, "Imported files removed.", android.widget.Toast.LENGTH_SHORT).show()
        })
        modelCard.addView(Ui.note(this, "Run YOLO every N frames (1 = every frame; 2-3 = faster on slow phones)"))
        yoloEvery.inputType = InputType.TYPE_CLASS_NUMBER
        modelCard.addView(yoloEvery)
        autoSave(yoloEvery) {
            val every = it.toInt()
            require(every in 1..10) { "Use 1-10" }
            settings.yoloEvery = every
        }
        body.addView(modelCard, Ui.blockParams(this))
        refreshModels()

        // ADVANCED: Wi-Fi provisioning and a future polling preference.
        val advanced = Ui.card(this)
        advanced.addView(Ui.title(this, "Advanced · Developer"))
        advanced.addView(Ui.note(this, "Camera Wi-Fi provisioning: only for camera firmware with /api/status and " +
                "/api/config. The repository's firmware (07_esp32cam_prototype) does not have them, and the " +
                "camera must stay on DMS-NET for the sensor board and the phone."))
        testAction(advanced, "Check camera status (/api/status)") { checkStatus(it) }
        advanced.addView(Ui.note(this, "Camera Wi-Fi name"))
        ssid = field("Camera Wi-Fi name",
            prefs.getString("camera_ssid", "DMS-CAM") ?: "DMS-CAM")
        advanced.addView(ssid)
        autoSave(ssid) {
            val value = it.trim()
            require(Regex("[A-Za-z0-9_-]{3,32}").matches(value)) { "Use 3–32 letters, digits, - or _" }
            prefs.edit().putString("camera_ssid", value).apply()
        }

        advanced.addView(Ui.note(this, "New Wi-Fi password (8–63 characters)"))
        password = field("New Wi-Fi password (8–63 characters)", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        advanced.addView(password)

        // Do not save the new Wi-Fi password in SharedPreferences
        testAction(advanced, "Send Wi-Fi credentials") { provisionCamera(it) }

        advanced.addView(Ui.note(this, "Sensor polling interval (ms)"))
        pollMs = field("sensor polling interval (ms)", prefs.getInt("poll_ms", 1000).toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        advanced.addView(pollMs)

        autoSave(pollMs) {
            val interval = it.toInt()
            require(interval in 200..60000) { "Use 200–60000 ms" }
            prefs.edit().putInt("poll_ms", interval).apply()
        }
        body.addView(advanced, Ui.blockParams(this))

        applyReadOnlyState()
    }

    /** Recheck access and monitoring state whenever Settings returns to the foreground. */
    override fun onResume() {
        super.onResume()
        if (::readOnlyNote.isInitialized) applyReadOnlyState()
    }

    /** Disable edits and tests while monitoring */
    private fun applyReadOnlyState() {
        val monitoring = DriverMonitoringService.active
        controls.forEach { it.isEnabled = !monitoring }
        readOnlyNote.text = if (monitoring)
            "Monitoring is running: stop it to change these settings or run tests."
        else ""
    }

    /** single-line field */
    private fun field(hintText: String, initial: String) = EditText(this).apply {
        hint = hintText
        setSingleLine(true)
        setText(initial)
        textSize = 15f
        setSelectAllOnFocus(true)
        if (Ui.nightMode) {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }
        setPadding(Ui.dp(this@SettingsActivity, 8), Ui.dp(this@SettingsActivity, 10),
            Ui.dp(this@SettingsActivity, 8), Ui.dp(this@SettingsActivity, 10))
        controls.add(this)
    }

    /** autosave where incomplete URLs remain in field until corrected */
    private fun autoSave(input: EditText, save: (String) -> Unit) {
        fun persist() {
            try { save(input.text.toString()); input.error = null }
            catch (e: Exception) { if (!input.hasFocus()) input.error = e.message ?: "Invalid setting" }
        }

        // Save valid changes on typing and validate again when focus leaves the field.
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = persist()
        })
        input.setOnFocusChangeListener { _, focused -> if (!focused) persist() }
    }

    /** register test button so read-only mode can disable it. */
    private fun action(label: String, callback: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { callback() }
        controls.add(this)
    }

    /** Result badge and explanation */
    private fun testAction(parent: LinearLayout, label: String, callback: (TestViews) -> Unit) {
        // Badge is independent of the detailed result text below the row.
        val indicator = TextView(this).apply {
            text = "○ Not run"
            textSize = 12f
            setTextColor(if (Ui.nightMode) Color.LTGRAY else Ui.muted)
            setPadding(Ui.dp(this@SettingsActivity, 6), 0, 0, 0)
        }
        val detail = Ui.note(this, "")
        val result = TestViews(indicator, detail)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(action(label) { callback(result) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(indicator, LinearLayout.LayoutParams(Ui.dp(this, 86), -2))
        parent.addView(row)
        parent.addView(detail)
    }

    /** sync text, color, accessibility description */
    private fun show(result: TestViews, message: String, success: Boolean? = null) {
        result.indicator.text = when (success) {
            true -> "● Passed"
            false -> "● Failed"
            null -> "○ Checking"
        }
        result.indicator.setTextColor(when (success) {
            true -> Ui.green
            false -> Ui.red
            null -> if (Ui.nightMode) Color.LTGRAY else Ui.muted
        })
        result.detail.text = message
        result.indicator.contentDescription = "${result.indicator.text}. $message"
    }

    /** Accept only HTTP(S) base addresses, without paths or embedded credentials. */
    private fun normalizedBase(text: String, optional: Boolean = false): String {
        val value = text.trim().trimEnd('/')
        if (optional && value.isEmpty()) return ""
        val url = URL(value)
        require(url.protocol == "http" || url.protocol == "https") { "Address must start with http:// or https://" }
        require(url.host.isNotBlank() && url.userInfo == null) { "Enter a valid URL without credentials" }
        require(url.path.isEmpty() || url.path == "/") { "Enter only the base URL, for example http://192.168.4.1" }
        require(url.query == null && url.ref == null) { "Remove URL queries and fragments" }
        return value
    }

    /** Run network operations off the UI thread and report errors on the UI thread. */
    private fun onWorker(result: TestViews, label: String, operation: () -> Unit) {
        show(result, "$label …")
        Thread {
            try { operation() }
            catch (e: Exception) {
                runOnUiThread { show(result, "$label failed. ${e.message}", false) }
            }
        }.start()
    }

    /**
     * Find the boards: the sensor board is the Wi-Fi gateway on DMS-NET (192.168.4.1, JSON at /data),
     * the camera has the fixed address .200 on the same network (picture at /jpg). Other test boards
     * at the gateway (/api/status, /sensors) are recognised too.
     */
    private fun discoverBoard(result: TestViews) {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val gateways = manager.allNetworks.filter { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.mapNotNull { network ->
            manager.getLinkProperties(network)?.routes?.firstOrNull {
                it.isDefaultRoute && it.gateway is Inet4Address
            }?.gateway?.hostAddress
        }.distinct()
        if (gateways.isEmpty()) {
            show(result, "Connect to DMS-NET and try again.", false)
            return
        }

        onWorker(result, "Finding the boards") {
            var camera: String? = null
            var sensor: String? = null
            for (ip in gateways) {
                val base = "http://$ip"
                val camHost = ip.substringBeforeLast('.') + ".200"
                if (camera == null) for (candidate in listOf("http://$camHost", base)) {
                    if (isJpeg("$candidate/jpg")) { camera = candidate; break }
                }
                if (sensor == null) for (path in listOf("/data", "/sensors")) {
                    val response = runCatching { JSONObject(requestText("$base$path")) }.getOrNull()
                    if (response != null && (response.has("fix") || response.has("lat"))) { sensor = base; break }
                }
            }
            runOnUiThread {
                camera?.let { cameraUrl.setText(it) }
                sensor?.let { sensorUrl.setText(it) }
                val message = when {
                    camera != null && sensor != null -> "Camera ($camera) and sensor board ($sensor) found."
                    camera != null -> "Camera found at $camera. Sensor board not found at the Wi-Fi gateway."
                    sensor != null -> "Sensor board found at $sensor. Camera not found at .200 or the gateway."
                    else -> "No board found. Check that both boards are on and the phone is on DMS-NET, or type the addresses."
                }
                show(result, message, camera != null && sensor != null)
                if ((camera == null) != (sensor == null)) {
                    result.indicator.text = "● Partial"
                    result.indicator.setTextColor(Ui.amber)
                }
            }
        }
    }

    private fun isJpeg(url: String): Boolean = try {
        val c = connect(url)
        try { c.responseCode == 200 && c.contentType?.startsWith("image/jpeg") == true } finally { c.disconnect() }
    } catch (_: Exception) { false }

    /** Use actual Wi-Fi Network. */
    // Bind requests to Wi-Fi when available, even if mobile data is also connected.
    private fun connect(url: String, method: String = "GET", form: String? = null): HttpURLConnection {
        val target = URL(url)
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val connection = (if (wifi != null) wifi.openConnection(target) else target.openConnection()) as HttpURLConnection
        connection.connectTimeout = 4000
        connection.readTimeout = 4000
        connection.requestMethod = method
        connection.useCaches = false

        // Provisioning sends URL-encoded form fields in the request body.
        if (form != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        }
        return connection
    }

    /** Read a bounded response and always release the HTTP connection. */
    private fun requestText(url: String, method: String = "GET", form: String? = null): String {
        val connection = connect(url, method, form)
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val message = stream?.bufferedReader()?.use { it.readText().take(4000) } ?: "No response body"
            require(code in 200..299) { "HTTP $code, $message" }
            return message
        } finally { connection.disconnect() }
    }

    /** POST a form; returns the board's JSON answer also for refusals (403 wrong password, 400 bad password). */
    private fun postForm(url: String, form: String): Pair<Int, JSONObject> {
        val connection = connect(url, "POST", form)
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText().take(2000) } ?: ""
            return code to (runCatching { JSONObject(text) }.getOrNull() ?: JSONObject().put("error", "HTTP $code"))
        } finally { connection.disconnect() }
    }

    /** GET /wifi: does this board's firmware support changing the DMS-NET password? */
    private fun supportsPasswordChange(base: String): Boolean =
        runCatching { JSONObject(requestText("$base/wifi")).optBoolean("changeable", false) }.getOrDefault(false)

    /**
     * Change the DMS-NET password: check both boards first, then tell the camera (it keeps the old
     * password as a fallback), then the sensor board, which restarts DMS-NET with the new password.
     * The ESP32-S3 test board is camera and sensor board in one (same address): told once.
     */
    private fun changeDmsNetPassword(result: TestViews) {
        val current = wifiCurrent.text.toString()
        val next = wifiNew.text.toString()
        when {
            next != wifiConfirm.text.toString() -> { show(result, "The two new passwords are different.", false); return }
            next.length !in 8..63 || next.any { it.code !in 32..126 } ->
                { show(result, "Use 8-63 letters, digits or symbols (no accents or emoji).", false); return }
            next == current -> { show(result, "The new password is the same as the current one.", false); return }
        }
        val sensor = try { normalizedBase(sensorUrl.text.toString(), optional = true) }
        catch (e: Exception) { show(result, "Invalid sensor board address. ${e.message}", false); return }
        val camera = try { normalizedBase(cameraUrl.text.toString(), optional = true) }
        catch (e: Exception) { show(result, "Invalid camera address. ${e.message}", false); return }
        if (sensor.isEmpty()) { show(result, "Enter the sensor board address first: it creates DMS-NET.", false); return }
        val separateCamera = camera.isNotEmpty() && URL(camera).host != URL(sensor).host
        val form = "old=" + URLEncoder.encode(current, "UTF-8") + "&new=" + URLEncoder.encode(next, "UTF-8")
        onWorker(result, "Changing the password") {
            // 1. Both boards reachable, with firmware that can change the password (nothing changed yet).
            if (!supportsPasswordChange(sensor)) error("the sensor board did not answer, or its firmware is older than this " +
                    "feature (flash the new 06_devkit_wifi.ino)")
            if (separateCamera && !supportsPasswordChange(camera)) error("the camera did not answer (turn it on first, so it " +
                    "learns the new password), or its firmware is older (flash the new 07_esp32cam_prototype.ino)")
            // 2. Camera first: it rejoins with the new password, and falls back to the old one if step 3 fails.
            if (separateCamera) {
                val (code, answer) = postForm("$camera/wifi", form)
                if (code != 200 || !answer.optBoolean("ok")) error("camera: ${answer.optString("error", "HTTP $code")}")
            }
            // 3. Sensor board: saves it and restarts DMS-NET a moment later.
            val (code, answer) = postForm("$sensor/wifi", form)
            if (code != 200 || !answer.optBoolean("ok")) {
                error("sensor board: ${answer.optString("error", "HTTP $code")}" +
                        if (separateCamera) ". DMS-NET keeps the old password; the camera goes back to it by itself." else "")
            }
            runOnUiThread {
                settings.dmsNetPassword = next
                wifiCurrent.setText(next); wifiNew.setText(""); wifiConfirm.setText("")
                show(result, "Password changed. DMS-NET restarts in about 2 seconds with the new password.", true)
                wifiHelp.removeAllViews()
                wifiHelp.addView(Ui.note(this, "Now on this phone: Wi-Fi settings > DMS-NET > Forget, then join DMS-NET " +
                        "again with the new password (and choose Stay connected). Do the same on the other phones and the laptop. " +
                        "The camera rejoins by itself."))
                wifiHelp.addView(action("Open Wi-Fi settings") { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) })
            }
        }
    }

    /** Validate and submit new camera access-point credentials to the ESP32. */
    private fun provisionCamera(result: TestViews) {
        val newSsid = ssid.text.toString().trim()
        val newPassword = password.text.toString()

        if (!Regex("[A-Za-z0-9_-]{3,32}").matches(newSsid)) {
            show(result, "Wi-Fi name must contain 3–32 letters, numbers, hyphens or underscores.", false); return
        }
        if (newPassword.length !in 8..63 || newPassword.any { it.code !in 32..126 }) {
            show(result, "Wi-Fi password must have 8–63 characters.", false); return
        }
        val base = try { normalizedBase(cameraUrl.text.toString()) }
        catch (e: Exception) { show(result, "Invalid camera URL. ${e.message}", false); return }

        // Never send the password until both fields and the camera URL are valid.
        onWorker(result, "Provisioning camera") {
            val form = "ssid=" + URLEncoder.encode(newSsid, "UTF-8") +
                    "&password=" + URLEncoder.encode(newPassword, "UTF-8")
            val response = JSONObject(requestText("$base/api/config", "POST", form))
            require(response.optBoolean("ok", false)) { "ESP32 did not confirm the configuration" }
            runOnUiThread {
                password.setText("")
                prefs.edit().putString("camera_ssid", newSsid).apply()
                show(result, "Saved on ESP32. It will reboot. Join '$newSsid' using the password you just chose.", true)
            }
        }
    }

    /** Check the camera initialization state reported by /api/status. */
    private fun checkStatus(result: TestViews) {
        val base = try { normalizedBase(cameraUrl.text.toString()) }
        catch (e: Exception) { show(result, "Invalid camera URL. ${e.message}", false); return }
        onWorker(result, "Checking camera") {
            val response = JSONObject(requestText("$base/api/status"))
            runOnUiThread {
                val ready = response.optBoolean("cameraReady", false)
                show(result, "Camera mode ${response.optString("mode", "?")}, Wi-Fi ${response.optString("ssid", "?")}, IP ${response.optString("ip", "?")}, camera initialized $ready", ready)
            }
        }
    }

    /** Clear an old image before requesting and decoding a fresh JPEG. */
    private fun testSnapshot(result: TestViews) {
        snapshot.setImageDrawable(null)
        val base = try { normalizedBase(cameraUrl.text.toString()) }
        catch (e: Exception) { show(result, "Invalid camera URL. ${e.message}", false); return }
        onWorker(result, "Testing camera photo") {
            val connection = connect("$base/jpg")
            try {
                require(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
                require(connection.contentType?.startsWith("image/jpeg") == true) { "Not a JPEG response" }
                val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) }
                require(bitmap != null) { "Could not decode camera JPEG" }
                runOnUiThread {
                    snapshot.setImageBitmap(bitmap)
                    show(result, "Camera photo received: ${bitmap.width} × ${bitmap.height}.", true)
                }
            } finally { connection.disconnect() }
        }
    }

    /** Show the values actually returned by the sensor board (/data, or /sensors on older test boards). */
    private fun testSensors(result: TestViews) {
        val base = try { normalizedBase(sensorUrl.text.toString(), optional = true) }
        catch (e: Exception) { show(result, "Invalid sensor URL. ${e.message}", false); return }
        if (base.isEmpty()) { show(result, "Find the boards or enter a sensor URL first.", false); return }
        onWorker(result, "Testing sensors") {
            val json = runCatching { JSONObject(requestText("$base/data")) }.getOrNull()
                ?: JSONObject(requestText("$base/sensors"))
            runOnUiThread {
                show(result, "GPS fix ${json.optBoolean("fix", false)}, ${json.optInt("sats")} satellites, " +
                        "speed ${json.optDouble("kmh", 0.0)} km/h, lat ${json.optDouble("lat", 0.0)}, lng ${json.optDouble("lng", 0.0)}, " +
                        "motion ${json.optDouble("motion_g", json.optDouble("jolt", 0.0))} g, devices on DMS-NET ${json.optInt("clients")}.", true)
            }
        }
    }

    /** Sound the buzzer HIGH for 1.5 s, then off (firmware 06: /tier?level=0..3). */
    private fun testBuzzer(result: TestViews) {
        val base = try { normalizedBase(sensorUrl.text.toString(), optional = true) }
        catch (e: Exception) { show(result, "Invalid sensor URL. ${e.message}", false); return }
        if (base.isEmpty()) { show(result, "Find the boards or enter a sensor URL first.", false); return }
        onWorker(result, "Testing buzzer") {
            requestText("$base/tier?level=3")
            Thread.sleep(1500)
            requestText("$base/tier?level=0")
            runOnUiThread { show(result, "Buzzer test sent (HIGH for 1.5 s, then off).", true) }
        }
    }

    /** Read the video stream for 5 s: frames per second and picture size (what monitoring receives). */
    private fun testStream(result: TestViews) {
        val base = try { normalizedBase(cameraUrl.text.toString()) }
        catch (e: Exception) { show(result, "Invalid camera URL. ${e.message}", false); return }
        val url = URL(base)
        val stream = "${url.protocol}://${url.host}:81/stream"
        onWorker(result, "Reading $stream") {
            val c = connect(stream)
            try {
                c.readTimeout = 5000
                require(c.responseCode == 200) { "HTTP ${c.responseCode}. Close any browser tab showing the stream." }
                val reader = MjpegReader(c.inputStream)
                val started = System.nanoTime()
                var frames = 0
                var size = ""
                while (System.nanoTime() - started < 5_000_000_000L) {
                    val jpeg = reader.next()
                    if (frames == 0) {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
                        size = "${o.outWidth} × ${o.outHeight}"
                    }
                    frames++
                }
                val fps = frames / ((System.nanoTime() - started) / 1e9)
                runOnUiThread {
                    show(result, String.format(java.util.Locale.US, "Video OK: %.1f frames per second, %s.", fps, size), fps >= 5)
                }
            } finally { c.disconnect() }
        }
    }

    private fun option(label: String, checked: Boolean, save: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        isChecked = checked
        if (Ui.nightMode) setTextColor(Color.WHITE)
        setOnCheckedChangeListener { _, value -> save(value) }
        controls.add(this)
    }

    // ------------------------------------------------------------------ import model files
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
                ModelFiles.EYE -> {
                    val buf = java.nio.ByteBuffer.allocateDirect(bytes.size).order(java.nio.ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
                    EyeAi(buf, ModelFiles.eyeLabels(this), ModelFiles.config(this).eyeModel).close()
                }
                ModelFiles.EYE_LABELS -> {
                    val names = bytes.toString(Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }
                    require(EyeCrop.closedIndex(names, ModelFiles.config(this).eyeModel.closedClassNames) != null) {
                        "no \"closed\" class in $names"
                    }
                }
            }
            File(filesDir, target).writeBytes(bytes)
            android.widget.Toast.makeText(this, "Imported $target (${bytes.size / 1024} KB).", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Throwable) {
            android.widget.Toast.makeText(this, "Import failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
        refreshModels()
    }

    private fun refreshModels() {
        models.text = ModelStatus.describe(this)
    }
}
