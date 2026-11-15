package edu.dlsu.esca_3_2526_c3

import android.app.Activity
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Button
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import java.text.DateFormat
import java.util.Date
import android.widget.LinearLayout
import android.widget.ScrollView

/** A separate launch entry. No navigation from MainActivity leads here. */
class SupervisorActivity : Activity() {
    private data class Driver(
        val name: String, val risk: String, val event: String,
        val trip: String, val speed: String, val position: String, val color: Int
    )

    private val drivers = listOf(
        Driver("Driver 1", "High risk", "Using phone", "48 minutes", "61 km/h", "18.35, 121.04", Ui.red),
        Driver("Driver 2", "Medium risk", "Yawning", "32 minutes", "45 km/h", "18.31, 121.07", Ui.amber),
        Driver("Driver 3", "Low risk", "Gaze deviation", "15 minutes", "27 km/h", "18.28, 121.09", Ui.green)
    )
    private lateinit var cloud: DemoCloud
    private var liveReference: DatabaseReference? = null
    private var liveListener: ValueEventListener? = null
    private var alertsReference: Query? = null
    private var alertsListener: ValueEventListener? = null
    private var liveRisk = "Low risk"
    private var liveEvent = "Normal"
    private var liveDuration = "0 minutes"
    private var liveSpeed = "No data yet"
    private var livePosition = "No data yet"
    private var liveSource = ""
    private var liveAlarm = "-"
    private var lastUpdate = "Never"
    private var liveAlerts = listOf<Triple<String, String, String>>()
    private var selected = 0
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.navy
        window.navigationBarColor = Ui.navy
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.background) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@SupervisorActivity, 16), 0, Ui.dp(this@SupervisorActivity, 16), Ui.dp(this@SupervisorActivity, 24))
        }
        scroll.addView(content)
        setContentView(scroll)
        cloud = DemoCloud(this)
        cloud.onChange = {
            if (cloud.role == "supervisor" && cloud.signedIn) attachLive()
            else detachLive()
            if (!isFinishing) showSupervisor()
        }
        cloud.start()
        showSupervisor()
    }

    override fun onDestroy() { detachLive(); cloud.stop(); super.onDestroy() }

    private fun detachLive() {
        liveListener?.let { liveReference?.removeEventListener(it) }
        alertsListener?.let { alertsReference?.removeEventListener(it) }
        liveListener = null; alertsListener = null
        liveReference = null; alertsReference = null
    }

    private fun attachLive() {
        if (liveListener != null || cloud.role != "supervisor") return
        liveReference = cloud.liveRef()
        liveListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                liveRisk = snapshot.child("risk").getValue(String::class.java) ?: "Low risk"
                liveEvent = snapshot.child("event").getValue(String::class.java) ?: "Normal"
                val seconds = snapshot.child("tripSeconds").getValue(Long::class.java) ?: 0L
                val simulated = snapshot.child("simulated").getValue(Boolean::class.java) ?: true
                liveSource = if (simulated) "simulated test" else "live from the driver's phone"
                liveDuration = "${seconds / 60} minutes" + if (simulated) " (simulated)" else ""
                val speed = snapshot.child("speedKph").getValue(Double::class.java)
                val speedSource = snapshot.child("speedSource").getValue(String::class.java) ?: ""
                liveSpeed = when {
                    simulated -> "Not collected · demo"
                    speed != null -> "%.0f km/h (GPS)".format(speed)
                    speedSource == "assumed" -> "No GPS fix (assumed moving)"
                    else -> "No data"
                }
                liveAlarm = when (snapshot.child("alertState").getValue(String::class.java)) {
                    "ALERTING" -> "Sounding"; "CLEARED" -> "Just cleared"; "MONITORING" -> "Quiet"
                    "UNAVAILABLE" -> "No camera picture"; else -> "-"
                }
                val lat = snapshot.child("lat").getValue(Double::class.java)
                val lon = snapshot.child("lon").getValue(Double::class.java)
                livePosition = if (lat != null && lon != null) "%.5f, %.5f".format(lat, lon) else if (simulated) "Not collected · demo" else "No GPS fix"
                val timestamp = snapshot.child("timestampMs").getValue(Long::class.java) ?: 0L
                lastUpdate = if (timestamp == 0L) "Never" else DateFormat.getDateTimeInstance().format(Date(timestamp))
                if (!isFinishing) showSupervisor()
            }
            override fun onCancelled(error: DatabaseError) { cloud.toast("Live read denied: ${error.message}") }
        }
        liveReference!!.addValueEventListener(liveListener!!)
        alertsReference = cloud.eventsRef().orderByChild("timestampMs").limitToLast(30)
        alertsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val rows = mutableListOf<Triple<String, String, String>>()
                snapshot.children.forEach { event ->
                    val simulated = event.child("simulated").getValue(Boolean::class.java) ?: true
                    val what = (event.child("behavior").getValue(String::class.java) ?: "Event") + if (simulated) " · TEST" else ""
                    val severity = event.child("severity").getValue(String::class.java) ?: "Warning"
                    val ms = event.child("timestampMs").getValue(Long::class.java) ?: 0L
                    rows.add(Triple(what, if (ms == 0L) "?" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms)), severity))
                }
                liveAlerts = rows.reversed().take(15)
                if (!isFinishing) showSupervisor()
            }
            override fun onCancelled(error: DatabaseError) { cloud.toast("Alert read denied: ${error.message}") }
        }
        alertsReference!!.addValueEventListener(alertsListener!!)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
        }
        cloud.registerSupervisorPush()
    }


    private fun showSupervisor() {
        content.removeAllViews()
        content.addView(Ui.header(this, "Supervisor Dashboard"))
        val account = Ui.card(this)
        account.addView(Ui.note(this, cloud.connectionLabel))
        account.addView(Ui.note(this, "Cloud data restricted to accounts with the supervisor role."))
        account.addView(Button(this).apply {
            text = if (cloud.signedIn) "Sign out · ${cloud.email}" else "Sign in (Supervisor researcher account)"
            isAllCaps = false
            setOnClickListener { if (cloud.signedIn) { detachLive(); cloud.signOut() } else cloud.signInDialog("supervisor") }
        })
        content.addView(account, Ui.blockParams(this))
        if (cloud.role != "supervisor") {
            content.addView(Ui.note(this, "Supervisor data unavailable until you sign in with a researcher account assigned the supervisor role."))
            return
        }
        content.addView(Ui.note(this, "Driver 1 - Firebase${if (liveSource.isNotEmpty()) " ($liveSource)" else ""}, Drivers 2 and 3 - still placeholders"))
        val monitor = Ui.card(this)
        monitor.addView(Ui.title(this, "Live Monitoring"))
        monitor.addView(Ui.note(this, "Select a driver"))
        drivers.forEachIndexed { index, driver ->
            val risk = if (index == 0) liveRisk else driver.risk
            val event = if (index == 0) liveEvent else driver.event
            val color = if (index == 0) (if (liveRisk.startsWith("High")) Ui.red else if (liveRisk.startsWith("Medium")) Ui.amber else Ui.green) else driver.color
            val row = Ui.monitorRow(this, driver.name, risk, event, color, index == selected)
            row.setOnClickListener { selected = index; showSupervisor() }
            monitor.addView(row)
        }
        content.addView(monitor, Ui.blockParams(this))

        val driver = drivers[selected]
        val details = Ui.card(this)
        details.addView(Ui.title(this, "Selected Driver"))
        details.addView(Ui.pair(this, "Driver", driver.name))
        details.addView(Ui.pair(this, "Risk Level", if (selected == 0) liveRisk else driver.risk))
        details.addView(Ui.pair(this, "Current status", if (selected == 0) liveEvent else driver.event))
        details.addView(Ui.pair(this, "Trip duration", if (selected == 0) liveDuration else driver.trip))
        if (selected == 0) details.addView(Ui.pair(this, "Driver's alarm", liveAlarm))
        details.addView(Ui.pair(this, "Speed", if (selected == 0) liveSpeed else "${driver.speed} (sample)"))
        details.addView(Ui.pair(this, "Location", if (selected == 0) livePosition else "${driver.position} (sample)"))
        if (selected == 0) details.addView(Ui.note(this, "Last Firebase update: $lastUpdate"))
        details.addView(Ui.space(this, 10))
        details.addView(Ui.placeholder(this, "MAP", "GPS map for ${driver.name} goes here", 150))
        content.addView(details, Ui.blockParams(this))

        val alerts = Ui.card(this)
        alerts.addView(Ui.title(this, "Recent Alerts"))
        if (liveAlerts.isEmpty()) alerts.addView(Ui.note(this, "No Firebase events yet."))
        liveAlerts.take(10).forEach { (event, time, severity) ->
            alerts.addView(Ui.alert(this, "Driver 1 · $event", time, severity.uppercase(), if (severity == "High") Ui.red else if (severity == "Low") Ui.green else Ui.amber))
        }
        content.addView(alerts, Ui.blockParams(this))
    }
}
