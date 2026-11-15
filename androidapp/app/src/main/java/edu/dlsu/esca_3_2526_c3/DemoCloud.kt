package edu.dlsu.esca_3_2526_c3

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/**
 * A manually provisioned, role-restricted Firebase test space. Not participant data.
 * Events come from the detection pipeline (source "device") or from the manual test
 * buttons (source "manual_test", simulated). Writes made without signal are queued on
 * the phone and sent when the connection is back.
 */
class DemoCloud(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("firebase_demo_v1", Context.MODE_PRIVATE)
    private var connectionRef: DatabaseReference? = null
    private var connectionListener: ValueEventListener? = null
    private var roleRef: DatabaseReference? = null
    private var roleListener: ValueEventListener? = null
    private var online = false
    var role = ""
        private set
    val configured: Boolean get() = FirebaseApp.getApps(activity).isNotEmpty()
    val signedIn: Boolean get() = configured && FirebaseAuth.getInstance().currentUser != null
    val email: String get() = if (signedIn) FirebaseAuth.getInstance().currentUser?.email ?: "" else ""
    val ready: Boolean get() = online && role.isNotBlank() && signedIn
    val lastSync: Long get() = prefs.getLong("lastSyncMs", 0L)
    val pendingCount: Int get() = readPending().length()
    val connectionLabel: String get() = when {
        !configured -> "Firebase not configured (copy google-services.json)"
        !signedIn -> "Sign in to Firebase test account"
        role.isEmpty() -> "Role not assigned or not readable"
        online -> "Firebase connected as $role"
        else -> "Remote monitoring unavailable · offline"
    }

    var onChange: (() -> Unit)? = null

    private fun db() = FirebaseDatabase.getInstance("https://esca-3-2526-c3-testing-default-rtdb.asia-southeast1.firebasedatabase.app")
    private fun uid() = FirebaseAuth.getInstance().currentUser!!.uid
    fun liveRef(): DatabaseReference = db().getReference("demo/drivers/driver1/live")
    fun eventsRef(): DatabaseReference = db().getReference("demo/drivers/driver1/events")

    fun start() {
        if (!configured) { onChange?.invoke(); return }
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) { onChange?.invoke(); return }
        roleRef = db().getReference("roles").child(uid())
        roleListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                role = snapshot.getValue(String::class.java) ?: ""
                android.util.Log.d("FirebaseRole", "UID: ${uid()}, Role: $role, Exists: ${snapshot.exists()}")
                if (!snapshot.exists()) toast("No role assigned to UID: ${uid()}")
                onChange?.invoke()
                if (role == "driver" && online) flush()
            }
            override fun onCancelled(error: DatabaseError) {
                role = ""
                android.util.Log.e("FirebaseRole", "Role read failed: ${error.message}")
                toast("Firebase role error: ${error.message}")
                onChange?.invoke()
            }
        }
        roleRef!!.addValueEventListener(roleListener!!)
        connectionRef = db().getReference(".info/connected")
        connectionListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                online = snapshot.getValue(Boolean::class.java) == true
                onChange?.invoke()
                if (online && role == "driver") flush()
            }
            override fun onCancelled(error: DatabaseError) { online = false; onChange?.invoke() }
        }
        connectionRef!!.addValueEventListener(connectionListener!!)
        onChange?.invoke()
    }

    fun stop() {
        connectionListener?.let { connectionRef?.removeEventListener(it) }
        roleListener?.let { roleRef?.removeEventListener(it) }
        connectionListener = null; roleListener = null
        connectionRef = null; roleRef = null
    }

    fun signInDialog(expectedRole: String) {
        if (!configured) { toast("Add app/google-services.json first, then rebuild."); return }
        val emailInput = EditText(activity).apply { hint = "Researcher email"; inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val passInput = EditText(activity).apply { hint = "Password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 10, 36, 5)
            addView(emailInput); addView(passInput)
        }
        AlertDialog.Builder(activity).setTitle("Sign in as $expectedRole")
            .setMessage("Use an account created in Firebase Authentication and assigned the '$expectedRole' role.")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Sign in") { _, _ ->
                val email = emailInput.text.toString().trim()
                val pw = passInput.text.toString()
                if (email.isBlank() || pw.isBlank()) { toast("Enter email and password"); return@setPositiveButton }
                FirebaseAuth.getInstance().signInWithEmailAndPassword(email, pw)
                    .addOnSuccessListener { stop(); role = ""; start(); toast("Signed in. Checking role …") }
                    .addOnFailureListener { toast("Sign-in failed: ${it.localizedMessage}") }
            }.show()
    }

    fun signOut() {
        stop(); if (configured) FirebaseAuth.getInstance().signOut()
        role = ""; online = false; onChange?.invoke()
    }

    fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private fun readPending(): JSONArray = try { JSONArray(prefs.getString("pending", "[]")) } catch (_: Exception) { JSONArray() }
    private fun storePending(a: JSONArray) { prefs.edit().putString("pending", a.toString()).apply(); onChange?.invoke() }

    /** One alert event. Manual UI tests are marked simulated; pipeline events have source "device". */
    fun record(behavior: String, severity: String, sessionId: String, simulated: Boolean = true,
               source: String = "manual_test", push: Boolean = false) {
        val event = JSONObject()
        val now = System.currentTimeMillis()
        event.put("eventId", UUID.randomUUID().toString())
        event.put("sessionId", sessionId)
        event.put("behavior", behavior)
        event.put("severity", severity)
        event.put("timestampMs", now)
        event.put("source", source)
        event.put("simulated", simulated)
        event.put("push", push) // the alert state machine's push: once per HIGH alert, at most one per minute
        event.put("livePushEligible", online && role == "driver")
        val queue = readPending(); queue.put(event); storePending(queue)
        if (ready && role == "driver") flush()
    }

    fun localEvents(): List<JSONObject> = readPending().let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.reversed()
    }

    /** Live status from the detection pipeline (Driver 1 on the supervisor screen). */
    fun publishLive(statuses: Map<String, String>, risk: String, event: String, sessionId: String, tripSeconds: Long,
                    speedKph: Double?, speedSource: String, lat: Double?, lon: Double?, alertState: String) {
        if (!ready || role != "driver") return
        val live = mutableMapOf<String, Any>(
            "name" to "Driver 1", "risk" to risk, "event" to event, "statuses" to statuses,
            "timestampMs" to System.currentTimeMillis(), "sessionId" to sessionId, "tripSeconds" to tripSeconds,
            "speedSource" to speedSource, "alertState" to alertState, "simulated" to false, "source" to "device"
        )
        if (speedKph != null) live["speedKph"] = speedKph
        if (lat != null && lon != null) { live["lat"] = lat; live["lon"] = lon }
        liveRef().setValue(live)
    }

    fun publish(statuses: Map<String, String>, sessionId: String, tripSeconds: Long) {
        if (!ready || role != "driver") return
        val severe = statuses.values.any { it == "High" }
        val medium = statuses.values.any { it == "Warning" }
        val headline = statuses.entries.firstOrNull { it.value == "High" }
            ?: statuses.entries.firstOrNull { it.value == "Warning" }
        liveRef().setValue(mapOf(
            "name" to "Driver 1", "risk" to (if (severe) "High risk" else if (medium) "Medium risk" else "Low risk"),
            "event" to (headline?.key ?: "Normal"), "statuses" to statuses,
            "timestampMs" to System.currentTimeMillis(), "sessionId" to sessionId,
            "tripSeconds" to tripSeconds, "speedKph" to 0,
            "simulated" to true, "source" to "manual_test"
        ))
    }

    private var flushing = false
    fun flush() {
        if (!ready || role != "driver" || flushing) return
        val arr = readPending()
        if (arr.length() == 0) { onChange?.invoke(); return }
        val event = arr.optJSONObject(0) ?: return
        flushing = true
        val id = event.optString("eventId")
        val map = mutableMapOf<String, Any>()
        val keys = event.keys()
        while (keys.hasNext()) { val k = keys.next(); map[k] = event.get(k) }
        eventsRef().child(id).setValue(map)
            .addOnCompleteListener { task ->
                flushing = false
                if (task.isSuccessful) {
                    val current = readPending()
                    val remaining = JSONArray()
                    for (i in 0 until current.length()) {
                        val item = current.optJSONObject(i) ?: continue
                        if (item.optString("eventId") != id) remaining.put(item)
                    }
                    prefs.edit().putString("pending", remaining.toString())
                        .putLong("lastSyncMs", System.currentTimeMillis()).apply()
                    onChange?.invoke()
                    flush()
                } else {
                    toast("Sync failed: ${task.exception?.localizedMessage}")
                    onChange?.invoke()
                }
            }
    }

    fun lastSyncText() = if (lastSync == 0L) "Never" else DateFormat.getDateTimeInstance().format(Date(lastSync))

    fun registerSupervisorPush() {
        if (!ready || role != "supervisor") return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            // Restrict registration in database rules to verified supervisor accounts.
            db().getReference("supervisorTokens").child(uid()).setValue(token)
                .addOnFailureListener { toast("Push registration failed: ${it.localizedMessage}") }
        }.addOnFailureListener { toast("FCM token unavailable: ${it.localizedMessage}") }
    }
}
