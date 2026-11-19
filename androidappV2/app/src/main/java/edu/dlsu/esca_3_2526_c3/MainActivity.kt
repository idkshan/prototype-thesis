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
import android.text.method.PasswordTransformationMethod


class MainActivity : Activity() {
    companion object {
        var developerSessionEnabled = false
            private set
    }

    // Firebase test connection and shared monitoring-service state.
    private lateinit var cloud: DemoCloud
    private val statuses get() = DriverMonitoringService.statuses
    private val recent get() = DriverMonitoringService.recent
    private val sessionId get() = DriverMonitoringService.sessionId
    private val sessionStarted get() = DriverMonitoringService.sessionStarted

    // Track the current page and the saved display preference.
    private var onHome = true
    private var onDetails = false
    private var onAccount = false
    private var selectedHistorySession: String? = null
    private var signingUp = false
    private var accountEmailDraft = ""
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

    // Poll the configured camera and sensor endpoints while the driver UI is visible.
    private val devices by lazy { DeviceStatusMonitor(this) { refreshDeviceStatus() } }

    /** Set up UI and reconnect to an existing monitoring session. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        developerSessionEnabled = false
        DriverMonitoringService.restore(this)
        nightMode = getSharedPreferences("driver_display", MODE_PRIVATE).getBoolean("night", false)
        Ui.nightMode = nightMode
        window.statusBarColor = Ui.navy
        window.navigationBarColor = Ui.navy

        // Fixed header, scrollable page content, and bottom navigation.
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.canvas)
        }
        root.addView(Ui.header(this, "Driver Dashboard").apply {
            setOnClickListener { onDashboardTap() }
        })
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
        settingsTab = Ui.tab(this, "⚙  Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }.apply { textSize = 13f }

        tabs.addView(homeTab, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        tabs.addView(historyTab, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        tabs.addView(accountTab, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        tabs.addView(settingsTab, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        root.addView(tabs)
        setContentView(root)

        // Refresh the current page when Firebase connection data changes.
        cloud = DemoCloud(this)
        cloud.onChange = { if (!isFinishing) refreshForRole() }
        cloud.start()

        if (DriverMonitoringService.active) {
            ContextCompat.startForegroundService(this, Intent(this, DriverMonitoringService::class.java).setAction(DriverMonitoringService.START))
        }

        showHome()
        if (!getSharedPreferences("driver_setup_v1", MODE_PRIVATE).getBoolean("complete", false)) {
            startActivity(Intent(this, SetupActivity::class.java))
        }
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
        if (onAccount) showAccount() else if (selectedHistorySession != null)
            showSessionDetails(selectedHistorySession!!) else if (!onHome) showHistory()
        else if (onDetails) showDetails() else showHome()
    }

    /** Resume receiving service updates when the app becomes visible. */
    override fun onResume() {
        super.onResume()
        Ui.nightMode = nightMode
        DriverMonitoringService.onChange = { if (!isFinishing && !isDestroyed && !onAccount) updateVisiblePage() }

        // Account sign-in may have changed while another screen was open.
        cloud.stop()
        cloud = DemoCloud(this).apply {
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
        Ui.nightMode = false
        super.onPause()
    }

    /** Clean up the border animation and Firebase listeners. */
    override fun onDestroy() {
        borderPulse?.cancel()
        cloud.stop()
        super.onDestroy()
    }

    /** Cycle a manual alert for testing */
    private fun trigger(name: String) {
        if (!DriverMonitoringService.active) {
            cloud.toast("Start driver monitoring service first")
            return
        }

        val current = statuses[name] ?: "Normal"
        val next = when (current) {
            "Normal" -> "Warning"; "Warning" -> "High"; else -> "Normal"
        }
        DriverMonitoringService.updateSimulatedRisk(this, name, next)

        if (next != "Normal") {
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            // An escalation of the same ongoing manual test replaces its previous stage.
            val prior = if (current == "Warning") recent.indexOfFirst {
                it.first == name && it.third == "Warning"
            } else -1
            if (prior >= 0) recent[prior] = Triple(name, recent[prior].second, next)
            else recent.add(0, Triple(name, time, next))
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
            cloud.clearLocation()
        }
        if (running) startService(intent) else ContextCompat.startForegroundService(this, intent)
    }

    /** Save the night-mode preference and repaint the current page. */
    private fun switchNightMode() {
        nightMode = !nightMode
        Ui.nightMode = nightMode
        getSharedPreferences("driver_display", MODE_PRIVATE).edit().putBoolean("night", nightMode).apply()
        root.setBackgroundColor(Ui.canvas)
        tabs.setBackgroundColor(Ui.surface)
        updateVisiblePage()
    }

    /** Seven header taps - toggle UI-only test controls. */
    private fun onDashboardTap() {
        val now = System.currentTimeMillis()
        if (dashboardTaps == 0 || now - firstDashboardTap > 4500L) {
            dashboardTaps = 0
            firstDashboardTap = now
        }
        if (++dashboardTaps == 7) {
            dashboardTaps = 0
            developerMode = !developerMode
            developerSessionEnabled = developerMode
            if (!developerMode && manualCoordinates != null) {
                manualCoordinates = null
                if (DriverMonitoringService.active) refreshDeviceStatus() else cloud.clearLocation()
            }
            cloud.toast(if (developerMode) "Developer mode on" else "Developer mode off")
            updateVisiblePage()
        }
    }

    /** Reset page, flashing border */
    private fun selectTab(home: Boolean, account: Boolean = false) {
        Ui.activeTab(homeTab, home)
        Ui.activeTab(historyTab, !home && !account)
        Ui.activeTab(accountTab, account)
        Ui.activeTab(settingsTab, false)
        settingsTab.visibility = if (developerMode) View.VISIBLE else View.GONE
        borderPulse?.cancel()
        borderPulse = null
        root.foreground = null
        statusStrip = null
        cameraFrame = null

        // Flash around both Driving Mode and Details while HIGH risk is active.
        if (home && DriverMonitoringService.active && statuses.values.any { it == "High" }) {
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
        val state = devices.state
        // An explicitly entered test location overrides prototype GPS until cleared.
        val manual = manualCoordinates
        if (DriverMonitoringService.active && sessionStarted > 0L) {
            cloud.publish(statuses, sessionId,
                (System.currentTimeMillis() - sessionStarted).coerceAtLeast(0L) / 1000L,
                manual?.first ?: state.latitude, manual?.second ?: state.longitude,
                if (manual == null) state.speedKph else null, updateLocation = true,
                locationSource = if (manual != null) "manual" else if (state.gps) "gps" else null)
        }
        statusStrip?.apply {
            removeAllViews()
            val indicators = listOf(
                "📷 Camera" to state.camera,
                "▣ Sensor" to state.sensor,
                (if (manual != null) "⌖ Manual location" else "⌖ GPS") to (manual != null || state.gps),
                "☁ Firebase" to cloud.ready
            )
            indicators.chunked(2).forEach { pair ->
                val row = LinearLayout(this@MainActivity)
                pair.forEach { (label, ready) ->
                    row.addView(Ui.deviceIndicator(this@MainActivity, label, ready),
                        LinearLayout.LayoutParams(0, -2, 1f).apply {
                            setMargins(Ui.dp(this@MainActivity, 2), Ui.dp(this@MainActivity, 2),
                                Ui.dp(this@MainActivity, 2), Ui.dp(this@MainActivity, 2))
                        })
                }
                addView(row)
            }
        }
        cameraFrame?.apply {
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

    /** Driving Mode */
    private fun showHome() {
        onHome = true
        onDetails = false
        onAccount = false
        selectTab(true)
        val overview = Ui.card(this)
        overview.addView(Ui.eyebrow(this, "DRIVING MODE"))

        // Show the highest simulated risk, or STOPPED when inactive.
        val risk = when {
            !DriverMonitoringService.active -> "STOPPED"
            statuses.values.any { it == "High" } -> "HIGH"
            statuses.values.any { it == "Warning" } -> "MEDIUM"
            else -> "LOW"
        }
        val color = if (risk == "STOPPED") Ui.muted else Ui.riskColor(risk)

        // The solid-color panel makes the risk visible at a glance.
        val riskBackground = GradientDrawable().apply {
            setColor(color)
            cornerRadius = Ui.dp(this@MainActivity, 16).toFloat()
        }

        val riskDisplay = TextView(this).apply {
            text = if (risk == "STOPPED") "MONITORING OFF" else "$risk RISK"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(if (risk == "MEDIUM") Color.BLACK else Color.WHITE)
            setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 27),
                Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 27))
            background = riskBackground
        }
        overview.addView(riskDisplay, LinearLayout.LayoutParams(-1, -2))
        overview.addView(Ui.space(this, 12))

        overview.addView(Ui.title(this, when (risk) {
            "HIGH" -> "Please take a break"
            "MEDIUM" -> "Stay alert"
            "LOW" -> "Keep your eyes on the road"
            else -> "Driver monitoring service is stopped"
        }))

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
        overview.addView(Ui.space(this, 8))
        addConnectionStrip(overview)
        overview.addView(Ui.note(this, if (cloud.signedIn)
            "Signed in as ${cloud.email}" else "Not signed in · Open Account to sign in"))

        // Side-by-side shortcuts avoid another row of full-width buttons.
        val shortcuts = LinearLayout(this)
        shortcuts.addView(Button(this).apply {
            text = if (nightMode) "Day mode" else "Night mode"
            isAllCaps = false
            if (nightMode) { backgroundTintList = ColorStateList.valueOf(Ui.navy); setTextColor(Color.WHITE) }
            setOnClickListener { switchNightMode() }
        }, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        shortcuts.addView(Button(this).apply {
            text = "View details"
            isAllCaps = false
            if (nightMode) { backgroundTintList = ColorStateList.valueOf(Ui.navy); setTextColor(Color.WHITE) }
            setOnClickListener { showDetails() }
        }, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        overview.addView(shortcuts)
        overview.addView(Ui.note(this, "Setup guide & camera alignment  ›").apply {
            setPadding(0, Ui.dp(this@MainActivity, 12), 0, Ui.dp(this@MainActivity, 8))
            setOnClickListener { startActivity(Intent(this@MainActivity, SetupActivity::class.java)) }
        })
        page.addView(overview, Ui.blockParams(this))
        if (developerMode) showManualLocationControls()
    }

    /** Optional driver-side test coordinates; blank until the researcher explicitly applies them. */
    private fun showManualLocationControls() {
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Manual GPS coordinates · Test"))
        card.addView(Ui.note(this, "Optional test location for the Supervisor map. Leave blank to use prototype GPS."))
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
                if (DriverMonitoringService.active) refreshDeviceStatus()
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
                if (DriverMonitoringService.active) refreshDeviceStatus() else cloud.clearLocation()
                cloud.toast("Manual test location cleared")
                showHome()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(actions)
        page.addView(card, Ui.blockParams(this))
    }

    /** Display simulated detection controls and placeholder measurements. */
    private fun showDetails() {
        onHome = true
        onDetails = true
        onAccount = false
        selectTab(true)
        val metrics = Ui.card(this)
        metrics.addView(Ui.title(this, "Detection Details"))
        addConnectionStrip(metrics)
        metrics.addView(Ui.space(this, 8))
        cameraFrame = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        metrics.addView(cameraFrame)
        refreshDeviceStatus()
        metrics.addView(Ui.pair(this, "Trip duration", if (DriverMonitoringService.active)
            "${(System.currentTimeMillis() - sessionStarted).coerceAtLeast(0L) / 60000} min" else "Not monitoring"))

        // show measurements only when developer mode is on
        if (developerMode) {
            for (name in listOf("Drowsiness", "Yawning", "Gaze deviation", "Phone usage")) {
                val row = Ui.pair(this, name, Ui.riskLevel(statuses[name] ?: "Normal"))
                row.setOnClickListener { trigger(name) }
                metrics.addView(row)
            }
            metrics.addView(Ui.space(this, 10))
            metrics.addView(Ui.eyebrow(this, "RAW MEASUREMENTS"))
            metrics.addView(Ui.pair(this, "Eye openness (EAR)", "0.286"))
            metrics.addView(Ui.pair(this, "Mouth opening (MAR)", "0.124"))
            metrics.addView(Ui.pair(this, "Head turn (yaw)", "4.5°"))
            metrics.addView(Ui.pair(this, "Head tilt (pitch)", "-2.0°"))
            metrics.addView(Ui.pair(this, "Camera frames per second", "Camera not connected"))
        }

        metrics.addView(Button(this).apply {
            text = "Back to Driving mode"
            isAllCaps = false
            if (nightMode) { backgroundTintList = ColorStateList.valueOf(Ui.navy); setTextColor(Color.WHITE) }
            setOnClickListener { showHome() }
        })
        page.addView(metrics, Ui.blockParams(this))

        // Recent alerts list that contains manually simulated test events
        val alerts = Ui.card(this)
        alerts.addView(Ui.title(this, "Recent Alerts"))
        if (!developerMode) alerts.addView(Ui.note(this, "No live alerts available yet."))
        else if (recent.isEmpty()) alerts.addView(Ui.note(this, "No test events yet"))
        else recent.take(5).forEach { (behavior, time, severity) ->
            alerts.addView(Ui.alert(this, Ui.alertCause(behavior), time, Ui.riskLevel(severity),
                Ui.riskColor(severity)))
        }
        page.addView(alerts, Ui.blockParams(this))
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
            row.addView(Ui.chip(this, "${session.alerts.size} alerts", when {
                session.alerts.any { it.severity == "High" } -> Ui.red
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

        // Manual cloud synchronization - only accessible in developer mode
        if (developerMode) {
            // Retry pending Firebase events when the driver role is authorized
            val sync = Ui.card(this)
            sync.addView(Ui.title(this, "Supervisor Sync · Test"))
            val button = Button(this).apply {
                text = "Manually Sync Logs to Supervisor"
                isAllCaps = false
                setOnClickListener {
                    if (cloud.role != "driver") cloud.toast("Sign in as a driver researcher to sync.")
                    else {
                        cloud.flush(); cloud.toast("Sync requested. Check pending count and Firebase connection.")
                    }
                }
            }
            sync.addView(button)
            sync.addView(Ui.note(this, "Last successful event sync: ${cloud.lastSyncText()}"))
            sync.addView(Ui.note(this, "Pending simulated events: ${cloud.pendingCount}"))
            sync.addView(Ui.note(this, cloud.connectionLabel))
            page.addView(sync, Ui.blockParams(this))
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
        page.addView(details, Ui.blockParams(this))

        val timeline = Ui.card(this)
        timeline.addView(Ui.title(this, "Risk timeline"))
        session.timeline.forEach { point ->
            timeline.addView(Ui.alert(this, sessionDate(point.timestampMs),
                "${(point.timestampMs - session.startedMs).coerceAtLeast(0L) / 1000} s into session",
                point.level, Ui.riskColor(point.level)))
        }
        page.addView(timeline, Ui.blockParams(this))

        val alerts = Ui.card(this)
        alerts.addView(Ui.title(this, "Alerts"))
        if (session.alerts.isEmpty()) alerts.addView(Ui.note(this, "No alerts were recorded."))
        else session.alerts.forEach { alert ->
            alerts.addView(Ui.alert(this, Ui.alertCause(alert.behavior),
                SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(alert.timestampMs)),
                Ui.riskLevel(alert.severity), Ui.riskColor(alert.severity)))
        }
        page.addView(alerts, Ui.blockParams(this))
    }

    /** Share a readable text report without needing external storage permission. */
    private fun shareSession(session: SessionHistoryStore.Session) {
        val log = buildString {
            appendLine("Session: ${sessionDate(session.startedMs)}")
            appendLine("Driving time: ${sessionMinutes(session.durationMs())}")
            appendLine("Alerts: ${session.alerts.size}")
            appendLine("Simulated monitoring data")
            appendLine("Risk timeline:")
            session.timeline.forEach { appendLine("${sessionDate(it.timestampMs)} · ${it.level}") }
            appendLine("Alerts:")
            session.alerts.forEach {
                appendLine("${sessionDate(it.timestampMs)} · ${it.behavior} · ${Ui.riskLevel(it.severity)}")
            }
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, log)
        }, "Share session log"))
    }

    /** System Back returns from the session detail screen to History. */
    @Deprecated("Use Back navigation")
    override fun onBackPressed() {
        if (selectedHistorySession != null) showHistory() else super.onBackPressed()
    }

    /** Dedicated screen for Firebase email/password sign-in and registration. */
    private fun showAccount() {
        onHome = false
        onAccount = true
        selectTab(false, true)

        val account = Ui.card(this)
        account.addView(Ui.title(this, "Account"))

        if (cloud.signedIn) {
            account.addView(Ui.note(this, "Signed in as ${cloud.email}"))
            account.addView(Ui.pair(this, "Account role", cloud.role.ifBlank { "No role" }))
            account.addView(Ui.note(this, cloud.connectionLabel))
            account.addView(Ui.note(this, "Cloud monitoring requires an account with the driver role."))
            account.addView(Button(this).apply {
                text = "Sign out"
                isAllCaps = false
                setOnClickListener {
                    cloud.signOut()
                    startActivity(Intent(this@MainActivity, LauncherActivity::class.java))
                    finish()
                }
            })
        } else {
            account.addView(Ui.note(this, "Sign in or create a Firebase account."))
            val choices = LinearLayout(this)
            choices.addView(Button(this).apply {
                text = "Sign in"
                isEnabled = signingUp
                isAllCaps = false
                setOnClickListener { accountEmailDraft = ""; signingUp = false; showAccount() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            choices.addView(Button(this).apply {
                text = "Sign up"
                isEnabled = !signingUp
                isAllCaps = false
                setOnClickListener { accountEmailDraft = ""; signingUp = true; showAccount() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            account.addView(choices)

            val email = EditText(this).apply {
                hint = "Email address"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                setSingleLine(true)
                setText(accountEmailDraft)
                textSize = 14f // font size
            }
            val password = EditText(this).apply {
                hint = "Password (at least 6 characters)"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                transformationMethod = PasswordTransformationMethod.getInstance()
                textSize = 14f // font size so full hint can be seen
            }
            account.addView(email)
            account.addView(password)

            val confirmation = if (signingUp) EditText(this).apply {
                hint = "Confirm password"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                transformationMethod = PasswordTransformationMethod.getInstance()
            } else null
            confirmation?.let { account.addView(it) }

            // Set text colors for night mode
            if (nightMode) listOfNotNull(email, password, confirmation).forEach {
                it.setTextColor(Color.WHITE)
                it.setHintTextColor(Color.LTGRAY)
            }

            val feedback = TextView(this).apply { setTextColor(Ui.red); textSize = 14f }
            account.addView(feedback)
            val submit = Button(this).apply {
                text = if (signingUp) "Create account" else "Sign in"
                isAllCaps = false
                setOnClickListener {
                    val address = email.text.toString().trim()
                    val secret = password.text.toString()
                    accountEmailDraft = address
                    when {
                        !cloud.configured -> { feedback.text = "Firebase is not configured on this device"; return@setOnClickListener }
                        !android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches() -> {
                            feedback.text = "Enter a valid email address"; return@setOnClickListener
                        }
                        secret.isBlank() -> { feedback.text = "Enter your password"; return@setOnClickListener }
                        signingUp && secret.length < 6 -> {
                            feedback.text = "Use at least 6 password characters"; return@setOnClickListener
                        }
                        signingUp && secret != confirmation?.text.toString() -> {
                            feedback.text = "Passwords do not match"; return@setOnClickListener
                        }
                    }
                    isEnabled = false
                    feedback.text = if (signingUp) "Creating account…" else "Signing in…"
                    val auth = FirebaseAuth.getInstance()
                    val task = if (signingUp) auth.createUserWithEmailAndPassword(address, secret)
                    else auth.signInWithEmailAndPassword(address, secret)
                    task.addOnCompleteListener(this@MainActivity) { result ->
                        if (result.isSuccessful) {
                            cloud.stop()
                            cloud = DemoCloud(this@MainActivity).apply {
                                onChange = { if (!isFinishing) refreshForRole() }
                                start()
                            }
                            showAccount()
                        } else {
                            isEnabled = true
                            feedback.text = result.exception?.localizedMessage ?: "Authentication failed"
                        }
                    }
                }
            }
            account.addView(submit)
            if (!signingUp) account.addView(Button(this).apply {
                text = "Forgot password?"
                isAllCaps = false
                setOnClickListener {
                    val address = email.text.toString().trim()
                    if (!cloud.configured || !android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
                        feedback.text = "Enter a valid email address first"
                    } else {
                        FirebaseAuth.getInstance().sendPasswordResetEmail(address)
                            .addOnSuccessListener { feedback.text = "If the account exists, check your email" }
                            .addOnFailureListener { feedback.text = it.localizedMessage ?: "Could not send reset email" }
                    }
                }
            })
        }
        page.addView(account, Ui.blockParams(this))
    }
}