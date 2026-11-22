package edu.dlsu.esca_3_2526_c3

import android.app.Activity
import android.content.Intent
import android.text.InputType
import android.content.res.ColorStateList
import android.os.Bundle
import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import androidx.core.content.ContextCompat
import android.view.Gravity
import android.view.View
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.firebase.auth.FirebaseAuth
import android.view.WindowManager
import androidx.core.content.FileProvider
import edu.dlsu.esca_3_2526_c3.dms.CALIBRATING
import edu.dlsu.esca_3_2526_c3.monitor.LiveFrame
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


/**
 * Driver screen. Start monitoring runs the detection program in DriverMonitoringService
 * (it continues with the screen off); this screen shows its live results.
 * Seven taps on the header: developer mode (settings, test alerts, guided recording, sync).
 */
class MainActivity : Activity() {
    companion object {
        /** Developer mode (7 taps on the header) is remembered until it is switched off the same way. */
        fun developerEnabled(context: android.content.Context): Boolean =
            context.getSharedPreferences("driver_display", android.content.Context.MODE_PRIVATE).getBoolean("developer", false)
    }

    // Firebase test connection and shared monitoring-service state.
    private lateinit var cloud: CloudSync
    private val statuses get() = DriverMonitoringService.statuses
    private val recent get() = DriverMonitoringService.recent
    private val sessionId get() = DriverMonitoringService.sessionId
    private val sessionStarted get() = DriverMonitoringService.sessionStarted

    // Track the current page and the saved display preference.
    private var onHome = true
    private var onDetails = false
    private var onAccount = false
    private var onSettings = false
    private var selectedHistorySession: String? = null
    private var developerMode = false
    // No default test coordinates. A manual location lasts only for this activity/session.
    private var manualCoordinates: Pair<Double, Double>? = null
    private var dashboardTaps = 0
    private var firstDashboardTap = 0L
    private var nightMode = false

    // The HIGH-risk animation must be cancelled before rebuilding the page.
    private var borderPulse: ValueAnimator? = null

    // References to the reusable screen containers and navigation tabs.
    private lateinit var root: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var page: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var homeTab: TextView
    private lateinit var historyTab: TextView
    private lateinit var accountTab: TextView
    private lateinit var settingsTab: TextView
    private var statusStrip: LinearLayout? = null
    private var cameraFrame: LinearLayout? = null

    // Poll the configured camera and sensor endpoints while the driver UI is visible (not while monitoring).
    private val devices by lazy { DeviceStatusMonitor(this) { refreshDeviceStatus() } }

    // Views updated with every processed frame (no page rebuild).
    private var riskDisplay: TextView? = null
    private var riskMessage: TextView? = null
    private var statusLine: TextView? = null
    private var calibrationLine: TextView? = null
    private var aimLine: TextView? = null
    private var liveImage: ImageView? = null
    private var calibrationBar: android.widget.ProgressBar? = null
    private var tripLine: TextView? = null
    private var shownState = ""
    private val behaviourValues = mutableMapOf<String, TextView>()
    private val rawValues = mutableMapOf<String, TextView>()
    private var lastFrameUi = 0L

    /** Set up UI and reconnect to an existing monitoring session. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        developerMode = developerEnabled(this)
        DriverMonitoringService.restore(this)
        nightMode = Ui.nightPreference(this)
        Ui.nightMode = nightMode

        // Fixed header, scrollable page content, and bottom navigation.
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.canvas)
        }
        val header = Ui.header(this, "Driver Dashboard").apply {
            setOnClickListener { onDashboardTap() }
        }
        root.addView(header)
        scroller = ScrollView(this).apply {
            isFillViewport = true
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    Ui.dp(this@MainActivity, 16),
                    Ui.dp(this@MainActivity, 16),
                    Ui.dp(this@MainActivity, 16),
                    Ui.dp(this@MainActivity, 24)
                )
                page = this
            })
        }
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))

        // Keep navigation visible while the page content scrolls.
        tabs = LinearLayout(this).apply {
            setBackgroundColor(Ui.surface)
            setPadding(
                Ui.dp(this@MainActivity, 12),
                Ui.dp(this@MainActivity, 8),
                Ui.dp(this@MainActivity, 12),
                Ui.dp(this@MainActivity, 8)
            )
        }

        homeTab = Ui.tab(this, "⌂  Home") { showHome() }.apply { textSize = 13f }
        historyTab = Ui.tab(this, "◷  History") { showHistory() }.apply { textSize = 13f }
        accountTab = Ui.tab(this, "Account") { showAccount() }.apply { textSize = 13f }
        settingsTab = Ui.tab(this, "⚙  Settings") { showSettings() }.apply { textSize = 13f }

        tabs.addView(homeTab, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        tabs.addView(historyTab, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        tabs.addView(settingsTab, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        root.addView(tabs)
        setContentView(root)
        Ui.fitSystemBars(this, root, header, tabs)

        // Refresh the current page when Firebase connection data changes.
        cloud = CloudSync(this)
        cloud.onChange = { if (!isFinishing) refreshForRole() }
        cloud.start()

        if (DriverMonitoringService.active) {
            ContextCompat.startForegroundService(this, Intent(this, DriverMonitoringService::class.java).setAction(DriverMonitoringService.START))
        }

        openTab(intent?.getStringExtra("driver_tab"))
        if (!DriverMonitoringService.active &&
            !getSharedPreferences("driver_setup_v1", MODE_PRIVATE).getBoolean("complete", false)) {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    /** Rotation keeps the current page (the activity is not recreated, see the manifest). */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::page.isInitialized) updateVisiblePage()
    }

    /** Settings' bottom tabs come back here with the requested tab. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openTab(intent.getStringExtra("driver_tab"))
    }

    private fun openTab(tab: String?) = when (tab) {
        "history" -> showHistory()
        "account" -> showAccount()
        "settings" -> showSettings()
        else -> showHome()
    }

    /** Existing sessions may change Firebase roles while this activity is visible. */
    private fun refreshForRole() {
        if (cloud.role == "supervisor") {
            startActivity(Intent(this, SupervisorActivity::class.java))
            finish()
        } else updateVisiblePage()
    }

    /** Refresh the page currently selected by the driver. */
    private fun updateVisiblePage() {
        if (onSettings) showSettings() else if (onAccount) showAccount() else if (selectedHistorySession != null)
            showSessionDetails(selectedHistorySession!!) else if (!onHome) showHistory()
        else if (onDetails) showDetails() else showHome()
    }

    /** Resume receiving service updates when the app becomes visible. */
    override fun onResume() {
        super.onResume()
        nightMode = Ui.nightPreference(this) // "auto" follows the time of day
        Ui.nightMode = nightMode
        if (::root.isInitialized) { root.setBackgroundColor(Ui.canvas); tabs.setBackgroundColor(Ui.surface) }
        // Account and Settings are not rebuilt by monitoring events (their device badges still update).
        DriverMonitoringService.onChange = {
            if (!isFinishing && !isDestroyed) { if (!onAccount && !onSettings) updateVisiblePage() else refreshDeviceStatus() }
        }
        DriverMonitoringService.onFrame = { frame -> if (!isFinishing && !isDestroyed) onLiveFrame(frame) }
        DriverMonitoringService.onStatusText = { text ->
            statusLine?.text = text
            if (!isFinishing && !isDestroyed && onHome && !onAccount && driverState() != shownState) updateVisiblePage()
        }

        // Account sign-in may have changed while another screen was open.
        cloud.stop()
        cloud = CloudSync(this).apply {
            onChange = { if (!isFinishing) refreshForRole() }
            start()
        }

        devices.start()
        if (::page.isInitialized) updateVisiblePage()
    }

    /** Stop UI callbacks while allowing the foreground service to continue. */
    override fun onPause() {
        devices.stop()
        DriverMonitoringService.onChange = null
        DriverMonitoringService.onFrame = null
        DriverMonitoringService.onStatusText = null
        super.onPause()
    }

    /** Clean up the border animation and Firebase listeners. */
    override fun onDestroy() {
        borderPulse?.cancel()
        cloud.stop()
        super.onDestroy()
    }

    /** Developer mode: cycle one behaviour Normal -> Warning -> High (simulated Firebase test event). */
    private fun trigger(name: String) {
        if (!DriverMonitoringService.active) {
            cloud.toast("Start monitoring first")
            return
        }

        val current = statuses[name] ?: "Normal"
        val next = when (current) {
            "Normal" -> "Warning"; "Warning" -> "High"; else -> "Normal"
        }
        DriverMonitoringService.updateSimulatedRisk(this, name, next)

        if (next != "Normal") {
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            val label = Ui.alertCause(name, simulated = true)
            // An escalation of the same ongoing manual test replaces its previous stage.
            val prior = if (current == "Warning") recent.indexOfFirst {
                it.first == label && it.third == "Warning"
            } else -1
            if (prior >= 0) recent[prior] = Triple(label, recent[prior].second, next)
            else recent.add(0, Triple(label, time, next))
            cloud.record(name, next, sessionId)
        }

        if (next == "High") {
            startService(Intent(this, DriverMonitoringService::class.java).setAction(DriverMonitoringService.HIGH_ALERT))
        }
        cloud.publish(statuses, sessionId, (System.currentTimeMillis() - sessionStarted) / 1000)
        updateVisiblePage()
    }

    /** Start or stop the foreground monitoring service */
    private fun controlSession() {
        val running = DriverMonitoringService.active
        if (!running && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 79)
        }
        val intent = Intent(this, DriverMonitoringService::class.java)
            .setAction(if (running) DriverMonitoringService.STOP else DriverMonitoringService.START)
        if (running && manualCoordinates != null) {
            manualCoordinates = null
            DriverMonitoringService.manualLocation = null
            cloud.clearLocation()
        }
        if (running) startService(intent) else ContextCompat.startForegroundService(this, intent)
    }

    private fun recalibrate() {
        startService(Intent(this, DriverMonitoringService::class.java).setAction(DriverMonitoringService.RECALIBRATE))
        cloud.toast("Recalibrating: look at the road normally for 5 seconds")
    }

    /** Seven header taps - toggle developer mode (settings and test controls). */
    private fun onDashboardTap() {
        val now = System.currentTimeMillis()
        if (dashboardTaps == 0 || now - firstDashboardTap > 4500L) {
            dashboardTaps = 0
            firstDashboardTap = now
        }
        if (++dashboardTaps == 7) {
            dashboardTaps = 0
            developerMode = !developerMode
            getSharedPreferences("driver_display", MODE_PRIVATE).edit().putBoolean("developer", developerMode).apply()
            if (!developerMode && manualCoordinates != null) {
                manualCoordinates = null
                DriverMonitoringService.manualLocation = null
                if (!DriverMonitoringService.active) cloud.clearLocation()
            }
            cloud.toast(if (developerMode) "Developer mode on" else "Developer mode off")
            updateVisiblePage()
        }
    }

    // ------------------------------------------------------------------ live state

    /** What the risk panel shows: STOPPED, STARTING, NO CAMERA, CALIBRATING, or the risk tier. */
    private fun driverState(): String {
        if (!DriverMonitoringService.active) return "STOPPED"
        if (DriverMonitoringService.simulatedStatuses) return when {
            statuses.values.any { it == "High" } -> "HIGH"
            statuses.values.any { it == "Warning" } -> "MEDIUM"
            else -> "NORMAL"
        }
        val f = DriverMonitoringService.latest ?: return "STARTING"
        if (System.currentTimeMillis() - DriverMonitoringService.latestAtMs > 3_000L || f.out.alertState == "UNAVAILABLE")
            return "NO CAMERA"
        if (f.out.calibrationStatus == CALIBRATING) return "CALIBRATING"
        return DriverMonitoringService.displayTier(f.out.risk.tier)
    }

    private fun stateColor(state: String) = when (state) {
        "HIGH", "MEDIUM", "LOW", "NORMAL" -> Ui.riskColor(state)
        "CALIBRATING" -> Ui.blue
        else -> Ui.muted
    }

    private fun stateText(state: String) = when (state) {
        "STOPPED" -> "MONITORING OFF"
        "STARTING" -> "STARTING …"
        "NO CAMERA" -> "NO CAMERA PICTURE"
        "CALIBRATING" -> "CALIBRATING"
        else -> "$state RISK".replace("NORMAL RISK", "NORMAL")
    }

    /** The behaviour behind the current alarm, for a short instruction to the driver. */
    private fun stateMessage(state: String): String {
        val f = DriverMonitoringService.latest
        val active = f?.out?.behavior?.active ?: emptyList()
        return when (state) {
            "STOPPED" -> "Driver monitoring is stopped"
            "STARTING" -> "Connecting to the camera …"
            "NO CAMERA" -> "Check the camera power and Wi-Fi (DMS-NET)"
            "CALIBRATING" -> "Look at the road normally for 5 seconds"
            "HIGH", "MEDIUM" -> when {
                "phone_usage" in active -> "Put the phone down"
                "gaze_deviation" in active -> "Eyes on the road"
                "eye_closure" in active || f?.out?.behavior?.drowsy == true -> "You look drowsy. Please take a break"
                "yawning" in active -> "You seem tired. Plan a break soon"
                state == "HIGH" -> "Please take a break"
                else -> "Stay alert"
            }
            "LOW" -> "Stay alert"
            else -> "Keep your eyes on the road"
        }
    }

    /** Called for processed frames (main thread); updates only the live views, at most 5 times a second. */
    private fun onLiveFrame(f: LiveFrame) {
        val now = System.currentTimeMillis()
        if (!onHome || onAccount || now - lastFrameUi < 200L) return
        lastFrameUi = now
        val state = driverState()
        if (state != shownState) { updateVisiblePage(); return }
        renderLive(f)
    }

    /** "Trip 23 min · 3 alerts · 40 km/h" while monitoring. */
    private fun tripText(): String {
        val minutes = (System.currentTimeMillis() - sessionStarted).coerceAtLeast(0L) / 60_000L
        val alerts = recent.count { it.third != "OFF" }
        val speed = DriverMonitoringService.latest?.out?.context?.speedKmh
            ?.takeIf { DriverMonitoringService.latest?.out?.context?.speedSource == "gps" }
        return "Trip $minutes min · $alerts alert${if (alerts == 1) "" else "s"}" +
                (speed?.let { String.format(Locale.US, " · %.0f km/h", it) } ?: "")
    }

    /** Home, not monitoring: the most recent session, or how to begin. */
    private fun showLastSession() {
        val card = Ui.card(this)
        val last = SessionHistoryStore.list(this).firstOrNull { !it.active }
        if (last == null) {
            card.addView(Ui.title(this, "Getting started"))
            card.addView(Ui.note(this, "1. Turn on the camera and the sensor board.\n2. Join DMS-NET on this phone.\n" +
                    "3. Mount the phone, press Start monitoring and look at the road for 5 seconds.\n" +
                    "Settings > Setup guide shows each step again."))
        } else {
            val high = last.alerts.count { Ui.riskLevel(it.severity) == "HIGH" }
            card.addView(Ui.eyebrow(this, "LAST SESSION"))
            card.addView(Ui.linkRow(this, sessionDate(last.startedMs),
                "${sessionMinutes(last.durationMs())} · ${last.alerts.size} alerts" + (if (high > 0) " ($high HIGH)" else "")) {
                showSessionDetails(last.id)
            })
        }
        page.addView(card, Ui.blockParams(this))
    }

    private fun fmt(v: Double?, pattern: String) = v?.let { String.format(Locale.US, pattern, it) } ?: "—"

    private fun renderLive(f: LiveFrame?) {
        riskMessage?.text = stateMessage(shownState)
        statusLine?.text = DriverMonitoringService.statusText
        liveImage?.setImageBitmap(f?.image)
        if (f == null) return
        val out = f.out
        calibrationLine?.text = when {
            out.calibrationStatus == CALIBRATING -> String.format(Locale.US,
                "Calibrating %.0f%%: look straight at the road with a relaxed face.%s", 100 * f.calibrationProgress,
                if (f.calibrationProblem.isNotEmpty()) "  Last try: ${f.calibrationProblem}" else "")
            f.warning.isNotEmpty() -> "⚠ ${f.warning}"
            else -> "Calibrated to this driver."
        }
        calibrationLine?.setTextColor(if (f.warning.isNotEmpty()) Ui.red else Ui.muted)
        calibrationBar?.apply {
            visibility = if (out.calibrationStatus == CALIBRATING) View.VISIBLE else View.GONE
            progress = (100 * f.calibrationProgress).toInt().coerceIn(0, 100)
        }
        tripLine?.text = tripText()
        val aim = f.aim
        aimLine?.text = if (aim?.offset == null) "" else String.format(Locale.US,
            "Camera angle %.0f° (limit %.0f°): %s · left/right: %s · up/down: %s", aim.offset, aim.limit,
            if (aim.ok) "OK" else "move the camera", aim.horizontal, aim.vertical)
        aimLine?.setTextColor(if (aim?.ok == true) Ui.green else Ui.amber)

        val b = out.behavior
        val sev = out.severity
        val sim = DriverMonitoringService.simulatedStatuses
        fun level(name: String, measured: String) = if (sim) Ui.riskLevel(statuses[name] ?: "Normal") + " · test" else measured
        behaviourValues["Drowsiness"]?.text = level("Drowsiness",
            "${Ui.riskLevel(DriverMonitoringService.statusOf(sev.drowsiness.level))} · ${b.drowsinessState.replace('_', ' ')}")
        behaviourValues["Yawning"]?.text = level("Yawning",
            "${Ui.riskLevel(DriverMonitoringService.statusOf(sev.yawning.level))} · ${b.yawnsInWindow} in 5 min" + if (b.yawning) " (now)" else "")
        behaviourValues["Gaze deviation"]?.text = level("Gaze deviation",
            Ui.riskLevel(DriverMonitoringService.statusOf(sev.gazeDeviation.level)) +
                    if (b.gazeAwayS > 0) String.format(Locale.US, " · away %.1f s", b.gazeAwayS) else "")
        behaviourValues["Phone usage"]?.text = level("Phone usage", if (!f.yoloOn) "Off (no YOLO model)" else
            Ui.riskLevel(DriverMonitoringService.statusOf(sev.phoneUsage.level)) +
                    if (b.phoneS > 0) String.format(Locale.US, " · %.1f s", b.phoneS) else "")

        val feat = f.features
        rawValues["ear"]?.text = fmt(feat.ear, "%.3f")
        rawValues["mar"]?.text = fmt(feat.mar, "%.3f")
        rawValues["yaw"]?.text = fmt(feat.yaw, "%.1f°")
        rawValues["pitch"]?.text = fmt(feat.pitch, "%.1f°")
        rawValues["fusion"]?.text = if (feat.eyeClosedFusion == null && feat.yawnFusion == null) "Off (no fusion model)" else
            "${fmt(feat.eyeClosedFusion, "%.2f")} · ${fmt(feat.yawnFusion, "%.2f")}"
        rawValues["eyeClassifier"]?.text = if (feat.eyeClosedProb == null) "Off (no eye-state model)" else fmt(feat.eyeClosedProb, "P(closed) %.2f")
        fun sources(m: Map<String, Double>, fused: Double?) =
            if (m.isEmpty()) "—" else m.entries.joinToString(" · ") { String.format(Locale.US, "%s %.2f", it.key, it.value) } +
                    (fused?.let { String.format(Locale.US, " → %.2f", it) } ?: "")
        rawValues["eyeEvidence"]?.text = sources(out.states.eyeSources, out.states.eyeEvidence)
        rawValues["mouthEvidence"]?.text = sources(out.states.mouthSources, out.states.mouthEvidence)
        rawValues["risk"]?.text = String.format(Locale.US, "%.0f / 100 (%s) · fatigue %s", out.risk.risk, out.risk.tier, out.risk.fatigueLevel)
        rawValues["inputs"]?.text = String.format(Locale.US, "fatigue %.2f · distraction %.2f · context %.2f",
            out.risk.fatigue, out.risk.distraction, out.risk.context)
        rawValues["alarm"]?.text = when (out.alertState) {
            "ALERTING" -> "Sounding: ${out.activeTier}"
            "CLEARED" -> "Stopped (risk still raised by recent signs)"
            "UNAVAILABLE" -> "Unavailable: no camera picture"
            else -> "No alarm"
        }
        val ctx = out.context
        rawValues["speed"]?.text = ctx.speedKmh?.let { String.format(Locale.US, "%.0f km/h (%s)", it, ctx.speedSource) } ?: "assumed moving (no GPS)"
        rawValues["motion"]?.text = fmt(ctx.motionG, "%.2f g")
        rawValues["fps"]?.text = String.format(Locale.US, "%.1f FPS · %.0f ms/frame", f.fps, f.totalMs)
        refreshDeviceStatus()
    }

    /** Reset page, flashing border */
    private fun selectTab(home: Boolean, account: Boolean = false, settings: Boolean = false) {
        onSettings = settings
        Ui.activeTab(homeTab, home)
        Ui.activeTab(historyTab, !home && !account && !settings)
        Ui.activeTab(accountTab, account) // Account is opened from Settings (not a tab of its own)
        Ui.activeTab(settingsTab, settings || account)
        borderPulse?.cancel()
        borderPulse = null
        root.foreground = null
        statusStrip = null
        cameraFrame = null
        riskDisplay = null; riskMessage = null; statusLine = null; calibrationLine = null; aimLine = null; liveImage = null
        calibrationBar = null; tripLine = null
        behaviourValues.clear(); rawValues.clear()
        shownState = driverState()
        window.apply {
            if (DriverMonitoringService.active && edu.dlsu.esca_3_2526_c3.monitor.DeviceSettings(this@MainActivity).keepScreenOn)
                addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Flash around both Driving Mode and Details while a HIGH alarm sounds (or a HIGH test).
        val alarmHigh = DriverMonitoringService.latest?.out?.activeTier == "HIGH" ||
                (DriverMonitoringService.simulatedStatuses && statuses.values.any { it == "High" })
        if (home && DriverMonitoringService.active && alarmHigh) {
            val border = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            root.foreground = border
            borderPulse = ValueAnimator.ofArgb(Color.TRANSPARENT, Ui.red).apply {
                duration = 550L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener {
                    border.setStroke(Ui.dp(this@MainActivity, 12), it.animatedValue as Int)
                }
                start()
            }
        }

        page.removeAllViews()
        scroller.scrollTo(0, 0)
    }

    /** Repaint only device indicators and camera snapshots without resetting the page. */
    private fun refreshDeviceStatus() {
        val monitoring = DriverMonitoringService.active
        val state = devices.state
        val f = DriverMonitoringService.latest
        val manual = manualCoordinates
        // While monitoring, the service owns the camera and the sensor board: show what it receives.
        val camera = if (monitoring) f != null && System.currentTimeMillis() - DriverMonitoringService.latestAtMs < 3_000L else state.camera
        val sensor = if (monitoring) f?.sensors != null else state.sensor
        val gps = if (monitoring) f?.out?.context?.latitude != null else state.gps
        statusStrip?.apply {
            removeAllViews()
            val sensorSet = edu.dlsu.esca_3_2526_c3.monitor.DeviceSettings(this@MainActivity).sensorUrl.isNotBlank()
            // "Checking" until the first test (or the first camera frame while monitoring) has come back.
            val waiting = if (monitoring) (if (f == null) "Connecting" else null) else (if (!state.checked) "Checking" else null)
            val gpsWaiting = if (monitoring && f != null) null else waiting
            val badges = listOf(
                Ui.deviceIndicator(this@MainActivity, "📷 Camera", camera, pending = waiting),
                Ui.deviceIndicator(this@MainActivity, "▣ Sensor board", sensor, notSet = !sensorSet, pending = waiting),
                if (manual != null && !gps) Ui.deviceIndicator(this@MainActivity, "⌖ Location", true, "Manual (test)")
                else Ui.deviceIndicator(this@MainActivity, "⌖ GPS", gps, "Location fix", "No fix", notSet = !sensorSet, pending = gpsWaiting),
                Ui.deviceIndicator(this@MainActivity, "☁ Supervisor link", cloud.ready, "Connected",
                    if (!cloud.signedIn) "Not signed in" else "Offline"),
            )
            badges.chunked(2).forEach { pair ->
                val row = LinearLayout(this@MainActivity)
                pair.forEach { badge ->
                    row.addView(badge, LinearLayout.LayoutParams(0, -1, 1f).apply {
                        setMargins(Ui.dp(this@MainActivity, 2), Ui.dp(this@MainActivity, 2),
                            Ui.dp(this@MainActivity, 2), Ui.dp(this@MainActivity, 2))
                    })
                }
                addView(row)
            }
        }
        // Not monitoring: show the latest test picture (live pictures use liveImage).
        if (!monitoring) cameraFrame?.apply {
            removeAllViews() // Immediately discard the last photo if the camera dropped.
            val photo = state.photo
            if (state.camera && photo != null) {
                addView(ImageView(this@MainActivity).apply {
                    setImageBitmap(photo)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "Latest camera photo"
                }, LinearLayout.LayoutParams(-1, Ui.dp(this@MainActivity, 160)))
            } else {
                addView(Ui.placeholder(this@MainActivity,
                    "NO CAMERA PICTURE", "Check the camera connection", 160))
            }
        }
    }

    private fun addConnectionStrip(target: LinearLayout) {
        target.addView(Ui.eyebrow(this, "DEVICE CONNECTIONS"))
        statusStrip = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        target.addView(statusStrip)
        refreshDeviceStatus()
    }

    private fun navyButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        if (nightMode) { backgroundTintList = ColorStateList.valueOf(Ui.navy); setTextColor(Color.WHITE) }
        setOnClickListener { click() }
    }

    /** Driving Mode */
    private fun showHome() {
        onHome = true
        onDetails = false
        onAccount = false
        selectTab(true)
        val overview = Ui.card(this)
        overview.addView(Ui.eyebrow(this, "DRIVING MODE"))

        val state = shownState
        val color = stateColor(state)

        // The solid-color panel makes the risk visible at a glance.
        val riskBackground = GradientDrawable().apply {
            setColor(color)
            cornerRadius = Ui.dp(this@MainActivity, 16).toFloat()
        }

        riskDisplay = TextView(this).apply {
            text = stateText(state)
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(if (state == "LOW") Color.BLACK else Color.WHITE)
            setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 27),
                Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 27))
            background = riskBackground
        }
        overview.addView(riskDisplay, LinearLayout.LayoutParams(-1, -2))
        overview.addView(Ui.space(this, 12))

        riskMessage = Ui.title(this, stateMessage(state))
        overview.addView(riskMessage)

        // Keep Start and Stop immediately below the risk message.
        overview.addView(Button(this).apply {
            text = if (DriverMonitoringService.active) "Stop monitoring" else "Start monitoring"
            isAllCaps = false
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            minimumHeight = Ui.dp(this@MainActivity, 68)
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(if (DriverMonitoringService.active) Ui.red else Ui.green)
            setOnClickListener { controlSession() }
        })
        statusLine = Ui.note(this, DriverMonitoringService.statusText)
        overview.addView(statusLine)
        if (DriverMonitoringService.active) {
            tripLine = Ui.title(this, tripText()).apply { textSize = 15f }
            overview.addView(tripLine)
            calibrationBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                visibility = if (state == "CALIBRATING") View.VISIBLE else View.GONE
                contentDescription = "Calibration progress"
            }
            overview.addView(calibrationBar, LinearLayout.LayoutParams(-1, Ui.dp(this, 14)))
            calibrationLine = Ui.note(this, "")
            overview.addView(calibrationLine)
            aimLine = Ui.note(this, "")
            overview.addView(aimLine)
        }
        overview.addView(Ui.space(this, 8))
        addConnectionStrip(overview)
        overview.addView(Ui.note(this, if (cloud.signedIn)
            "Signed in as ${cloud.email}" else "Not signed in · Open Account to sign in"))

        // Side-by-side shortcuts avoid another row of full-width buttons.
        val shortcuts = LinearLayout(this)
        shortcuts.addView(navyButton("View details") { showDetails() },
            LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        if (DriverMonitoringService.active) shortcuts.addView(navyButton("Recalibrate") { recalibrate() },
            LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        overview.addView(shortcuts)
        page.addView(overview, Ui.blockParams(this))
        if (!DriverMonitoringService.active) showLastSession()
        if (developerMode) showManualLocationControls()
        renderLive(DriverMonitoringService.latest.takeIf { DriverMonitoringService.active })
        liveImage = null
    }

    /** Optional driver-side test coordinates; blank until the researcher explicitly applies them. */
    private fun showManualLocationControls() {
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Manual GPS coordinates · Test"))
        card.addView(Ui.note(this, "Optional test location for the Supervisor map, used while the GPS has no fix."))
        val numberType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
        val latitude = EditText(this).apply {
            hint = "Latitude (-90 to 90)"
            inputType = numberType
            setSingleLine(true)
            textSize = 14f
            setText(manualCoordinates?.first?.toString() ?: "")
            if (nightMode) { setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY) }
        }
        val longitude = EditText(this).apply {
            hint = "Longitude (-180 to 180)"
            inputType = numberType
            setSingleLine(true)
            textSize = 14f
            setText(manualCoordinates?.second?.toString() ?: "")
            if (nightMode) { setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY) }
        }
        card.addView(latitude)
        card.addView(longitude)
        card.addView(Ui.note(this, if (manualCoordinates == null)
            "Manual location not set" else "Manual override active · Not measured by GPS"))
        val actions = LinearLayout(this)
        actions.addView(Button(this).apply {
            text = "Apply coordinates"
            isAllCaps = false
            setOnClickListener {
                val lat = latitude.text.toString().trim().toDoubleOrNull()
                val lng = longitude.text.toString().trim().toDoubleOrNull()
                if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite() ||
                    lat !in -90.0..90.0 || lng !in -180.0..180.0 || (lat == 0.0 && lng == 0.0)) {
                    cloud.toast("Enter a valid latitude and longitude (not 0, 0).")
                    return@setOnClickListener
                }
                manualCoordinates = lat to lng
                DriverMonitoringService.manualLocation = lat to lng
                cloud.toast(if (DriverMonitoringService.active) "Manual test location applied"
                else "Saved for this app session. Start monitoring to share it.")
                showHome()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply {
            text = "Clear"
            isAllCaps = false
            setOnClickListener {
                manualCoordinates = null
                DriverMonitoringService.manualLocation = null
                if (!DriverMonitoringService.active) cloud.clearLocation()
                cloud.toast("Manual test location cleared")
                showHome()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(actions)
        page.addView(card, Ui.blockParams(this))
    }

    /** Live picture, behaviour levels, alerts; raw measurements and test controls in developer mode. */
    private fun showDetails() {
        onHome = true
        onDetails = true
        onAccount = false
        selectTab(true)
        val metrics = Ui.card(this)
        metrics.addView(Ui.title(this, "Detection Details"))
        addConnectionStrip(metrics)
        metrics.addView(Ui.space(this, 8))
        if (DriverMonitoringService.active) {
            liveImage = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                contentDescription = "Live camera picture with the detected face points"
            }
            metrics.addView(liveImage, LinearLayout.LayoutParams(-1, Ui.dp(this, 220)))
            statusLine = Ui.note(this, DriverMonitoringService.statusText)
            metrics.addView(statusLine)
        } else {
            cameraFrame = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            metrics.addView(cameraFrame)
            refreshDeviceStatus()
        }
        metrics.addView(Ui.pair(this, "Trip duration", if (DriverMonitoringService.active)
            "${(System.currentTimeMillis() - sessionStarted).coerceAtLeast(0L) / 60000} min" else "Not monitoring"))

        metrics.addView(Ui.space(this, 6))
        metrics.addView(Ui.eyebrow(this, if (developerMode) "BEHAVIOURS · TAP FOR A TEST EVENT" else "BEHAVIOURS"))
        for (name in listOf("Drowsiness", "Yawning", "Gaze deviation", "Phone usage")) {
            val row = Ui.pair(this, Ui.alertCause(name), if (DriverMonitoringService.active) "—" else "Not monitoring")
            behaviourValues[name] = row.getChildAt(1) as TextView
            if (developerMode) row.setOnClickListener { trigger(name) }
            metrics.addView(row)
        }

        // Raw measurements only in developer mode.
        if (developerMode) {
            metrics.addView(Ui.space(this, 10))
            metrics.addView(Ui.eyebrow(this, "RAW MEASUREMENTS"))
            for ((key, label) in listOf(
                "ear" to "Eye openness (EAR)", "mar" to "Mouth opening (MAR)", "yaw" to "Head turn (yaw)",
                "pitch" to "Head tilt (pitch)", "eyeClassifier" to "Eye-state classifier",
                "fusion" to "Fusion model: eyes closed · yawn", "eyeEvidence" to "Eyes-closed evidence",
                "mouthEvidence" to "Yawn evidence", "inputs" to "Fuzzy inputs", "risk" to "Fuzzy risk",
                "alarm" to "Alarm", "speed" to "Speed", "motion" to "Motion", "fps" to "Camera frames per second")) {
                val row = Ui.pair(this, label, if (DriverMonitoringService.active) "—" else "Not monitoring")
                rawValues[key] = row.getChildAt(1) as TextView
                metrics.addView(row)
            }
        }

        metrics.addView(navyButton("Back to Driving mode") { showHome() })
        page.addView(metrics, Ui.blockParams(this))

        val alerts = Ui.card(this)
        alerts.addView(Ui.title(this, "Recent Alerts"))
        if (recent.isEmpty()) alerts.addView(Ui.note(this,
            if (DriverMonitoringService.active) "No alerts in this session yet." else "Start monitoring to see alerts."))
        else recent.take(8).forEach { (behavior, time, severity) ->
            alerts.addView(Ui.alert(this, behavior, time, Ui.riskLevel(severity), Ui.riskColor(severity)))
        }
        page.addView(alerts, Ui.blockParams(this))
        renderLive(DriverMonitoringService.latest.takeIf { DriverMonitoringService.active })
    }

    /** Show recorded monitoring runs.
     * Sessions under 10 seconds are discarded. */
    private fun showHistory() {
        onHome = false
        onAccount = false
        selectedHistorySession = null
        selectTab(false)
        val history = Ui.card(this)
        history.addView(Ui.title(this, "Session History"))
        val waiting = cloud.pendingCount
        history.addView(Ui.note(this, if (waiting == 0) "✓ Everything is synced with the supervisor."
            else "⟳ $waiting item${if (waiting == 1) "" else "s"} waiting to be sent to the supervisor. ${cloud.connectionLabel}.").apply {
            setTextColor(if (waiting == 0) Ui.green else Ui.amber)
        })
        if (waiting > 0) history.addView(Button(this).apply {
            text = "Sync now"
            isAllCaps = false
            setOnClickListener {
                if (!cloud.signedIn || cloud.role != "driver") cloud.toast("Sign in with a driver account first (Settings > Account)")
                else { cloud.flush(); cloud.toast("Sending…") }
            }
        })
        val sessions = SessionHistoryStore.list(this)

        if (sessions.isEmpty()) history.addView(Ui.note(this, "No saved sessions yet. Start monitoring to record a session."))
        else sessions.forEach { session ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(this@MainActivity, 10), 0, Ui.dp(this@MainActivity, 10))
                setOnClickListener { showSessionDetails(session.id) }
                contentDescription = "${sessionDate(session.startedMs)}, ${sessionMinutes(session.durationMs())}, ${session.alerts.size} alerts. View details"
            }
            val summary = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            summary.addView(TextView(this).apply {
                text = sessionDate(session.startedMs)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (nightMode) Color.WHITE else Ui.navy)
            })
            summary.addView(Ui.note(this, sessionMinutes(session.durationMs()) +
                    if (session.active && DriverMonitoringService.active && DriverMonitoringService.sessionId == session.id)
                        " · In progress" else ""))
            row.addView(summary, LinearLayout.LayoutParams(0, -2, 1f))
            summary.addView(syncLabel(session))
            row.addView(Ui.chip(this, "${session.alerts.size} alerts", when {
                session.alerts.any { Ui.riskLevel(it.severity) == "HIGH" } -> Ui.red
                session.alerts.isEmpty() -> Ui.muted
                else -> Ui.amber
            }))
            row.addView(ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_share)
                imageTintList = ColorStateList.valueOf(if (nightMode) Color.WHITE else Ui.muted)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "Share session log"
                setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 12),
                    Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 12))
                setOnClickListener { shareSession(session) }
            })
            history.addView(row)
        }
        page.addView(history, Ui.blockParams(this))

        // Research tools - only in developer mode
        if (developerMode) {
            val guided = Ui.card(this)
            guided.addView(Ui.title(this, "Guided recording · Developer"))
            guided.addView(Ui.note(this, "Spoken instructions (close your eyes, yawn, look away, phone) with a " +
                    "countdown; writes labels.csv so the session can be scored. Parked car or simulator only."))
            guided.addView(Button(this).apply {
                text = "Start a guided recording"
                isAllCaps = false
                setOnClickListener {
                    if (DriverMonitoringService.active) cloud.toast("Stop monitoring first")
                    else startActivity(Intent(this@MainActivity, GuidedRecordingActivity::class.java))
                }
            })
            page.addView(guided, Ui.blockParams(this))

        }
    }

    /** Has this session reached the supervisor? (alerts and the session summary sent to Firebase) */
    private fun syncState(session: SessionHistoryStore.Session): Pair<String, Int> {
        val running = session.active && DriverMonitoringService.active && DriverMonitoringService.sessionId == session.id
        val pending = cloud.pendingFor(session.id)
        return when {
            !session.syncTracked -> "" to Ui.muted
            running -> "● Sending live to the supervisor" + (if (pending > 0) " ($pending waiting)" else "") to Ui.blue
            pending == 0 -> "✓ Synced: the supervisor can see it" to Ui.green
            !cloud.signedIn -> "✕ Not synced: sign in with a driver account ($pending waiting)" to Ui.red
            cloud.ready -> "⟳ Syncing… ($pending waiting)" to Ui.amber
            else -> "⟳ Not synced yet: sent when online ($pending waiting)" to Ui.amber
        }
    }

    private fun syncLabel(session: SessionHistoryStore.Session): TextView {
        val (text, color) = syncState(session)
        return Ui.note(this, text).apply {
            setTextColor(color)
            setPadding(0, 0, 0, 0)
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun sessionDate(ms: Long): String =
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ms))

    private fun sessionMinutes(ms: Long): String =
        String.format(Locale.getDefault(), "%.1f min", ms / 60_000.0)

    /** A session-specific screen with its chronological risk and alert records. */
    private fun showSessionDetails(id: String) {
        val session = SessionHistoryStore.get(this, id) ?: run { showHistory(); return }
        onHome = false
        onAccount = false
        selectedHistorySession = id
        selectTab(false)

        val details = Ui.card(this)
        details.addView(Ui.note(this, "‹ Back to history").apply {
            setOnClickListener { showHistory() }
            setPadding(0, Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 18))
        })
        details.addView(Ui.title(this, sessionDate(session.startedMs)))
        details.addView(Ui.pair(this, "Driving time", sessionMinutes(session.durationMs())))
        details.addView(Ui.pair(this, "Alerts", session.alerts.size.toString()))
        details.addView(Ui.pair(this, "HIGH alerts", session.alerts.count { Ui.riskLevel(it.severity) == "HIGH" }.toString()))
        details.addView(Ui.pair(this, "Measurement log", if (session.logDir.isNotBlank() && File(session.logDir).isDirectory)
            "Saved (share to analyse)" else "Not saved"))
        if (session.syncTracked) {
            details.addView(Ui.eyebrow(this, "SUPERVISOR"))
            details.addView(syncLabel(session))
            if (cloud.pendingFor(session.id) > 0) details.addView(Button(this).apply {
                text = "Sync now"
                isAllCaps = false
                setOnClickListener {
                    if (!cloud.signedIn) cloud.toast("Sign in with a driver account first (Settings > Account)")
                    else { cloud.flush(); cloud.toast("Sending…") }
                }
            })
        }
        if (!(session.active && DriverMonitoringService.active && DriverMonitoringService.sessionId == session.id)) {
            details.addView(Button(this).apply {
                text = "Delete this session"
                isAllCaps = false
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Delete this session?")
                        .setMessage("The history entry and its measurement log are removed from this phone. " +
                                "Data already sent to the supervisor stay in Firebase.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ ->
                            SessionHistoryStore.delete(this@MainActivity, session.id)
                            cloud.toast("Session deleted")
                            showHistory()
                        }.show()
                }
            })
        }
        page.addView(details, Ui.blockParams(this))

        if (session.alerts.isNotEmpty()) {
            val byType = Ui.card(this)
            byType.addView(Ui.title(this, "Alerts by type"))
            session.alerts.groupBy { Ui.alertCause(it.behavior, it.simulated) }.entries
                .sortedByDescending { it.value.size }.forEach { (name, list) ->
                    val worst = list.maxByOrNull { listOf("LOW", "MEDIUM", "HIGH").indexOf(Ui.riskLevel(it.severity)) }
                    byType.addView(Ui.alert(this, name, "${list.size} time${if (list.size == 1) "" else "s"}",
                        "max ${Ui.riskLevel(worst?.severity ?: "")}", Ui.riskColor(worst?.severity ?: "")))
                }
            page.addView(byType, Ui.blockParams(this))
        }

        val timeline = Ui.card(this)
        timeline.addView(Ui.title(this, "Risk timeline"))
        session.timeline.forEach { point ->
            timeline.addView(Ui.alert(this, sessionDate(point.timestampMs),
                "${(point.timestampMs - session.startedMs).coerceAtLeast(0L) / 1000} s into session",
                Ui.riskLevel(point.level), Ui.riskColor(point.level)))
        }
        page.addView(timeline, Ui.blockParams(this))

        val alerts = Ui.card(this)
        alerts.addView(Ui.title(this, "Alerts"))
        if (session.alerts.isEmpty()) alerts.addView(Ui.note(this, "No alerts were recorded."))
        else session.alerts.forEach { alert ->
            alerts.addView(Ui.alert(this, Ui.alertCause(alert.behavior, alert.simulated),
                SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(alert.timestampMs)),
                Ui.riskLevel(alert.severity), Ui.riskColor(alert.severity)))
        }
        page.addView(alerts, Ui.blockParams(this))
    }

    /** Share the measurement log (.zip, for scripts/evaluate_behaviors.py) or, without one, a text report. */
    private fun shareSession(session: SessionHistoryStore.Session) {
        val report = buildString {
            appendLine("Session: ${sessionDate(session.startedMs)}")
            appendLine("Driving time: ${sessionMinutes(session.durationMs())}")
            appendLine("Alerts: ${session.alerts.size}")
            if (session.alerts.any { it.simulated }) appendLine("Includes simulated test alerts")
            appendLine("Risk timeline:")
            session.timeline.forEach { appendLine("${sessionDate(it.timestampMs)} · ${Ui.riskLevel(it.level)}") }
            appendLine("Alerts:")
            session.alerts.forEach {
                appendLine("${sessionDate(it.timestampMs)} · ${Ui.alertCause(it.behavior, it.simulated)} · ${Ui.riskLevel(it.severity)}")
            }
        }
        val dir = File(session.logDir)
        if (session.logDir.isNotBlank() && dir.isDirectory) try {
            val zip = File(cacheDir, dir.name + ".zip")
            ZipOutputStream(zip.outputStream()).use { zos ->
                dir.listFiles()?.forEach { f ->
                    zos.putNextEntry(ZipEntry(dir.name + "/" + f.name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                zos.putNextEntry(ZipEntry(dir.name + "/history.txt"))
                zos.write(report.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share session log"))
            return
        } catch (e: Exception) {
            cloud.toast("Could not zip the log (${e.message}); sharing the text report")
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, report)
        }, "Share session log"))
    }

    /** System Back returns from the session detail screen to History. */
    @Deprecated("Use Back navigation")
    override fun onBackPressed() {
        when {
            selectedHistorySession != null -> showHistory()
            onAccount -> showSettings()
            onSettings || onDetails || !onHome -> showHome()
            else -> super.onBackPressed()
        }
    }

    private fun setNight(choice: String) {
        Ui.saveNightChoice(this, choice)
        nightMode = Ui.nightPreference(this)
        Ui.nightMode = nightMode
        root.setBackgroundColor(Ui.canvas)
        tabs.setBackgroundColor(Ui.surface)
        updateVisiblePage()
    }

    /** Settings for every driver: alerts, break reminder, display, devices, setup guide, account, help, about. */
    private fun showSettings() {
        val keepY = if (onSettings) scroller.scrollY else 0 // a choice tapped in Settings keeps the place
        onHome = false
        onDetails = false
        onAccount = false
        selectedHistorySession = null
        selectTab(false, settings = true)
        val prefs = edu.dlsu.esca_3_2526_c3.monitor.DeviceSettings(this)

        val alerts = Ui.card(this)
        alerts.addView(Ui.title(this, "Alerts"))
        alerts.addView(Ui.note(this, "The buzzer on the sensor board always sounds the alarm. The phone can add:"))
        alerts.addView(Ui.switchRow(this, "Spoken warnings", "Says what to do, e.g. \"Eyes on the road\" (MEDIUM and HIGH alerts, alarm volume)",
            prefs.speakAlerts) { prefs.speakAlerts = it })
        alerts.addView(Ui.switchRow(this, "Vibration", "Vibrates for MEDIUM and HIGH alerts", prefs.vibrateAlerts) { prefs.vibrateAlerts = it })
        alerts.addView(Ui.switchRow(this, "Phone beeps", "Beeps like the buzzer; useful without the sensor board (from the next session)",
            prefs.phoneSound) { prefs.phoneSound = it })
        alerts.addView(Ui.choiceRow(this, "Break reminder", listOf("0" to "Off", "60" to "1 h", "90" to "1.5 h", "120" to "2 h", "180" to "3 h"),
            prefs.breakMinutes.toString()) { prefs.breakMinutes = it.toInt(); showSettings() })
        alerts.addView(Ui.linkRow(this, "Test phone alert", "Plays a spoken warning and a vibration once") {
            startService(Intent(this, DriverMonitoringService::class.java).setAction(DriverMonitoringService.TEST_ALERT))
        })
        page.addView(alerts, Ui.blockParams(this))

        val display = Ui.card(this)
        display.addView(Ui.title(this, "Display"))
        display.addView(Ui.choiceRow(this, "Night mode", listOf("off" to "Off", "on" to "On", "auto" to "Auto (6 pm-6 am)"),
            Ui.nightChoice(this)) { setNight(it) })
        display.addView(Ui.switchRow(this, "Keep the screen on", "While monitoring and the app is open", prefs.keepScreenOn) {
            prefs.keepScreenOn = it
        })
        page.addView(display, Ui.blockParams(this))

        val devicesCard = Ui.card(this)
        devicesCard.addView(Ui.title(this, "Camera and sensor board"))
        addConnectionStrip(devicesCard)
        devicesCard.addView(Ui.linkRow(this, "Device settings and tests", "Addresses, find the boards, test the camera, sensors and buzzer") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        devicesCard.addView(Ui.linkRow(this, "Setup guide & camera alignment", "Wi-Fi, device checks and camera position, step by step") {
            if (DriverMonitoringService.active) cloud.toast("Stop monitoring first: the camera serves one viewer at a time")
            else startActivity(Intent(this, SetupActivity::class.java))
        })
        page.addView(devicesCard, Ui.blockParams(this))

        val accountCard = Ui.card(this)
        accountCard.addView(Ui.title(this, "Account"))
        accountCard.addView(Ui.linkRow(this, if (cloud.signedIn) cloud.email else "Not signed in",
            if (cloud.signedIn) "Role: ${cloud.role.ifBlank { "none" }} · ${cloud.connectionLabel}" else "Sign in to send live status to the supervisor") {
            showAccount()
        })
        page.addView(accountCard, Ui.blockParams(this))

        val help = Ui.card(this)
        help.addView(Ui.title(this, "What the screen means"))
        for ((level, meaning) in listOf(
            "NORMAL" to "No warning signs.",
            "LOW" to "Mild signs (short look away, a yawn). Stay alert.",
            "MEDIUM" to "Clear signs (eyes closing, phone, looking away for seconds). The buzzer beeps.",
            "HIGH" to "Dangerous (long eye closure, phone use, drowsiness). Take a break.")) {
            help.addView(Ui.alert(this, meaning, "", level, Ui.riskColor(level)))
        }
        help.addView(Ui.note(this, "CALIBRATING: the first 5 seconds of each session, look at the road normally. " +
                "NO CAMERA PICTURE: check the camera power and the DMS-NET Wi-Fi."))
        page.addView(help, Ui.blockParams(this))

        val about = Ui.card(this)
        about.addView(Ui.title(this, "About"))
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        about.addView(Ui.pair(this, "Danteye", "version $version"))
        about.addView(Ui.note(this, ModelStatus.short(this)))
        page.addView(about, Ui.blockParams(this))

        if (developerMode) {
            val dev = Ui.card(this)
            dev.addView(Ui.title(this, "Developer mode"))
            dev.addView(Ui.linkRow(this, "Developer settings", "Models, session logs, Wi-Fi provisioning (in Device settings)") {
                startActivity(Intent(this, SettingsActivity::class.java))
            })
            dev.addView(Ui.switchRow(this, "Developer mode", "Raw measurements, test alerts, guided recording", true) { on ->
                if (!on) {
                    developerMode = false
                    getSharedPreferences("driver_display", MODE_PRIVATE).edit().putBoolean("developer", false).apply()
                    manualCoordinates = null
                    DriverMonitoringService.manualLocation = null
                    cloud.toast("Developer mode off")
                    showSettings()
                }
            })
            page.addView(dev, Ui.blockParams(this))
        }
        if (keepY > 0) scroller.post { scroller.scrollTo(0, keepY) }
    }

    /** Account: who is signed in, role, Firebase state, sign out. Sign-in itself is on the launcher screen. */
    private fun showAccount() {
        onHome = false
        onAccount = true
        selectTab(false, true)

        page.addView(Ui.linkRow(this, "‹ Back to Settings", "") { showSettings() }, Ui.blockParams(this))
        val account = Ui.card(this)
        account.addView(Ui.title(this, "Account"))
        if (cloud.signedIn) {
            account.addView(Ui.note(this, "Signed in as ${cloud.email}"))
            account.addView(Ui.pair(this, "Account role", cloud.role.ifBlank { "No role" }))
            account.addView(Ui.note(this, cloud.connectionLabel))
            account.addView(Ui.note(this, "Live status and alerts reach the supervisor only with the driver role."))
            account.addView(Button(this).apply {
                text = "Sign out"
                isAllCaps = false
                setOnClickListener {
                    if (DriverMonitoringService.active) { cloud.toast("Stop monitoring first"); return@setOnClickListener }
                    cloud.signOut()
                    startActivity(Intent(this@MainActivity, LauncherActivity::class.java))
                    finish()
                }
            })
        } else {
            account.addView(Ui.note(this, "Not signed in. Monitoring works without an account, but the supervisor sees nothing."))
            account.addView(Button(this).apply {
                text = "Sign in"
                isAllCaps = false
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, LauncherActivity::class.java))
                    finish()
                }
            })
        }
        page.addView(account, Ui.blockParams(this))

    }
}
