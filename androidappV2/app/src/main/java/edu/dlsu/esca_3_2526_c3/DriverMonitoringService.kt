package edu.dlsu.esca_3_2526_c3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import edu.dlsu.esca_3_2526_c3.dms.AlertCommand
import edu.dlsu.esca_3_2526_c3.dms.CALIBRATING
import edu.dlsu.esca_3_2526_c3.monitor.DeviceSettings
import edu.dlsu.esca_3_2526_c3.monitor.LiveFrame
import edu.dlsu.esca_3_2526_c3.monitor.MonitorSession
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * User-started foreground service that runs the detection program (MonitorSession):
 *   ESP32-CAM video -> MediaPipe + YOLO -> rules, severity, fuzzy risk -> alert state machine
 *   -> buzzer on the sensor board, phone voice / vibration, Firebase, session history.
 * Monitoring continues with the screen off or another app in front. Also gives the
 * two-hour break reminder.
 */
class DriverMonitoringService : Service(), TextToSpeech.OnInitListener, MonitorSession.Listener {
    companion object {
        const val START = "edu.dlsu.esca_3_2526_c3.START_MONITORING"
        const val STOP = "edu.dlsu.esca_3_2526_c3.STOP_MONITORING"
        const val RECALIBRATE = "edu.dlsu.esca_3_2526_c3.RECALIBRATE"
        /** Developer-mode test: a simulated HIGH alert (vibration, voice, notification). */
        const val HIGH_ALERT = "edu.dlsu.esca_3_2526_c3.DEMO_HIGH_ALERT"
        /** Settings > Test phone alert: voice and vibration once (also when not monitoring). */
        const val TEST_ALERT = "edu.dlsu.esca_3_2526_c3.TEST_ALERT"

        private const val PREFS = "driver_monitoring_v1"
        private const val CHANNEL_ACTIVE = "driver_monitoring"
        private const val CHANNEL_ALERT = "driver_safety_alerts"
        private const val ACTIVE_ID = 501
        private const val PUBLISH_MS = 2_000L

        /** Behaviour levels for the screen and Firebase: Normal / Warning / High (from the severity grades). */
        val statuses = linkedMapOf(
            "Drowsiness" to "Normal", "Yawning" to "Normal",
            "Gaze deviation" to "Normal", "Phone usage" to "Normal"
        )
        /** Recent alerts, newest first: label, time, level (HIGH / MEDIUM / LOW, OFF, or a test level). */
        val recent = mutableListOf<Triple<String, String, String>>()

        var sessionId = ""
            private set
        var sessionStarted = 0L
            private set
        var active = false
            private set

        /** Newest processed frame (picture with overlay, measurements, engine output); null before the first one. */
        @Volatile var latest: LiveFrame? = null
            private set
        /** Wall-clock time of [latest]. */
        @Volatile var latestAtMs = 0L
            private set
        /** Last message from the pipeline ("Camera connected", "Camera: ... retrying", ...). */
        @Volatile var statusText = "Not monitoring"
            private set
        /** Folder of the running session's measurement log (null when logs are off). */
        @Volatile var logDir: String? = null
            private set
        /** Developer-mode test location, sent to the supervisor when the GPS has no fix (null = GPS only). */
        @Volatile var manualLocation: Pair<Double, Double>? = null

        /** True when the developer-mode test values (not the detection program) set [statuses]. */
        var simulatedStatuses = false
            private set

        private var restored = false
        private val resetHandler = Handler(Looper.getMainLooper())

        /** Screen callbacks (main thread). onChange: rebuild the page; onFrame: update live values only. */
        var onChange: (() -> Unit)? = null
        var onFrame: ((LiveFrame) -> Unit)? = null
        /** New status message only (camera connecting / retrying); falls back to onChange. */
        var onStatusText: ((String) -> Unit)? = null

        /** Developer mode: set one behaviour by hand; it returns to Normal after 12 seconds. */
        fun updateSimulatedRisk(context: Context, name: String, level: String) {
            statuses[name] = level
            simulatedStatuses = level != "Normal" || statuses.values.any { it != "Normal" }
            if (active) SessionHistoryStore.record(context, sessionId, name, level, statuses)
            onChange?.invoke()
            if (level != "Normal") resetHandler.postDelayed({
                if (active && statuses[name] == level) {
                    statuses[name] = "Normal"
                    simulatedStatuses = statuses.values.any { it != "Normal" }
                    SessionHistoryStore.record(context.applicationContext, sessionId, name, "Normal", statuses)
                    onChange?.invoke()
                }
            }, 12_000L)
        }

        /** Read session metadata once when the process initializes. */
        fun restore(context: Context) {
            if (restored) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            active = prefs.getBoolean("active", false)
            sessionStarted = prefs.getLong("started", 0L)
            sessionId = prefs.getString("session", "") ?: ""
            if (active && (sessionStarted <= 0L || sessionStarted > System.currentTimeMillis())) {
                active = false
                prefs.edit().putBoolean("active", false).apply()
            }
            restored = true
        }

        /** Display tier of the risk model: NORMAL (model tier NONE) / LOW / MEDIUM / HIGH. */
        fun displayTier(tier: String) = if (tier == "NONE") "NORMAL" else tier

        /** Severity grade -> behaviour level shown on the screens. */
        fun statusOf(level: String) = when (level) { "SEVERE" -> "High"; "MODERATE" -> "Warning"; else -> "Normal" }

        fun behaviourName(key: String) = when (key) {
            "eye_closure" -> "Eye closure"; "yawning" -> "Yawning"; "gaze_deviation" -> "Gaze deviation"
            "phone_usage" -> "Phone usage"; "sudden_motion" -> "Sudden motion"
            "drowsiness" -> "Drowsiness"; else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var speaker: TextToSpeech? = null
    private var speechReady = false
    private var queuedSpeech: String? = null
    @Volatile private var monitor: MonitorSession? = null
    private var cloud: CloudSync? = null
    private var settings: DeviceSettings? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastPublishMs = 0L
    private var lastTier = ""
    private var lastNotifiedTier = ""
    // Session summary for the supervisor (drivers/<driver>/sessions/<session>)
    private var alertCount = 0
    private var highCount = 0
    private var maxLevel = "NORMAL"

    /** Every 30 s: notification text, break reminder, history recovery point, heartbeat to Firebase. */
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            val elapsed = (System.currentTimeMillis() - sessionStarted).coerceAtLeast(0L)
            // Break reminder every N minutes (Settings); each completed interval reminds once.
            val breakMs = (settings ?: DeviceSettings(this@DriverMonitoringService)).breakMinutes * 60_000L
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (breakMs > 0) {
                val interval = elapsed / breakMs
                if (interval > 0 && interval > prefs.getLong("lastBreak", 0L)) {
                    prefs.edit().putLong("lastBreak", interval).apply()
                    val minutes = interval * breakMs / 60_000L
                    val time = if (minutes % 60 == 0L) "${minutes / 60} hour${if (minutes == 60L) "" else "s"}"
                    else String.format(Locale.US, "%.1f hours", minutes / 60.0)
                    val phrase = "You've been driving for $time. Please take a break."
                    notifyAlert("Break reminder", phrase, ACTIVE_ID + 1)
                    speak(phrase)
                }
            }
            refreshNotification()
            SessionHistoryStore.checkpoint(this@DriverMonitoringService, sessionId)
            // No picture for a while: still tell the supervisor that the driver app is running.
            if (System.currentTimeMillis() - lastPublishMs > 10_000L) publish(null)
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        restore(this)
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ACTIVE, "Driver monitoring", NotificationManager.IMPORTANCE_LOW
            ))
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ALERT, "Driver safety alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply { enableVibration(true); lightColor = Color.RED; enableLights(true) })
        }
        cloud = CloudSync(this).apply { start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> {
                finishSession()
                return START_NOT_STICKY
            }
            START -> begin()
            RECALIBRATE -> monitor?.recalibrate()
            TEST_ALERT -> {
                vibrate(true)
                speak("This is a test alert. Eyes on the road.")
                if (!active) { handler.postDelayed({ if (!active) stopSelf() }, 6_000L); return START_NOT_STICKY }
            }
            HIGH_ALERT -> if (active) {
                phoneFeedback("HIGH", "Please take a break")
                notifyAlert("Simulated high-risk alert (test)", "Please take a break", ACTIVE_ID + 2)
            }
            // Restarted by Android after the app process was stopped: resume monitoring.
            null -> if (!active) { stopSelf(); return START_NOT_STICKY }
        }
        if (active) {
            startForeground(ACTIVE_ID, activeNotification())
            if (monitor == null) startPipeline()
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, 30_000L)
        }
        return START_STICKY
    }

    /** Begin a new session (no effect if one is running). */
    private fun begin() {
        if (active) return
        active = true
        sessionStarted = System.currentTimeMillis()
        sessionId = "DMS-" + UUID.randomUUID()
        statuses.keys.forEach { statuses[it] = "Normal" }
        simulatedStatuses = false
        resetHandler.removeCallbacksAndMessages(null)
        recent.clear()
        latest = null
        latestAtMs = 0L
        lastTier = ""
        lastNotifiedTier = ""
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("active", true).putLong("started", sessionStarted)
            .putString("session", sessionId).putLong("lastBreak", 0L).apply()
        SessionHistoryStore.start(this, sessionId, sessionStarted)
        alertCount = 0; highCount = 0; maxLevel = "NORMAL"
        cloud?.recordSession(sessionId, mapOf("startedMs" to sessionStarted, "active" to true))
        onChange?.invoke()
    }

    private fun startPipeline() {
        val s = DeviceSettings(this)
        settings = s
        statusText = "Starting the detection program …"
        acquireLocks()
        val m = MonitorSession(this, s, this)
        monitor = m
        // Loading the face model takes about a second: keep it off the main thread.
        Thread {
            synchronized(m) { m.start() } // stop() waits for this (see finishSession)
            if (monitor !== m) return@Thread // stopped while the model was loading
            m.logDir?.let { dir ->
                logDir = dir.absolutePath
                handler.post { if (active) SessionHistoryStore.setLogDir(this, sessionId, dir.absolutePath) }
            }
        }.start()
    }

    /** Stop monitoring: buzzer off, log saved, history closed, supervisor told. */
    private fun finishSession() {
        val m = monitor
        monitor = null
        val id = sessionId
        val trip = (System.currentTimeMillis() - sessionStarted).coerceAtLeast(0L) / 1000L
        handler.removeCallbacks(tick)
        resetHandler.removeCallbacksAndMessages(null)
        if (active) {
            SessionHistoryStore.stop(this, id)
            cloud?.publishStopped(id, trip)
            cloud?.recordSession(id, mapOf("endedMs" to System.currentTimeMillis(), "durationS" to trip,
                "alerts" to alertCount, "highAlerts" to highCount, "maxLevel" to maxLevel, "active" to false))
        }
        active = false
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("active", false).apply()
        statuses.keys.forEach { statuses[it] = "Normal" }
        simulatedStatuses = false
        statusText = if (m != null) "Stopping …" else "Not monitoring"
        onChange?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Thread {
            val summary = try { m?.let { synchronized(it) { it.stop() } } } catch (_: Exception) { null }
            handler.post {
                if (active) return@post // a new session was started meanwhile: leave it running
                statusText = if (summary != null)
                    "Stopped. Session saved (${summary.optInt("frames")} frames): see History." else "Not monitoring"
                latest = null
                logDir = null
                releaseLocks()
                onChange?.invoke()
                stopSelf()
            }
        }.start()
    }

    // ------------------------------------------------------------------ pipeline callbacks (background threads)

    override fun onFrame(frame: LiveFrame) {
        latest = frame
        latestAtMs = System.currentTimeMillis()
        handler.post { if (active) handleFrame(frame) }
    }

    override fun onAlert(cmd: AlertCommand, pushed: Boolean) {
        handler.post { if (active) handleAlert(cmd, pushed) }
    }

    override fun onStatus(text: String) {
        statusText = text
        handler.post { onStatusText?.invoke(text) ?: onChange?.invoke() }
    }

    // ------------------------------------------------------------------ main thread

    private fun handleFrame(f: LiveFrame) {
        val sev = f.out.severity
        if (!simulatedStatuses) {
            val next = linkedMapOf(
                "Drowsiness" to statusOf(sev.drowsiness.level), "Yawning" to statusOf(sev.yawning.level),
                "Gaze deviation" to statusOf(sev.gazeDeviation.level), "Phone usage" to statusOf(sev.phoneUsage.level))
            statuses.putAll(next)
        }
        val tier = when {
            f.out.alertState == "UNAVAILABLE" -> "UNAVAILABLE"
            f.out.calibrationStatus == CALIBRATING -> "CALIBRATING"
            else -> displayTier(f.out.risk.tier)
        }
        if (tier != lastTier) {
            lastTier = tier
            if (tier != "CALIBRATING") SessionHistoryStore.recordRisk(this, sessionId, tier)
            refreshNotification()
            onChange?.invoke()
        }
        if (System.currentTimeMillis() - lastPublishMs >= PUBLISH_MS) publish(f)
        onFrame?.invoke(f)
    }

    private fun handleAlert(c: AlertCommand, pushed: Boolean) {
        val what = if (c.behaviors.isEmpty()) "Risk level" else c.behaviors.joinToString(", ") { behaviourName(it) }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        val severity = when (c.tier) { "HIGH" -> "High"; "MEDIUM" -> "Warning"; else -> "Low" }
        when (c.event) {
            "start", "escalate" -> {
                recent.add(0, Triple(what, time, c.tier))
                SessionHistoryStore.recordAlert(this, sessionId, what, c.tier)
                cloud?.record(what, severity, sessionId, test = false, push = pushed)
                alertCount++
                if (c.tier == "HIGH") highCount++
                val order = listOf("NORMAL", "LOW", "MEDIUM", "HIGH")
                if (order.indexOf(c.tier) > order.indexOf(maxLevel)) maxLevel = c.tier
                if (c.tier == "HIGH" || c.tier == "MEDIUM") phoneFeedback(c.tier, warningFor(c))
                if (c.tier == "HIGH") notifyAlert("HIGH risk: $what", warningFor(c), ACTIVE_ID + 2)
            }
            "unavailable" -> {
                recent.add(0, Triple("Monitoring unavailable (no camera picture)", time, "OFF"))
                lastTier = "UNAVAILABLE"
                SessionHistoryStore.recordRisk(this, sessionId, "UNAVAILABLE")
                refreshNotification()
                notifyAlert("No camera picture", "Monitoring is paused until the camera picture returns.", ACTIVE_ID + 3)
                speak("Camera picture lost.")
            }
        }
        while (recent.size > 20) recent.removeAt(recent.size - 1)
        onChange?.invoke()
    }

    /** Short spoken warning that names the behaviour (the buzzer gives the alarm itself). */
    private fun warningFor(c: AlertCommand): String {
        val first = c.behaviors.firstOrNull() ?: ""
        val high = c.tier == "HIGH"
        return when (first) {
            "phone_usage" -> "Put the phone down."
            "gaze_deviation" -> "Eyes on the road."
            "eye_closure" -> if (high) "Wake up. Please pull over and rest." else "Keep your eyes open."
            "yawning" -> if (high) "You look tired. Please take a break." else "You seem tired. Plan a break soon."
            "sudden_motion" -> "Drive carefully."
            else -> if (high) "High risk. Please take a break." else "Stay alert."
        }
    }

    /** Send live status (measured values) to Firebase; f = null: no picture yet / camera lost. */
    private fun publish(f: LiveFrame?) {
        lastPublishMs = System.currentTimeMillis()
        val c = cloud ?: return
        val trip = (lastPublishMs - sessionStarted).coerceAtLeast(0L) / 1000L
        val sensors = f?.out?.context
        val manual = manualLocation
        val gps = sensors?.latitude != null && sensors.longitude != null
        val lat = if (gps) sensors?.latitude else manual?.first
        val lng = if (gps) sensors?.longitude else manual?.second
        val source = if (gps) "gps" else if (manual != null) "manual" else null
        if (f == null) {
            c.publishLive(statuses, "Unknown", 0.0, statusText, "UNAVAILABLE", sessionId, trip, lat, lng, null, source)
            return
        }
        val out = f.out
        val risk = when {
            out.alertState == "UNAVAILABLE" -> "Unknown"
            out.calibrationStatus == CALIBRATING -> "Calibrating"
            else -> when (out.risk.tier) { "HIGH" -> "High risk"; "MEDIUM" -> "Medium risk"; "LOW" -> "Low risk"; else -> "No risk" }
        }
        val activeNow = out.behavior.active
        val event = when {
            out.alertState == "UNAVAILABLE" -> "No camera picture"
            out.calibrationStatus == CALIBRATING -> "Calibrating to the driver"
            activeNow.isNotEmpty() -> activeNow.joinToString(", ") { behaviourName(it) }
            out.behavior.drowsy -> out.behavior.drowsinessState.replace('_', ' ').replaceFirstChar { it.uppercase() }
            else -> "Normal"
        }
        c.publishLive(statuses, risk, out.risk.risk, event, out.alertState, sessionId, trip, lat, lng,
            sensors?.speedKmh?.takeIf { sensors.speedSource == "gps" }, source)
    }

    // ------------------------------------------------------------------ phone alerts

    private fun phoneFeedback(tier: String, message: String) {
        val s = settings ?: DeviceSettings(this)
        if (s.vibrateAlerts) vibrate(tier == "HIGH")
        if (s.speakAlerts) speak(message)
    }

    private fun refreshNotification() {
        if (!active) return
        getSystemService(NotificationManager::class.java).notify(ACTIVE_ID, activeNotification())
    }

    private fun activeNotification(): Notification {
        val launch = PendingIntent.getActivity(
            this, 0, Intent(this, LauncherActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val minutes = ((System.currentTimeMillis() - sessionStarted).coerceAtLeast(0L) / 60_000L)
        val state = when (lastTier) {
            "" -> "starting"
            "CALIBRATING" -> "calibrating"
            "UNAVAILABLE" -> "no camera picture"
            else -> "risk ${lastTier.lowercase(Locale.ROOT)}"
        }
        return NotificationCompat.Builder(this, CHANNEL_ACTIVE)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Driver monitoring is running")
            .setContentText("$minutes min · $state")
            .setContentIntent(launch)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Post a phone alert if notification permission is available. */
    private fun notifyAlert(title: String, message: String, id: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title).setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun vibrate(strong: Boolean) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val pattern = if (strong) longArrayOf(0, 300, 150, 300, 150, 500) else longArrayOf(0, 250)
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        else @Suppress("DEPRECATION") vibrator.vibrate(pattern, -1)
    }

    /** Speak immediately when ready, or queue text while TTS initializes. */
    /** Warnings use the alarm volume, so they are heard even when media is muted. */
    private val speechParams = android.os.Bundle().apply {
        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_ALARM)
    }

    private fun speak(message: String) {
        if (speechReady) speaker?.speak(message, TextToSpeech.QUEUE_FLUSH, speechParams, "driver_alert")
        else {
            queuedSpeech = message
            if (speaker == null) speaker = TextToSpeech(applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        speechReady = status == TextToSpeech.SUCCESS
        if (!speechReady) return
        speaker?.language = Locale.US
        queuedSpeech?.let { speaker?.speak(it, TextToSpeech.QUEUE_FLUSH, speechParams, "driver_alert") }
        queuedSpeech = null
    }

    // ------------------------------------------------------------------ keep the CPU and Wi-Fi awake

    private fun acquireLocks() {
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "esca:monitoring").apply {
                setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L)
            }
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi?.createWifiLock(mode, "esca:camera")?.apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        monitor?.let { m -> Thread { try { synchronized(m) { m.stop() } } catch (_: Exception) {} }.start() }
        monitor = null
        releaseLocks()
        cloud?.stop()
        speaker?.stop()
        speaker?.shutdown()
        super.onDestroy()
    }
}
