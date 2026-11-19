package edu.dlsu.esca_3_2526_c3

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.text.method.PasswordTransformationMethod
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
import java.util.Locale
import java.util.UUID

/** Firebase disallows periods and other punctuation in path keys; escape them reversibly. */
fun firebaseEmailKey(email: String): String = email.trim().lowercase(Locale.ROOT)
    .replace("%", "%25").replace("-", "%2D").replace(".", "-").replace("#", "%23")
    .replace("$", "%24").replace("[", "%5B").replace("]", "%5D")
    .replace("/", "%2F")

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

    // Each driver writes to a Firebase branch owned by that signed-in account.
    private fun driverId() = firebaseEmailKey(email)
    fun driversRef(): DatabaseReference = db().getReference("demo/drivers")
    fun liveRef(driverId: String = driverId()): DatabaseReference = driversRef().child(driverId).child("live")
    fun eventsRef(driverId: String = driverId()): DatabaseReference = driversRef().child(driverId).child("events")

    fun start() {
        if (!configured) { onChange?.invoke(); return }
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser?.email.isNullOrBlank()) { onChange?.invoke(); return }
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
        val passInput = EditText(activity).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
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

    /** Every event is clearly identified as a simulated manual UI test. */
    fun record(behavior: String, severity: String, sessionId: String) {
        val event = JSONObject()
        val now = System.currentTimeMillis()
        event.put("eventId", UUID.randomUUID().toString())
        event.put("sessionId", sessionId)
        event.put("behavior", behavior)
        event.put("severity", severity)
        event.put("timestampMs", now)
        event.put("source", "manual_test")
        event.put("simulated", true)
        event.put("livePushEligible", online && role == "driver")
        val queue = readPending(); queue.put(event); storePending(queue)
        if (ready && role == "driver") flush()
    }

    fun localEvents(): List<JSONObject> = readPending().let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.reversed()
    }

    fun publish(statuses: Map<String, String>, sessionId: String, tripSeconds: Long,
                latitude: Double? = null, longitude: Double? = null, speedKph: Double? = null,
                updateLocation: Boolean = false, locationSource: String? = null) {
        if (!ready || role != "driver" || email.isBlank()) return
        val severe = statuses.values.any { it == "High" }
        val medium = statuses.values.any { it == "Warning" }
        val headline = statuses.entries.firstOrNull { it.value == "High" }
            ?: statuses.entries.firstOrNull { it.value == "Warning" }
        val changes = mutableMapOf<String, Any?>(
            "name" to (FirebaseAuth.getInstance().currentUser?.displayName?.takeIf { it.isNotBlank() }
                ?: email), "email" to email,
            "risk" to (if (severe) "High risk" else if (medium) "Medium risk" else "Low risk"),
            "event" to (headline?.key ?: "Normal"), "statuses" to statuses,
            "timestampMs" to System.currentTimeMillis(), "sessionId" to sessionId,
            "tripSeconds" to tripSeconds, "simulated" to true, "source" to "manual_test"
        )
        // Only device polling replaces GPS data. Manual alerts preserve the last measured fix.
        if (updateLocation) {
            changes["lat"] = latitude
            changes["lng"] = longitude
            changes["locationTimestampMs"] = if (latitude != null && longitude != null) System.currentTimeMillis() else null
            changes["speedKph"] = speedKph
            changes["locationSource"] = if (latitude != null && longitude != null) locationSource else null
        }
        liveRef().updateChildren(changes)
    }

    /** Remove a previously entered test location when monitoring is not running. */
    fun clearLocation() {
        if (ready && role == "driver") liveRef().updateChildren(mapOf<String, Any?>(
            "lat" to null, "lng" to null, "locationTimestampMs" to null,
            "speedKph" to null, "locationSource" to null))
    }

    private var flushing = false
    fun flush() {
        if (!ready || role != "driver" || flushing || email.isBlank()) return
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
        if (!ready || role != "supervisor" || email.isBlank()) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            // Restrict registration in database rules to verified supervisor accounts.
            db().getReference("supervisorTokens").child(uid()).setValue(token)
                .addOnFailureListener { toast("Push registration failed: ${it.localizedMessage}") }
        }.addOnFailureListener { toast("FCM token unavailable: ${it.localizedMessage}") }
    }
}
