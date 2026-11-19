package edu.dlsu.esca_3_2526_c3

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.ViewGroup
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class SupervisorActivity : Activity() {
    private data class Driver(
        val id: String, val name: String, val risk: String, val event: String,
        val tripSeconds: Long, val speedKph: Double?, val lat: Double?, val lng: Double?,
        val locationTime: Long, val locationSource: String?, val lastUpdate: Long
    )

    private val offlineAfterMs = 90_000L // The foreground driver service reports every 30 seconds.
    private lateinit var cloud: DemoCloud
    private var driversReference: DatabaseReference? = null
    private var driversListener: ValueEventListener? = null
    private var alertsReference: Query? = null
    private var alertsListener: ValueEventListener? = null
    private var drivers = emptyList<Driver>()
    private var liveAlerts = emptyList<Triple<String, String, String>>()
    private var selectedId: String? = null
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var mapView: WebView? = null
    private var mapPosition: Pair<Double, Double>? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ageLabels = mutableMapOf<String, TextView>()
    private var shownOffline = emptyMap<String, Boolean>()
    private val refreshAges = object : Runnable {
        override fun run() {
            if (!isFinishing && ::cloud.isInitialized && cloud.role == "supervisor") {
                val offline = drivers.associate { it.id to isOffline(it) }
                if (offline != shownOffline) showSupervisor() else updateAgeLabels()
            }
            handler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedId = intent.getStringExtra("driver_id")?.takeIf { it.isNotBlank() }
        window.statusBarColor = Ui.navy
        window.navigationBarColor = Ui.navy
        scroll = ScrollView(this).apply { setBackgroundColor(Ui.background) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@SupervisorActivity, 16), 0,
                Ui.dp(this@SupervisorActivity, 16), Ui.dp(this@SupervisorActivity, 24))
        }
        scroll.addView(content)
        setContentView(scroll)
        cloud = DemoCloud(this)
        cloud.onChange = roleChanged@{
            if (cloud.role == "driver") {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                return@roleChanged
            }
            if (cloud.role == "supervisor" && cloud.signedIn) {
                attachDrivers()
                if (cloud.ready) cloud.registerSupervisorPush()
            } else detachDrivers()
            if (!isFinishing) showSupervisor()
        }
        cloud.start()
        showSupervisor()
    }

    /** Notification taps can open the corresponding driver's own details page. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectedId = intent.getStringExtra("driver_id")?.takeIf { it.isNotBlank() }
        attachAlerts()
        showSupervisor()
    }

    override fun onResume() { super.onResume(); handler.post(refreshAges) }
    override fun onPause() { handler.removeCallbacks(refreshAges); super.onPause() }
    override fun onDestroy() {
        detachDrivers()
        (mapView?.parent as? ViewGroup)?.removeView(mapView)
        mapView?.destroy()
        mapView = null
        cloud.stop()
        super.onDestroy()
    }

    /** A supervisor reads the roster from Firebase, never from hardcoded sample drivers. */
    private fun attachDrivers() {
        if (driversListener != null || cloud.role != "supervisor") return
        driversReference = cloud.driversRef()
        driversListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val nextDrivers = snapshot.children.mapNotNull { node ->
                    val live = node.child("live")
                    if (!live.exists()) return@mapNotNull null
                    val id = node.key ?: return@mapNotNull null
                    if (!id.contains("@")) return@mapNotNull null // Ignore old UID-keyed records.
                    val lat = live.child("lat").getValue(Double::class.java)
                    val lng = live.child("lng").getValue(Double::class.java)
                    val valid = lat != null && lng != null && lat.isFinite() && lng.isFinite() &&
                            lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)
                    Driver(
                        id, live.child("name").getValue(String::class.java)
                            ?.takeIf { it.isNotBlank() } ?: live.child("email")
                            .getValue(String::class.java) ?: id.replace("-", ".").replace("%2D", "-"),
                        live.child("risk").getValue(String::class.java) ?: "Low risk",
                        live.child("event").getValue(String::class.java) ?: "Normal",
                        live.child("tripSeconds").getValue(Long::class.java) ?: 0L,
                        live.child("speedKph").getValue(Double::class.java),
                        if (valid) lat else null, if (valid) lng else null,
                        live.child("locationTimestampMs").getValue(Long::class.java) ?: 0L,
                        live.child("locationSource").getValue(String::class.java),
                        live.child("timestampMs").getValue(Long::class.java) ?: 0L
                    )
                }.sortedBy { it.name.lowercase(Locale.ROOT) }
                val oldList = drivers.map { listOf(it.id, it.name, it.risk, it.event, isOffline(it).toString()) }
                val newList = nextDrivers.map { listOf(it.id, it.name, it.risk, it.event, isOffline(it).toString()) }
                drivers = nextDrivers
                if (!isFinishing) {
                    if (selectedId == null && oldList == newList) updateAgeLabels()
                    else showSupervisor()
                }
            }
            override fun onCancelled(error: DatabaseError) {
                cloud.toast("Driver list unavailable: ${error.message}")
            }
        }
        driversReference!!.addValueEventListener(driversListener!!)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
        cloud.registerSupervisorPush()
        attachAlerts()
    }

    /** Attach an alerts query only to the currently selected driver's events. */
    private fun attachAlerts() {
        alertsListener?.let { alertsReference?.removeEventListener(it) }
        alertsReference = null
        alertsListener = null
        liveAlerts = emptyList()
        val id = selectedId ?: return
        if (cloud.role != "supervisor") return
        alertsReference = cloud.eventsRef(id).orderByChild("timestampMs").limitToLast(30)
        alertsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                data class Event(val name: String, val ms: Long, val level: String, val session: String)
                val rows = mutableListOf<Event>()
                snapshot.children.forEach { event ->
                    val name = event.child("behavior").getValue(String::class.java) ?: "Event"
                    val level = event.child("severity").getValue(String::class.java) ?: "Warning"
                    val ms = event.child("timestampMs").getValue(Long::class.java) ?: 0L
                    val session = event.child("sessionId").getValue(String::class.java) ?: ""
                    val prior = rows.indexOfLast { it.session == session && session.isNotBlank() &&
                            it.name == name && Ui.riskLevel(it.level) == "MEDIUM" &&
                            Ui.riskLevel(level) == "HIGH" && ms - it.ms in 0..12_000L }
                    if (prior >= 0) rows[prior] = rows[prior].copy(level = level)
                    else rows.add(Event(name, ms, level, session))
                }
                liveAlerts = rows.asReversed().take(15).map {
                    Triple(it.name, if (it.ms == 0L) "?" else
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.ms)), it.level)
                }
                if (!isFinishing && selectedId == id) showSupervisor()
            }
            override fun onCancelled(error: DatabaseError) {
                cloud.toast("Alerts unavailable for selected driver: ${error.message}")
            }
        }
        alertsReference!!.addValueEventListener(alertsListener!!)
    }

    private fun detachDrivers() {
        driversListener?.let { driversReference?.removeEventListener(it) }
        alertsListener?.let { alertsReference?.removeEventListener(it) }
        driversReference = null; driversListener = null
        alertsReference = null; alertsListener = null
        drivers = emptyList(); liveAlerts = emptyList()
    }

    private fun isOffline(driver: Driver): Boolean = driver.lastUpdate <= 0L ||
            System.currentTimeMillis() - driver.lastUpdate > offlineAfterMs

    private fun ageText(time: Long): String {
        if (time <= 0L) return "Last update: Never"
        val seconds = ((System.currentTimeMillis() - time).coerceAtLeast(0L)) / 1000L
        return when {
            seconds < 60 -> "Last update: ${seconds} s ago"
            seconds < 3600 -> "Last update: ${seconds / 60} min ago"
            else -> "Last update: ${seconds / 3600} h ago"
        }
    }

    private fun updateAgeLabels() {
        drivers.forEach { driver ->
            ageLabels[driver.id]?.apply {
                text = ageText(driver.lastUpdate) + if (isOffline(driver)) " · Offline" else ""
                setTextColor(if (isOffline(driver)) Ui.muted else Ui.green)
            }
        }
    }

    private fun openDriver(id: String) {
        selectedId = id
        attachAlerts()
        showSupervisor()
    }

    override fun onBackPressed() {
        if (selectedId != null) {
            selectedId = null
            attachAlerts()
            showSupervisor()
        } else super.onBackPressed()
    }

    private fun showSupervisor() {
        val scrollY = if (::scroll.isInitialized) scroll.scrollY else 0
        content.removeAllViews()
        ageLabels.clear()
        content.addView(Ui.header(this, "Supervisor Dashboard"))
        val account = Ui.card(this)
        account.addView(Ui.note(this, cloud.connectionLabel))
        account.addView(Ui.note(this, "Cloud data restricted to supervisor accounts."))
        account.addView(Button(this).apply {
            text = if (cloud.signedIn) "Sign out · ${cloud.email}" else "Account / Sign in"
            isAllCaps = false
            setOnClickListener {
                if (cloud.signedIn) { detachDrivers(); cloud.signOut() }
                startActivity(Intent(this@SupervisorActivity, LauncherActivity::class.java))
                finish()
            }
        })
        content.addView(account, Ui.blockParams(this))
        if (cloud.role != "supervisor") {
            content.addView(Ui.note(this,
                "Supervisor data unavailable until you sign in with an account assigned the supervisor role."))
            return
        }
        shownOffline = drivers.associate { it.id to isOffline(it) }
        val id = selectedId
        if (id == null) showDriverList() else showDriverDetails(id)
        updateAgeLabels()
        scroll.post { if (!isFinishing) scroll.scrollTo(0, scrollY) }
    }

    private fun showDriverList() {
        val monitor = Ui.card(this)
        monitor.addView(Ui.title(this, "Live Monitoring"))
        if (drivers.isEmpty()) {
            monitor.addView(Ui.note(this, "No drivers have published data yet. Start monitoring with a driver account."))
        }
        drivers.forEach { driver ->
            val offline = isOffline(driver)
            val row = Ui.monitorRow(this, driver.name,
                if (offline) "OFFLINE" else Ui.riskLevel(driver.risk),
                if (offline) "No recent data" else driver.event,
                if (offline) Ui.muted else Ui.riskColor(driver.risk), false)
            row.setOnClickListener { openDriver(driver.id) }
            monitor.addView(row)
            ageLabels[driver.id] = Ui.note(this, "").also { monitor.addView(it) }
        }
        content.addView(monitor, Ui.blockParams(this))
    }

    private fun showDriverDetails(id: String) {
        content.addView(Button(this).apply {
            text = "‹  All drivers"
            isAllCaps = false
            setOnClickListener { openDriverList() }
        }, Ui.blockParams(this))
        val driver = drivers.firstOrNull { it.id == id }
        if (driver == null) {
            content.addView(Ui.note(this, "Waiting for this driver's first Firebase update."))
            return
        }

        val details = Ui.card(this)
        details.addView(Ui.title(this, driver.name))
        val offline = isOffline(driver)
        details.addView(Ui.pair(this, "Connection", if (offline) "Offline" else "Receiving updates"))

        ageLabels[id] = Ui.note(this, "").also { details.addView(it) }
        details.addView(Ui.pair(this, "Risk", if (offline) "Last known · ${Ui.riskLevel(driver.risk)}"
        else Ui.riskLevel(driver.risk)))

        details.addView(Ui.pair(this, "Current status", driver.event))
        details.addView(Ui.pair(this, "Driving time", "${driver.tripSeconds / 60} min ${driver.tripSeconds % 60} s (simulated)"))
        details.addView(Ui.pair(this, "Speed", driver.speedKph?.let {
            String.format(Locale.US, "%.1f km/h", it)
        } ?: "Unavailable"))

        details.addView(Ui.title(this, "Driver Location"))
        val lat = driver.lat
        val lng = driver.lng
        if (lat == null || lng == null || driver.locationTime <= 0L) {
            details.addView(Ui.note(this, "No location coordinates available yet."))
        } else {
            val locationAge = (System.currentTimeMillis() - driver.locationTime).coerceAtLeast(0L)
            val locationLabel = if (driver.locationSource == "manual") "manual test location" else "GPS position"

            details.addView(Ui.note(this, if (locationAge > 60_000L)
                "Last known $locationLabel · ${locationAge / 1000} s old" else "Latest $locationLabel"))
            details.addView(Ui.pair(this, "Coordinates",
                String.format(Locale.US, "%.6f, %.6f", lat, lng)))

            // OpenStreetMap's interactive embedded map, centered on *measured* GPS only.
            val bbox = String.format(Locale.US, "%.5f%%2C%.5f%%2C%.5f%%2C%.5f",
                lng - 0.006, lat - 0.004, lng + 0.006, lat + 0.004)
            val marker = String.format(Locale.US, "%.6f%%2C%.6f", lat, lng)
            val map = mapView ?: WebView(this).apply {
                settings.javaScriptEnabled = true // Required by OpenStreetMap's embedded map.
                webViewClient = WebViewClient()
            }.also { mapView = it }

            // Reuse the same WebView as status timestamps change, avoiding map reloads.
            (map.parent as? ViewGroup)?.removeView(map)
            if (mapPosition != Pair(lat, lng)) {
                mapPosition = Pair(lat, lng)
                map.loadUrl("https://www.openstreetmap.org/export/embed.html?bbox=$bbox&layer=mapnik&marker=$marker")
            }

            details.addView(map, LinearLayout.LayoutParams(-1, Ui.dp(this, 230)))
            details.addView(Ui.note(this, "Map © OpenStreetMap"))
        }
        content.addView(details, Ui.blockParams(this))
        val alerts = Ui.card(this)
        alerts.addView(Ui.title(this, "Recent Alerts · ${driver.name}"))
        if (liveAlerts.isEmpty()) alerts.addView(Ui.note(this, "No Firebase alerts for this driver yet."))
        liveAlerts.take(10).forEach { (event, time, severity) ->
            alerts.addView(Ui.alert(this, Ui.alertCause(event), time,
                Ui.riskLevel(severity), Ui.riskColor(severity)))
        }
        content.addView(alerts, Ui.blockParams(this))
    }

    private fun openDriverList() {
        selectedId = null
        attachAlerts()
        showSupervisor()
    }
}
