package edu.dlsu.esca_3_2526_c3

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs

/** Setup guide */
class SetupActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("driver_setup_v1", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val devices by lazy { DeviceStatusMonitor(this) { refreshDeviceViews() } }
    private lateinit var content: LinearLayout
    private var step = 0
    private var alignment = false
    private var runningCheck = false
    private var checkEnds = 0L
    private var calibrated = false
    private var connections: LinearLayout? = null
    private var preview: LinearLayout? = null
    private var offsetLabel: TextView? = null
    private var offsetMeter: SeekBar? = null
    private var startCheck: Button? = null
    private var nextButton: Button? = null
    private var progress: ProgressBar? = null
    private var countdown: TextView? = null
    private var feedback: TextView? = null

    private val timer = object : Runnable {
        override fun run() {
            if (!runningCheck) return

            val remaining = (checkEnds - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            progress?.progress = (5000L - remaining).toInt()

            countdown?.text = if (remaining > 0L)
                "Look straight ahead for 5 seconds · ${(remaining + 999L) / 1000L} s remaining"
            else "Five seconds completed"

            if (remaining > 0L) handler.postDelayed(this, 100L)
            else {
                runningCheck = false
                calibrated = devices.state.camera && devices.state.faceOffsetPercent != null
                if (calibrated) prefs.edit().putLong("aligned_at", System.currentTimeMillis()).apply()
                feedback?.text = if (calibrated)
                    "Pose check completed. This records setup progress only; model calibration is not connected yet."
                else "The camera or face was lost. Adjust the camera and try again."
                startCheck?.isEnabled = devices.state.faceOffsetPercent != null
                startCheck?.text = "Repeat 5-second check"
                nextButton?.isEnabled = calibrated
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.nightMode = getSharedPreferences("driver_display", MODE_PRIVATE).getBoolean("night", false)
        window.statusBarColor = Ui.navy
        window.navigationBarColor = Ui.navy
        step = savedInstanceState?.getInt("step") ?: 0
        calibrated = prefs.getLong("aligned_at", 0L) > 0L
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.background) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@SetupActivity, 16), Ui.dp(this@SetupActivity, 16),
                Ui.dp(this@SetupActivity, 16), Ui.dp(this@SetupActivity, 24))
        }
        scroll.addView(content)
        setContentView(scroll)
        render()
    }

    override fun onResume() {
        super.onResume()
        devices.start()
    }

    override fun onPause() {
        handler.removeCallbacks(timer)
        runningCheck = false
        devices.stop()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timer)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("step", step)
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 90 && step == 0) render()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun goTo(next: Int) {
        handler.removeCallbacks(timer)
        runningCheck = false
        step = next
        alignment = false
        render()
    }

    private fun render() {
        connections = null
        preview = null
        offsetLabel = null
        offsetMeter = null
        startCheck = null
        nextButton = null
        progress = null
        countdown = null
        feedback = null
        content.removeAllViews()
        content.addView(Ui.title(this, if (alignment) "Camera alignment" else "First-time setup"))
        content.addView(Ui.note(this, "Step ${step + 1} of 5"))
        content.addView(Ui.space(this, 12))
        if (alignment) showAlignment() else when (step) {
            0 -> showNotifications()
            1 -> showWifi()
            2 -> showDevices()
            3 -> showCalibration()
            else -> showDone()
        }
        if (!alignment) {
            if (step > 0 && step < 4) content.addView(button("Previous step") { goTo(step - 1) })
            if (step < 4) content.addView(button("Skip for now") { finish() })
        }
    }

    private fun showNotifications() {
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Allow notifications"))
        card.addView(Ui.note(this, "Notifications keep the reminder and HIGH alerts visible."))
        val granted = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        card.addView(Ui.note(this, if (granted) "Notifications are permitted." else "Notifications are not yet permitted."))
        if (!granted) card.addView(button("Allow notifications") {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 90)
        })
        card.addView(button("Continue to Wi-Fi") { goTo(1) })
        content.addView(card, Ui.blockParams(this))
    }

    private fun showWifi() {
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Join DMS-NET"))
        card.addView(Ui.note(this, "Open Wi-Fi settings and connect your phone to DMS-NET. Return here afterward."))
        card.addView(Ui.note(this, "The app cannot verify the Wi-Fi network name without additional location permission."))
        card.addView(button("Open Wi-Fi settings") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) })
        card.addView(button("I have joined DMS-NET") { goTo(2) })
        content.addView(card, Ui.blockParams(this))
    }

    private fun showDevices() {
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Test the devices"))
        connections = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(connections)
        card.addView(button("Run tests again") {
            devices.stop()
            devices.start()
            refreshDeviceViews()
        })
        card.addView(button("Continue to calibration") { goTo(3) })
        content.addView(card, Ui.blockParams(this))
        refreshDeviceViews()
    }

    private fun showCalibration() {
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Camera calibration"))
        card.addView(Ui.note(this, "Look straight ahead for 5 seconds. Keep your face fully visible and the phone steady."))
        preview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(preview)
        offsetLabel = Ui.note(this, "Waiting for a camera picture…")
        card.addView(offsetLabel)
        card.addView(button("Camera alignment helper") {
            alignment = true
            render()
        })
        countdown = Ui.note(this, "Ready for a 5-second pose check")
        card.addView(countdown)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 5000
            progress = 0
        }
        card.addView(progress, LinearLayout.LayoutParams(-1, Ui.dp(this, 20)))
        feedback = Ui.note(this, if (calibrated) "Pose check previously completed." else "A visible face is required to start.")
        card.addView(feedback)
        startCheck = button("Start 5-second check") { startCalibration() }
        card.addView(startCheck)
        nextButton = button("Continue to Done") { goTo(4) }.apply { isEnabled = calibrated }
        card.addView(nextButton)
        content.addView(card, Ui.blockParams(this))
        refreshDeviceViews()
    }

    private fun showAlignment() {
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Center your face"))
        card.addView(Ui.note(this, "Move the camera until your face is near the picture center."))

        preview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(preview)

        offsetLabel = Ui.note(this, "Waiting for a camera picture…")
        card.addView(offsetLabel)

        card.addView(Ui.note(this, "Left                         Center                         Right"))
        offsetMeter = SeekBar(this).apply { max = 200; progress = 100; isEnabled = false }
        card.addView(offsetMeter)

        card.addView(button("Back to calibration") { alignment = false; render() })
        content.addView(card, Ui.blockParams(this))
        refreshDeviceViews()
    }

    private fun startCalibration() {
        if (runningCheck || !devices.state.camera || devices.state.faceOffsetPercent == null) return
        calibrated = false
        prefs.edit().remove("aligned_at").apply()
        runningCheck = true
        checkEnds = SystemClock.elapsedRealtime() + 5000L
        startCheck?.isEnabled = false
        nextButton?.isEnabled = false
        feedback?.text = "Keep looking straight ahead while the timer runs."
        timer.run()
    }

    private fun showDone() {
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Setup done"))
        card.addView(Ui.note(this, "5-second alignment guide complete"))
        card.addView(button("Finish setup") {
            prefs.edit().putBoolean("complete", true).apply()
            finish()
        })
        content.addView(card, Ui.blockParams(this))
    }

    /** Only the camera actually delivers a face image; sensor yaw cannot locate the camera. */
    private fun refreshDeviceViews() {
        if (!::content.isInitialized) return
        val state = devices.state
        connections?.apply {
            removeAllViews()
            addView(Ui.deviceIndicator(this@SetupActivity, "📷 Camera", state.camera))
            addView(Ui.deviceIndicator(this@SetupActivity, "▣ Sensor", state.sensor))
            addView(Ui.deviceIndicator(this@SetupActivity, "⌖ GPS", state.gps))
        }
        preview?.apply {
            removeAllViews()
            if (state.photo != null && state.camera) {
                addView(ImageView(this@SetupActivity).apply {
                    setImageBitmap(state.photo)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "Most recent camera JPEG"
                }, LinearLayout.LayoutParams(-1, Ui.dp(this@SetupActivity, 200)))
            } else addView(Ui.placeholder(this@SetupActivity,
                "NO CAMERA PICTURE", "Check the camera power and Wi-Fi connection", 200))
        }
        offsetMeter?.progress = (100 + (state.faceOffsetPercent ?: 0)).coerceIn(0, 200)
        offsetLabel?.text = when {
            !state.camera -> "Camera is offline. No alignment measurement available."
            state.faceOffsetPercent == null -> "No face detected. Look ahead in good lighting."
            abs(state.faceOffsetPercent) <= 8 -> "Face is near picture center (within 8% of half-frame width)."
            state.faceOffsetPercent > 0 -> "Face is ${state.faceOffsetPercent}% right of center. Move the camera right."
            else -> "Face is ${abs(state.faceOffsetPercent)}% left of center. Move the camera left."
        }
        startCheck?.isEnabled = !runningCheck && state.camera && state.faceOffsetPercent != null
    }
}
