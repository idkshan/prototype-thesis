package edu.dlsu.esca_3_2526_c3

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class AlertMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        // Switched off in the supervisor's Settings (the token is also removed from Firebase).
        if (!getSharedPreferences("supervisor_prefs", MODE_PRIVATE).getBoolean("push", true)) return
        // Real HIGH alerts (test = "false") and developer-mode tests (test = "true").
        val test = message.data["test"] == "true"
        if (!test && message.data["source"] != "device") return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = if (test) "danteye_test_high" else "danteye_high"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                channel, if (test) "Test HIGH alerts" else "Driver HIGH-risk alerts", NotificationManager.IMPORTANCE_HIGH
            ))
        }
        val driverId = message.data["driverId"] ?: "" // Email-based Firebase key from the notification.
        val openDriver = Intent(this, SupervisorActivity::class.java).apply {
            putExtra("driver_id", driverId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openIntent = PendingIntent.getActivity(
            this, driverId.hashCode(), openDriver,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(if (test) "TEST high-risk alert" else "HIGH-risk alert: ${message.data["driverName"] ?: "driver"}")
            .setContentText(message.data["behavior"] ?: if (test) "Driver test event" else "Driver alert")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}
