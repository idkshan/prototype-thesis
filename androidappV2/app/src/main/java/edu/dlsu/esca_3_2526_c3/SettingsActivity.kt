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

class SettingsActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("device_setup_v1", Context.MODE_PRIVATE) }

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

        // Reject direct launches when the hidden developer mode is off.
        if (!MainActivity.developerSessionEnabled) { finish(); return }

        // Monitoring state determines whether the configuration is read-only.
        DriverMonitoringService.restore(this)
        Ui.nightMode = getSharedPreferences("driver_display", MODE_PRIVATE).getBoolean("night", false)
        window.statusBarColor = Ui.navy
        window.navigationBarColor = Ui.navy

        // Fixed header and bottom navigation, with scrollable settings in between.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.canvas)
        }
        root.addView(Ui.header(this, "Developer Settings"))
        val scroller = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 16),
                Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 28))
        }
        scroller.addView(body)
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        // This note changes when monitoring starts or stops.
        readOnlyNote = Ui.note(this, "Monitoring inactive. Settings can be written to.")
        body.addView(readOnlyNote)

        // CONNECTION: URLs and hardware checks.
        val endpoints = Ui.card(this)
        endpoints.addView(Ui.title(this, "Connection"))
        endpoints.addView(Ui.note(this, "Camera base URL"))
        cameraUrl = field("Camera base URL",
            prefs.getString("camera_url", "http://192.168.4.1") ?: "http://192.168.4.1")
        endpoints.addView(cameraUrl)

        // Store a valid base URL as soon as it changes.
        autoSave(cameraUrl) { prefs.edit().putString("camera_url", normalizedBase(it)).apply() }

        endpoints.addView(Ui.note(this, "Sensor base URL"))
        sensorUrl = field("Sensor base URL", prefs.getString("sensor_url", "") ?: "")
        endpoints.addView(sensorUrl)
        autoSave(sensorUrl) {
            prefs.edit().putString("sensor_url", normalizedBase(it, optional = true)).apply()
        }

        // Every test displays its result next to the action that ran it.
        testAction(endpoints, "Find test board on Wi-Fi") { discoverBoard(it) }
        testAction(endpoints, "Check camera status") { checkStatus(it) }
        testAction(endpoints, "Test camera photo") { testSnapshot(it) }
        testAction(endpoints, "Test MPU6050") { testSensors(it) }

        // The photo area stays empty until a JPEG test succeeds.
        snapshot = ImageView(this).apply {
            adjustViewBounds = true
            minimumHeight = Ui.dp(this@SettingsActivity, 90)
            contentDescription = "Last test image"
        }
        endpoints.addView(snapshot, LinearLayout.LayoutParams(-1, -2))
        body.addView(endpoints, Ui.blockParams(this))

        // ALERTS
        val alerts = Ui.card(this)
        alerts.addView(Ui.title(this, "Alerts - empty for now"))
        body.addView(alerts, Ui.blockParams(this))

        // MODELS
        val models = Ui.card(this)
        models.addView(Ui.title(this, "Models - empty for now"))
        body.addView(models, Ui.blockParams(this))

        // ADVANCED: Wi-Fi provisioning and a future polling preference.
        val advanced = Ui.card(this)
        advanced.addView(Ui.title(this, "Advanced"))
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

        // Bottom navigation mirrors the driver screen
        val tabs = LinearLayout(this).apply {
            setBackgroundColor(Ui.surface)
            setPadding(Ui.dp(this@SettingsActivity, 12), Ui.dp(this@SettingsActivity, 8),
                Ui.dp(this@SettingsActivity, 12), Ui.dp(this@SettingsActivity, 8))
        }
        for ((label, destination) in listOf(
            "⌂  Home" to "home", "◷  History" to "history", "Account" to "account"
        )) {
            val tab = Ui.tab(this, label) { navigate(destination) }.apply { textSize = 13f }
            Ui.activeTab(tab, false)
            tabs.addView(tab, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        }
        val selected = Ui.tab(this, "⚙  Settings") { scroller.smoothScrollTo(0, 0) }.apply { textSize = 13f }
        Ui.activeTab(selected, true)
        tabs.addView(selected, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        root.addView(tabs)
        applyReadOnlyState()
    }

    /** Recheck access and monitoring state whenever Settings returns to the foreground. */
    override fun onResume() {
        super.onResume()
        if (!MainActivity.developerSessionEnabled) { finish(); return }
        if (::readOnlyNote.isInitialized) applyReadOnlyState()
    }

    /** Reuse MainActivity and request its destination tab */
    private fun navigate(destination: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra("driver_tab", destination)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    /** Disable edits and tests while monitoring */
    private fun applyReadOnlyState() {
        val monitoring = DriverMonitoringService.active
        controls.forEach { it.isEnabled = !monitoring }
        readOnlyNote.text = if (monitoring)
            "Monitoring is active. Settings are read-only until monitoring stops."
        else "Monitoring inactive. Settings can be written to."
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

    /** Discover the actual Wi-Fi gateway, then identify supported prototype HTTP endpoints. */
    private fun discoverBoard(result: TestViews) {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Inspect Wi-Fi default gateways; do not assume the prototype uses a fixed IP.
        val gateways = manager.allNetworks.filter { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.mapNotNull { network ->
            manager.getLinkProperties(network)?.routes?.firstOrNull {
                it.isDefaultRoute && it.gateway is Inet4Address
            }?.gateway?.hostAddress
        }.distinct()
        if (gateways.isEmpty()) {
            show(result, "Connect to the prototype Wi-Fi and try again.", false)
            return
        }

        onWorker(result, "Finding prototype") {
            var camera: String? = null
            var sensor: String? = null

            // Probe only endpoints the prototype already supports.
            for (ip in gateways) {
                val base = "http://$ip"
                if (camera == null) {
                    val response = runCatching { JSONObject(requestText("$base/api/status")) }.getOrNull()
                    if (response?.has("cameraReady") == true) camera = base
                }
                if (sensor == null) {
                    val response = runCatching { JSONObject(requestText("$base/sensors")) }.getOrNull()
                    if (response != null && (response.has("fix") || response.has("lat"))) sensor = base
                }
            }

            // Update fields only for endpoints that actually responded as expected.
            runOnUiThread {
                camera?.let { cameraUrl.setText(it) }
                sensor?.let { sensorUrl.setText(it) }
                val message = when {
                    camera != null && sensor != null -> "Camera and sensor verified at their discovered Wi-Fi address."
                    camera != null -> "Camera found. Sensor endpoint not detected at the Wi-Fi gateway."
                    sensor != null -> "Sensor found. Camera endpoint not detected at the Wi-Fi gateway."
                    else -> "No supported prototype endpoint found at the Wi-Fi gateway. Check the network or configure addresses manually."
                }
                show(result, message, camera != null && sensor != null)
                if ((camera == null) != (sensor == null)) {
                    result.indicator.text = "● Partial"
                    result.indicator.setTextColor(Ui.amber)
                }
            }
        }
    }

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

    /** Show the values actually returned by the sensor endpoint. */
    private fun testSensors(result: TestViews) {
        val base = try { normalizedBase(sensorUrl.text.toString(), optional = true) }
        catch (e: Exception) { show(result, "Invalid sensor URL. ${e.message}", false); return }
        if (base.isEmpty()) { show(result, "Find the ESP32 or enter a sensor URL first.", false); return }
        onWorker(result, "Testing sensors") {
            val json = JSONObject(requestText("$base/sensors"))
            runOnUiThread {
                show(result, "Sensor response. GPS fix ${json.optBoolean("fix", false)}, speed ${json.optDouble("kmh", 0.0)} km/h, lat ${json.optDouble("lat", 0.0)}, lng ${json.optDouble("lng", 0.0)}.", true)
            }
        }
    }
}
