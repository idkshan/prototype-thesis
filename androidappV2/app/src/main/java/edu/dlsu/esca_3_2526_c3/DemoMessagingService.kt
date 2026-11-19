package edu.dlsu.esca_3_2526_c3

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class DemoMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["demo"] != "true") return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = "esca_3_2526_c3_demo_high"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                channel, "Simulated high-risk tests", NotificationManager.IMPORTANCE_HIGH
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
            .setContentTitle("SIMULATED high-risk alert")
            .setContentText(message.data["behavior"] ?: "Driver test event")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}
