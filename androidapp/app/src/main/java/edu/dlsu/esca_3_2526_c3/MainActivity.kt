package edu.dlsu.esca_3_2526_c3

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import edu.dlsu.esca_3_2526_c3.dms.AlertCommand
import edu.dlsu.esca_3_2526_c3.dms.CALIBRATING
import edu.dlsu.esca_3_2526_c3.monitor.DeviceSettings
import edu.dlsu.esca_3_2526_c3.monitor.LiveFrame
import edu.dlsu.esca_3_2526_c3.monitor.ModelFiles
import edu.dlsu.esca_3_2526_c3.monitor.MonitorSession
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Driver screen. Start monitoring: the ESP32-CAM picture goes through the same
 * detection pipeline as the desktop program (MediaPipe + YOLO -> rules -> fuzzy
 * risk -> alarm); the buzzer on the sensor board sounds; events go to Firebase.
 * Long-press a behaviour row to send a simulated test event (no camera needed).
 */
class MainActivity : Activity(), MonitorSession.Listener {
    private lateinit var cloud: DemoCloud
    private lateinit var settings: DeviceSettings
    private val main = Handler(Looper.getMainLooper())
    private var session: MonitorSession? = null
    private var sessionId = "DEMO-" + UUID.randomUUID().toString()
    private var sessionStarted = System.currentTimeMillis()
    private val recent = mutableListOf<Triple<String, String, String>>() // what, time, level
    private val simStatuses = linkedMapOf("Drowsiness" to "Normal", "Yawning" to "Normal", "Gaze deviation" to "Normal", "Phone usage" to "Normal")
    private var latest: LiveFrame? = null
    private var statusLine = "Not monitoring"
    private var lastPublishMs = 0L
    private val uiPending = AtomicBoolean(false)
    private var onHome = true

    private lateinit var page: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var homeTab: TextView
    private lateinit var historyTab: TextView

    // live views (home page)
    private var liveImage: ImageView? = null
    private var startButton: Button? = null
    private var statusText: TextView? = null
    private var calibrationText: TextView? = null
    private var aimText: TextView? = null
    private var riskChip: TextView? = null
    private var alarmText: TextView? = null
    private var alertsCard: LinearLayout? = null
    private val behaviorValues = mutableMapOf<String, TextView>()
    private val rawValues = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.navy
        window.navigationBarColor = Ui.navy
        settings = DeviceSettings(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.background)
        }
        root.addView(Ui.header(this, "Driver Dashboard"))
        scroller = ScrollView(this).apply {
            isFillViewport = true
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 24))
                page = this
            })
        }
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        val tabs = LinearLayout(this).apply {
            setBackgroundColor(Ui.white)
            setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 8))
        }
        homeTab = Ui.tab(this, "⌂  Home") { showHome() }
        historyTab = Ui.tab(this, "◷  History") { showHistory() }
        tabs.addView(homeTab, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        tabs.addView(historyTab, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        tabs.addView(Ui.tab(this, "⚙  Settings") {
            if (session != null) toast("Stop monitoring before changing settings.")
            else startActivity(Intent(this, SettingsActivity::class.java))
        }, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        root.addView(tabs)
        setContentView(root)
        cloud = DemoCloud(this)
        cloud.onChange = { if (!isFinishing && session == null) updateVisiblePage() }
        cloud.start()
        showHome()
    }

    override fun onDestroy() {
        stopMonitoring()
        cloud.stop()
        super.onDestroy()
    }

    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    private fun updateVisiblePage() { if (onHome) showHome() else showHistory() }

    // ------------------------------------------------------------------ monitoring
    private fun startMonitoring() {
        if (session != null) return
        sessionId = "DMS-" + UUID.randomUUID().toString()
        sessionStarted = System.currentTimeMillis()
        recent.clear()
        latest = null
        val s = MonitorSession(this, settings, this)
        session = s
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusLine = "Starting …"
        s.start()
        showHome()
    }

    private fun stopMonitoring() {
        val s = session ?: return
        session = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusLine = "Stopping …"
        Thread {
            val summary = s.stop()
            main.post {
                statusLine = if (summary != null) "Stopped. Session saved (${summary.optInt("frames")} frames) - see History." else "Stopped."
                if (!isFinishing) updateVisiblePage()
            }
        }.start()
        showHome()
    }

    override fun onFrame(frame: LiveFrame) {
        if (!uiPending.compareAndSet(false, true)) return // the screen is still busy with the previous frame
        main.post {
            uiPending.set(false)
            latest = frame
            if (onHome && session != null) renderLive(frame)
            maybePublish(frame)
        }
    }

    override fun onAlert(cmd: AlertCommand, pushed: Boolean) {
        main.post { handleAlert(cmd, pushed) }
    }

    override fun onStatus(text: String) {
        main.post {
            statusLine = text
            statusText?.text = text
        }
    }

    private fun behaviourName(key: String) = when (key) {
        "eye_closure" -> "Eye closure"; "yawning" -> "Yawning"; "gaze_deviation" -> "Gaze deviation"
        "phone_usage" -> "Phone usage"; "sudden_motion" -> "Sudden motion"; else -> key
    }

    private fun handleAlert(c: AlertCommand, pushed: Boolean) {
        val what = if (c.behaviors.isEmpty()) "Risk level" else c.behaviors.joinToString(", ") { behaviourName(it) }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        val severity = when (c.tier) { "HIGH" -> "High"; "MEDIUM" -> "Warning"; else -> "Low" }
        when (c.event) {
            "start", "escalate" -> {
                recent.add(0, Triple(what, time, c.tier))
                cloud.record(what, severity, sessionId, simulated = false, source = "device", push = pushed)
            }
            "unavailable" -> recent.add(0, Triple("Monitoring unavailable (no camera picture)", time, "OFF"))
        }
        while (recent.size > 20) recent.removeAt(recent.size - 1)
        if (onHome && c.event in setOf("start", "escalate", "unavailable")) renderAlerts()
    }

    private fun statusOf(level: String) = when (level) { "SEVERE" -> "High"; "MODERATE" -> "Warning"; else -> "Normal" }

    private fun maybePublish(f: LiveFrame) {
        val now = System.currentTimeMillis()
        if (now - lastPublishMs < 2000) return
        lastPublishMs = now
        val sev = f.out.severity
        val statuses = linkedMapOf(
            "Drowsiness" to statusOf(sev.drowsiness.level), "Yawning" to statusOf(sev.yawning.level),
            "Gaze deviation" to statusOf(sev.gazeDeviation.level), "Phone usage" to statusOf(sev.phoneUsage.level))
        val risk = when (f.out.risk.tier) { "HIGH" -> "High risk"; "MEDIUM" -> "Medium risk"; "LOW" -> "Low risk"; else -> "No risk" }
        val active = f.out.behavior.active
        val event = if (active.isNotEmpty()) active.joinToString(", ") { behaviourName(it) }
        else if (f.out.behavior.drowsy) f.out.behavior.drowsinessState.replace('_', ' ') else "Normal"
        val ctx = f.out.context
        cloud.publishLive(statuses, risk, event, sessionId, (now - sessionStarted) / 1000, ctx.speedKmh, ctx.speedSource,
            ctx.latitude, ctx.longitude, f.out.alertState)
    }

    // ------------------------------------------------------------------ manual Firebase test (long-press)
    private fun simulate(name: String) {
        val next = when (simStatuses[name] ?: "Normal") { "Normal" -> "Warning"; "Warning" -> "High"; else -> "Normal" }
        simStatuses[name] = next
        if (next != "Normal") {
            recent.add(0, Triple("$name · TEST", DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()), next.uppercase()))
            cloud.record(name, next, sessionId)
        }
        cloud.publish(simStatuses, sessionId, (System.currentTimeMillis() - sessionStarted) / 1000)
        toast("Simulated $name: $next (Firebase test event)")
        renderAlerts()
    }

    // ------------------------------------------------------------------ pages
    private fun selectTab(home: Boolean) {
        Ui.activeTab(homeTab, home)
        Ui.activeTab(historyTab, !home)
        page.removeAllViews()
        liveImage = null; startButton = null; statusText = null; calibrationText = null; aimText = null
        riskChip = null; alarmText = null; alertsCard = null
        behaviorValues.clear(); rawValues.clear()
    }

    private fun valueOf(row: LinearLayout) = row.getChildAt(1) as TextView

    private fun showHome() {
        onHome = true
        selectTab(true)

        val monitor = Ui.card(this)
        monitor.addView(Ui.eyebrow(this, "MONITORING"))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = Button(this).apply {
            isAllCaps = false
            text = if (session == null) "▶  Start monitoring" else "■  Stop monitoring"
            setOnClickListener { if (session == null) startMonitoring() else stopMonitoring() }
        }
        buttons.addView(startButton, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(Button(this).apply {
            isAllCaps = false
            text = "Recalibrate"
            setOnClickListener {
                val s = session
                if (s == null) toast("Start monitoring first.") else { s.recalibrate(); toast("Look straight at the road with a relaxed face for 5 s.") }
            }
        }, LinearLayout.LayoutParams(-2, -2))
        monitor.addView(buttons)
        statusText = Ui.note(this, statusLine)
        monitor.addView(statusText)
        liveImage = ImageView(this).apply {
            adjustViewBounds = true
            setBackgroundColor(Color.rgb(232, 239, 248))
            minimumHeight = Ui.dp(this@MainActivity, 160)
            contentDescription = "Live camera picture"
        }
        monitor.addView(liveImage, LinearLayout.LayoutParams(-1, -2))
        calibrationText = Ui.note(this, if (session == null) "Camera: ${settings.streamUrl}  ·  Sensors: ${settings.sensorUrl.ifEmpty { "off" }}" else "")
        monitor.addView(calibrationText)
        aimText = Ui.note(this, "")
        monitor.addView(aimText)
        page.addView(monitor, Ui.blockParams(this))

        val overview = Ui.card(this)
        overview.addView(Ui.eyebrow(this, "CURRENT SESSION"))
        overview.addView(Ui.title(this, "Risk Level"))
        riskChip = Ui.bigChip(this, "—", Ui.muted)
        overview.addView(riskChip)
        alarmText = Ui.note(this, "")
        overview.addView(alarmText)
        val trip = Ui.pair(this, "Trip duration", "${(System.currentTimeMillis() - sessionStarted) / 60000} min")
        rawValues["trip"] = valueOf(trip)
        overview.addView(trip)
        accountArea(overview)
        page.addView(overview, Ui.blockParams(this))

        val metrics = Ui.card(this)
        metrics.addView(Ui.title(this, "Your Metrics"))
        metrics.addView(Ui.eyebrow(this, "DETECTION STATUS  (long-press = send a Firebase test event)"))
        for (name in listOf("Drowsiness", "Yawning", "Gaze deviation", "Phone usage")) {
            val row = Ui.pair(this, name, "—")
            row.setOnLongClickListener { simulate(name); true }
            behaviorValues[name] = valueOf(row)
            metrics.addView(row)
        }
        metrics.addView(Ui.space(this, 10))
        metrics.addView(Ui.eyebrow(this, "RAW MEASUREMENTS"))
        for ((key, label) in listOf("ear" to "Eye openness (EAR)", "mar" to "Mouth openness (MAR)", "yaw" to "Head left/right (yaw)",
                "pitch" to "Head up/down (pitch)", "fusion" to "Fusion model (eyes closed · yawn)", "speed" to "Speed",
                "motion" to "Motion", "fps" to "Processing")) {
            val row = Ui.pair(this, label, "—")
            rawValues[key] = valueOf(row)
            metrics.addView(row)
        }
        page.addView(metrics, Ui.blockParams(this))

        alertsCard = Ui.card(this)
        page.addView(alertsCard, Ui.blockParams(this))
        renderAlerts()
        latest?.let { if (session != null) renderLive(it) }
    }

    private fun renderAlerts() {
        val card = alertsCard ?: return
        card.removeAllViews()
        card.addView(Ui.title(this, "Recent Alerts"))
        if (recent.isEmpty()) card.addView(Ui.note(this, "No alerts yet"))
        recent.take(6).forEach { (what, time, level) ->
            val color = when (level) { "HIGH" -> Ui.red; "MEDIUM", "WARNING" -> Ui.amber; "OFF" -> Ui.muted; else -> Ui.blue }
            card.addView(Ui.alert(this, what, time, level, color))
        }
    }

    private fun renderLive(f: LiveFrame) {
        liveImage?.setImageBitmap(f.image)
        val out = f.out
        val tier = out.risk.tier
        riskChip?.text = if (tier == "NONE") "NO RISK" else "$tier RISK"
        riskChip?.setTextColor(when (tier) { "HIGH" -> Ui.red; "MEDIUM", "LOW" -> Ui.amber; else -> Ui.green })
        alarmText?.text = when (out.alertState) {
            "ALERTING" -> "🔔 Alarm sounding: ${out.activeTier}"
            "CLEARED" -> "Alarm stopped - driver back to normal (risk still elevated by recent signs)"
            "UNAVAILABLE" -> "Monitoring unavailable: no camera picture"
            else -> "No alarm"
        } + "  ·  fatigue ${out.risk.fatigueLevel}"
        val b = out.behavior
        val sev = out.severity
        behaviorValues["Drowsiness"]?.text = "${statusOf(sev.drowsiness.level)} · ${b.drowsinessState.replace('_', ' ')}"
        behaviorValues["Yawning"]?.text = "${statusOf(sev.yawning.level)} · ${b.yawnsInWindow} in 5 min" + if (b.yawning) " (now)" else ""
        behaviorValues["Gaze deviation"]?.text = "${statusOf(sev.gazeDeviation.level)}" + if (b.gazeAwayS > 0) " · away %.1f s".format(b.gazeAwayS) else ""
        behaviorValues["Phone usage"]?.text = if (!f.yoloOn) "Off (no YOLO model)" else
            "${statusOf(sev.phoneUsage.level)}" + if (b.phoneS > 0) " · %.1f s".format(b.phoneS) else ""
        val feat = f.features
        fun fmt(v: Double?, pattern: String) = v?.let { pattern.format(it) } ?: "—"
        rawValues["ear"]?.text = fmt(feat.ear, "%.3f")
        rawValues["mar"]?.text = fmt(feat.mar, "%.3f")
        rawValues["yaw"]?.text = fmt(feat.yaw, "%.1f°")
        rawValues["pitch"]?.text = fmt(feat.pitch, "%.1f°")
        rawValues["fusion"]?.text = if (session?.fusion == null) "Off (no fusion model)" else
            "${fmt(feat.eyeClosedFusion, "%.2f")} · ${fmt(feat.yawnFusion, "%.2f")}"
        val ctx = out.context
        rawValues["speed"]?.text = (ctx.speedKmh?.let { "%.0f km/h (GPS)".format(it) } ?: "assumed moving (no GPS)")
        rawValues["motion"]?.text = ctx.motionG?.let { "%.2f g".format(it) } ?: "—"
        rawValues["fps"]?.text = "%.1f FPS · %.0f ms/frame".format(f.fps, f.totalMs)
        rawValues["trip"]?.text = "${(System.currentTimeMillis() - sessionStarted) / 60000} min"

        calibrationText?.text = when {
            out.calibrationStatus == CALIBRATING -> "Calibrating %.0f%%: look straight at the road with a relaxed face.%s"
                .format(100 * f.calibrationProgress, if (f.calibrationProblem.isNotEmpty()) "  Last try: ${f.calibrationProblem}" else "")
            f.warning.isNotEmpty() -> "⚠ ${f.warning}"
            else -> "Calibrated to this driver."
        }
        calibrationText?.setTextColor(if (f.warning.isNotEmpty()) Ui.red else Ui.muted)
        val aim = f.aim
        aimText?.text = if (aim?.offset == null) "" else
            "Camera angle %.0f° (limit %.0f°): %s  ·  left/right: %s  ·  up/down: %s".format(aim.offset, aim.limit,
                if (aim.ok) "OK" else "move the camera", aim.horizontal, aim.vertical)
        aimText?.setTextColor(if (aim?.ok == true) Ui.green else Ui.amber)
    }

    private fun accountArea(target: LinearLayout) {
        target.addView(Ui.note(this, cloud.connectionLabel))
        target.addView(Button(this).apply {
            text = if (cloud.signedIn) "Sign out  ·  ${cloud.email}" else "Sign in (Driver researcher account)"
            isAllCaps = false
            setOnClickListener { if (cloud.signedIn) cloud.signOut() else cloud.signInDialog("driver") }
        })
        if (cloud.signedIn && cloud.role != "driver") {
            target.addView(Ui.note(this, "Driver Firebase writes require a researcher account assigned the driver role."))
        }
    }

    // ------------------------------------------------------------------ history
    private fun sessionsRoot(): File = getExternalFilesDir("sessions") ?: File(filesDir, "sessions")

    private fun showHistory() {
        onHome = false
        selectTab(false)
        val guided = Ui.card(this)
        guided.addView(Ui.title(this, "Guided recording"))
        guided.addView(Ui.note(this, "Labelled test data for the evaluation: the app tells the participant what to do " +
            "(close your eyes, yawn, look away, phone …) and writes labels.csv itself."))
        guided.addView(Button(this).apply {
            text = "Start a guided recording"
            isAllCaps = false
            setOnClickListener {
                if (session != null) toast("Stop monitoring first (the camera serves one viewer).")
                else startActivity(Intent(this@MainActivity, GuidedRecordingActivity::class.java))
            }
        })
        page.addView(guided, Ui.blockParams(this))

        val history = Ui.card(this)
        history.addView(Ui.title(this, "Session History"))
        val dirs = sessionsRoot().listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (dirs.isEmpty()) history.addView(Ui.note(this, "No sessions yet. Start monitoring on the Home tab."))
        for (dir in dirs.take(15)) {
            val summary = try { JSONObject(File(dir, "summary.json").readText()) } catch (_: Exception) { null }
            val minutes = summary?.optDouble("video_duration_s", 0.0)?.div(60.0) ?: 0.0
            val episodes = summary?.optJSONObject("alert_episodes")
            val alerts = episodes?.let { e -> e.keys().asSequence().sumOf { e.optInt(it) } } ?: 0
            val labelled = if (File(dir, "labels.csv").isFile) " · labelled" else ""
            history.addView(Ui.session(this, dir.name.removePrefix("phone_"),
                (if (summary == null) "in progress / not closed" else "%.1f minutes".format(minutes)) + labelled, "$alerts alerts"))
            history.addView(Button(this).apply {
                text = "Share log (frames.jsonl, alerts.jsonl, summary.json)"
                isAllCaps = false
                setOnClickListener { shareSession(dir) }
            })
        }
        history.addView(Ui.note(this, "On the laptop: python scripts/evaluate_behaviors.py --session <unzipped folder> --labels <unzipped folder>/labels.csv"))
        page.addView(history, Ui.blockParams(this))

        val sync = Ui.card(this)
        sync.addView(Ui.title(this, "Supervisor Sync"))
        sync.addView(Button(this).apply {
            text = "Manually Sync Logs to Supervisor"
            isAllCaps = false
            setOnClickListener {
                if (cloud.role != "driver") cloud.toast("Sign in as a driver researcher to sync.")
                else { cloud.flush(); cloud.toast("Sync requested. Check pending count and Firebase connection.") }
            }
        })
        sync.addView(Ui.note(this, "Last successful event sync: ${cloud.lastSyncText()}"))
        sync.addView(Ui.note(this, "Events waiting to be sent: ${cloud.pendingCount}"))
        sync.addView(Ui.note(this, "Models: face built in · YOLO ${ModelFiles.source(this, ModelFiles.YOLO)} · fusion " +
            "${ModelFiles.source(this, ModelFiles.FUSION)} · config ${ModelFiles.source(this, ModelFiles.CONFIG)}"))
        accountArea(sync)
        page.addView(sync, Ui.blockParams(this))
    }

    private fun shareSession(dir: File) {
        try {
            val zip = File(cacheDir, dir.name + ".zip")
            ZipOutputStream(zip.outputStream()).use { zos ->
                dir.listFiles()?.forEach { f ->
                    zos.putNextEntry(ZipEntry(dir.name + "/" + f.name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
            val send = Intent(Intent.ACTION_SEND).setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Share session log"))
        } catch (e: Exception) {
            toast("Could not share: ${e.message}")
        }
    }
}
