package edu.dlsu.esca_3_2526_c3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.util.Locale
import java.util.UUID

/** User-started foreground timer and demo-alert presenter */
class DriverMonitoringService : Service(), TextToSpeech.OnInitListener {
    // Shared commands and session state used by the driver activity and this service.
    companion object {
        // distinguish start, stop, and HIGH alerts.
        const val START = "edu.dlsu.esca_3_2526_c3.START_MONITORING"
        const val STOP = "edu.dlsu.esca_3_2526_c3.STOP_MONITORING"
        const val HIGH_ALERT = "edu.dlsu.esca_3_2526_c3.DEMO_HIGH_ALERT"

        // Preference keys, notification channels, and the two-hour reminder interval.
        private const val PREFS = "driver_monitoring_v1"
        private const val CHANNEL_ACTIVE = "driver_demo_monitoring"
        private const val CHANNEL_ALERT = "driver_demo_alerts"
        private const val ACTIVE_ID = 501
        private const val BREAK_MS = 2 * 60 * 60 * 1000L

        // In-memory demo risk levels and recent events; these are not sensor readings.
        val statuses = linkedMapOf(
            "Drowsiness" to "Normal", "Yawning" to "Normal",
            "Gaze deviation" to "Normal", "Phone usage" to "Normal"
        )
        val recent = mutableListOf<Triple<String, String, String>>()

        // Current session identity and start time are persisted for service recreation.
        var sessionId = ""
            private set
        var sessionStarted = 0L
            private set
        var active = false
            private set

        // The main-thread handler resets manually simulated risks after 12 seconds.
        private var restored = false
        private val resetHandler = Handler(Looper.getMainLooper())

        /** Update one behavior and schedule its automatic return to Normal. */
        fun updateSimulatedRisk(context: Context, name: String, level: String) {
            statuses[name] = level

            if (active) SessionHistoryStore.record(context, sessionId, name, level, statuses)

            onChange?.invoke()

            if (level != "Normal") resetHandler.postDelayed({
                if (active && statuses[name] == level) {
                    statuses[name] = "Normal"
                    SessionHistoryStore.record(context.applicationContext, sessionId, name, "Normal", statuses)
                    onChange?.invoke()
                }
            }, 12_000L)
        }

        // Optional callback for updating the visible driver activity.
        var onChange: (() -> Unit)? = null

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

        /** Begin a new simulated session and clear previous test events. */
        private fun begin(context: Context) {
            restore(context)
            if (active) return
            active = true
            sessionStarted = System.currentTimeMillis()
            sessionId = "DEMO-" + UUID.randomUUID()
            statuses.keys.forEach { statuses[it] = "Normal" }
            resetHandler.removeCallbacksAndMessages(null)
            recent.clear()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("active", true).putLong("started", sessionStarted)
                .putString("session", sessionId).putLong("lastBreak", 0L).apply()

            SessionHistoryStore.start(context, sessionId, sessionStarted)
            onChange?.invoke()
        }

        /** Stop the session, clear pending risk resets, and persist inactivity. */
        private fun finish(context: Context) {
            SessionHistoryStore.stop(context, sessionId)

            active = false
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("active", false).apply()
            resetHandler.removeCallbacksAndMessages(null)
            statuses.keys.forEach { statuses[it] = "Normal" }
            onChange?.invoke()
        }
    }

    // Service-owned timer and lazily initialized text-to-speech engine.
    private val handler = Handler(Looper.getMainLooper())
    private var speaker: TextToSpeech? = null
    private var speechReady = false
    private var queuedSpeech: String? = null

    /** Refresh elapsed time and demo cloud status every 30 seconds. */
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            val elapsed = (System.currentTimeMillis() - sessionStarted).coerceAtLeast(0L)

            // Each completed two-hour interval should generate only one reminder.
            val interval = elapsed / BREAK_MS
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (interval > 0 && interval > prefs.getLong("lastBreak", 0L)) {
                prefs.edit().putLong("lastBreak", interval).apply()
                val hours = interval * 2
                val phrase = "You've been driving for $hours hours. Please take a break."
                notifyAlert("Break reminder", phrase, ACTIVE_ID + 1)
                speak(phrase)
            }

            getSystemService(NotificationManager::class.java).notify(ACTIVE_ID, activeNotification())

            SessionHistoryStore.checkpoint(this@DriverMonitoringService, sessionId)
            publishDemo()
            handler.postDelayed(this, 30_000L)
        }
    }

    // This is a started service; activities do not bind to it.
    override fun onBind(intent: Intent?): IBinder? = null

    /** Register separate channels for the ongoing session and urgent demo alerts. */
    override fun onCreate() {
        super.onCreate()
        restore(this)
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ACTIVE, "Driver demo session", NotificationManager.IMPORTANCE_LOW
            ))
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ALERT, "Driver demo safety alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply { enableVibration(true); lightColor = Color.RED; enableLights(true) })
        }
    }

    /** Handle service commands and ensure the persistent notification is shown. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // A deliberate stop ends the session and removes its notification.
            STOP -> {
                finish(this)
                handler.removeCallbacks(tick)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            START -> begin(this)

            // Only a running demo session can emit a simulated HIGH alert.
            HIGH_ALERT -> {
                if (active) {
                    vibrate()
                    speak("Please take a break")
                    notifyAlert("Simulated high-risk alert", "Please take a break", ACTIVE_ID + 2)
                }
            }
            null -> if (!active) { stopSelf(); return START_NOT_STICKY }
        }
        if (active) {
            startForeground(ACTIVE_ID, activeNotification())
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
        return START_STICKY
    }

    /** Keep simulated live status and trip duration current after screen rotation/backgrounding. */
    private fun publishDemo() {
        if (FirebaseApp.getApps(this).isEmpty()) return
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val email = user.email?.takeIf { it.isNotBlank() } ?: return
        val emailKey = firebaseEmailKey(email)
        val db = FirebaseDatabase.getInstance(
            "https://esca-3-2526-c3-testing-default-rtdb.asia-southeast1.firebasedatabase.app"
        )
        // Check the Firebase role before publishing test-only driver data.
        db.getReference("roles").child(user.uid).get().addOnSuccessListener { role ->
            if (!active || role.getValue(String::class.java) != "driver") return@addOnSuccessListener
            val high = statuses.values.any { it == "High" }
            val medium = statuses.values.any { it == "Warning" }
            val headline = statuses.entries.firstOrNull { it.value == "High" }
                ?: statuses.entries.firstOrNull { it.value == "Warning" }
            db.getReference("demo/drivers").child(emailKey).child("live").updateChildren(mapOf(
                "name" to (user.displayName?.takeIf { it.isNotBlank() } ?: email), "email" to email,
                "risk" to (if (high) "High risk" else if (medium) "Medium risk" else "Low risk"),
                "event" to (headline?.key ?: "Normal"), "statuses" to statuses.toMap(),
                "timestampMs" to System.currentTimeMillis(), "sessionId" to sessionId,
                "tripSeconds" to (System.currentTimeMillis() - sessionStarted).coerceAtLeast(0L) / 1000,
                "simulated" to true, "source" to "manual_test"
            ))
        }
    }

    /** Build the persistent foreground notification and its return-to-app action. */
    private fun activeNotification(): Notification {
        val launch = PendingIntent.getActivity(
            this, 0, Intent(this, LauncherActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val minutes = ((System.currentTimeMillis() - sessionStarted).coerceAtLeast(0L) / 60_000L)
        return NotificationCompat.Builder(this, CHANNEL_ACTIVE)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Driver monitoring service is running")
            .setContentText("$minutes min")
            .setContentIntent(launch)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Post a supplemental phone alert if notification permission is available. */
    private fun notifyAlert(title: String, message: String, id: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title).setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).build()
        // Phone notifications are supplementary; no prototype buzzer API exists in this commit.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    /** Use a short vibration pattern, with compatibility for older Android versions. */
    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 500), -1))
        } else {
            vibrator.vibrate(longArrayOf(0, 300, 150, 300, 150, 500), -1)
        }
    }

    /** Speak immediately when ready, or queue text while TTS initializes. */
    private fun speak(message: String) {
        if (speechReady) speaker?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "driver_alert")
        else {
            queuedSpeech = message
            if (speaker == null) speaker = TextToSpeech(applicationContext, this)
        }
    }


    /** Complete text-to-speech initialization and play any queued warning. */
    override fun onInit(status: Int) {
        speechReady = status == TextToSpeech.SUCCESS
        if (!speechReady) return
        speaker?.language = Locale.US
        queuedSpeech?.let { speaker?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "driver_alert") }
        queuedSpeech = null
    }

    /** Release timer callbacks and speech resources when the service is destroyed. */
    override fun onDestroy() {
        handler.removeCallbacks(tick)
        speaker?.stop()
        speaker?.shutdown()
        super.onDestroy()
    }
}
