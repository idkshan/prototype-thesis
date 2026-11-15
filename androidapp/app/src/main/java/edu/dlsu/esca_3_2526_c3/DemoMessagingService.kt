package edu.dlsu.esca_3_2526_c3

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Supervisor push: real HIGH alerts from the driver's phone, or simulated test events. */
class DemoMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val simulated = message.data["demo"] == "true"
        val live = message.data["source"] == "device"
        if (!simulated && !live) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = if (live) "esca_3_2526_c3_live_high" else "esca_3_2526_c3_demo_high"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                channel, if (live) "High-risk driver alerts" else "Simulated high-risk tests", NotificationManager.IMPORTANCE_HIGH
            ))
        }
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(if (live) "HIGH-risk alert · Driver 1" else "SIMULATED high-risk alert")
            .setContentText(message.data["behavior"] ?: "Driver 1 event")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}
