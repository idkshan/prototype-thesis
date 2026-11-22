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
import edu.dlsu.esca_3_2526_c3.monitor.ModelFiles

/**
 * Setup guide (first start, and later from Settings): how the app works and what the screen means,
 * notifications, DMS-NET Wi-Fi, device tests, camera position, done.
 * The camera-position step runs the same MediaPipe face model as monitoring and gives the
 * advice of scripts/aim_camera.py. Calibration to the driver itself happens automatically
 * during the first 5 seconds of every monitoring session.
 */
class SetupActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("driver_setup_v1", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val devices by lazy { DeviceStatusMonitor(this, aim = true) { refreshDeviceViews() } }
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
                val state = devices.state
                calibrated = state.camera && state.faceFound && state.aim?.ok == true
                if (calibrated) prefs.edit().putLong("aligned_at", System.currentTimeMillis()).apply()
                feedback?.text = when {
                    calibrated -> "Camera position is good. The app calibrates to the driver automatically " +
                            "in the first 5 seconds of every monitoring session."
                    !state.camera || !state.faceFound -> "The camera or face was lost. Adjust the camera and try again."
                    else -> "The camera is too far to the side. Follow the advice above and try again."
                }
                startCheck?.isEnabled = state.faceFound
                startCheck?.text = "Repeat 5-second check"
                nextButton?.isEnabled = calibrated
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.nightMode = Ui.nightPreference(this)
        step = savedInstanceState?.getInt("step") ?: 0
        calibrated = prefs.getLong("aligned_at", 0L) > 0L
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.canvas); isFillViewport = true } // dark in night mode too
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@SetupActivity, 16), Ui.dp(this@SetupActivity, 16),
                Ui.dp(this@SetupActivity, 16), Ui.dp(this@SetupActivity, 24))
        }
        scroll.addView(content)
        // Navy top bar behind the Android status bar, then the scrolling page.
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.canvas) }
        val header = Ui.header(this, "Setup guide")
        screen.addView(header)
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(screen)
        Ui.fitSystemBars(this, screen, header)
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
        if (requestCode == 90 && step == 1) render()
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
        content.addView(Ui.note(this, "Step ${step + 1} of 6"))
        content.addView(Ui.space(this, 12))
        if (alignment) showAlignment() else when (step) {
            0 -> showIntro()
            1 -> showNotifications()
            2 -> showWifi()
            3 -> showDevices()
            4 -> showCalibration()
            else -> showDone()
        }
        if (!alignment) {
            if (step > 0 && step < 5) content.addView(button("Previous step") { goTo(step - 1) })
            if (step < 5) content.addView(button("Skip for now") { finish() })
        }
    }

    /** Step 1: how Danteye works and what the screen means. */
    private fun showIntro() {
        val how = Ui.card(this)
        how.addView(Ui.title(this, "Welcome to Danteye"))
        how.addView(Ui.note(this, "Danteye watches for signs of tiredness and distraction while you drive, and warns you in time."))
        for ((step, text) in listOf(
            "1. Watch" to "A small camera in front of you sends its picture to this phone over the DMS-NET Wi-Fi. " +
                    "The picture is analysed on the phone only; it is not saved or sent anywhere.",
            "2. Learn your face" to "In the first 5 seconds of every drive the app learns how your eyes and face normally look " +
                    "(CALIBRATING). Look at the road normally.",
            "3. Detect" to "It notices eyes closing, yawning, looking away from the road and using a phone, " +
                    "and combines them with speed and sudden movements from the sensor board.",
            "4. Warn" to "The buzzer on the sensor board beeps, and the phone can speak a short instruction such as " +
                    "\"Eyes on the road\" and vibrate.",
            "5. Share" to "Your supervisor sees your risk level, alerts and location while you monitor. " +
                    "History keeps every session on this phone.")) {
            how.addView(Ui.title(this, step).apply { textSize = 15f; setPadding(0, Ui.dp(this@SetupActivity, 6), 0, 0) })
            how.addView(Ui.note(this, text).apply { setPadding(0, 0, 0, Ui.dp(this@SetupActivity, 4)) })
        }
        content.addView(how, Ui.blockParams(this))

        val screen = Ui.card(this)
        screen.addView(Ui.title(this, "What the screen means"))
        screen.addView(Ui.note(this, "The big panel on the Home screen shows your risk level:"))
        for ((level, text) in listOf(
            "NORMAL" to "No warning signs. Keep driving safely.",
            "LOW" to "Mild signs, e.g. a yawn or a short look away. Stay alert.",
            "MEDIUM" to "Clear signs, e.g. eyes closing or looking away for seconds. The buzzer beeps.",
            "HIGH" to "Dangerous, e.g. a long eye closure or using the phone. Take a break.")) {
            screen.addView(Ui.alert(this, text, "", level, Ui.riskColor(level)))
        }
        screen.addView(Ui.note(this, "Other messages: CALIBRATING (look at the road for 5 seconds), " +
                "NO CAMERA PICTURE (check the camera power and the Wi-Fi), MONITORING OFF (press Start monitoring)."))
        screen.addView(Ui.note(this, "The badges under the panel say whether the camera, the sensor board, the GPS and the " +
                "supervisor link are Online or Offline. Settings has the alert options, night mode, the device tests and this guide."))
        screen.addView(Ui.note(this, "Never touch the phone while driving: set everything up before you start."))
        screen.addView(button("Next: notifications") { goTo(1) })
        content.addView(screen, Ui.blockParams(this))
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
        card.addView(button("Continue to Wi-Fi") { goTo(2) })
        content.addView(card, Ui.blockParams(this))
    }

    private fun showWifi() {
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Join DMS-NET"))
        card.addView(Ui.note(this, "Open Wi-Fi settings and connect your phone to DMS-NET " +
                "(password ${edu.dlsu.esca_3_2526_c3.monitor.DeviceSettings(this).dmsNetPassword}). " +
                "When Android says the network has no internet, choose Stay connected. Keep mobile data on for the supervisor. " +
                "Return here afterward."))
        card.addView(Ui.note(this, "The app cannot verify the Wi-Fi network name without additional location permission."))
        card.addView(button("Open Wi-Fi settings") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) })
        card.addView(button("I have joined DMS-NET") { goTo(3) })
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
        card.addView(button("Continue to the camera position") { goTo(4) })
        content.addView(card, Ui.blockParams(this))
        refreshDeviceViews()
    }

    private fun showCalibration() {
        val card = Ui.card(this)
        card.addView(Ui.title(this, "Camera position"))
        card.addView(Ui.note(this, "Sit normally and look straight at the road for 5 seconds. Keep your face fully visible. " +
                "The camera should be within ${"%.0f".format(ModelLimits.cameraOffsetDeg(this))}° of straight ahead."))
        preview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(preview)
        offsetLabel = Ui.note(this, "Waiting for a camera picture…")
        card.addView(offsetLabel)
        card.addView(button("Camera alignment helper") {
            alignment = true
            render()
        })
        countdown = Ui.note(this, "Ready for a 5-second position check")
        card.addView(countdown)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 5000
            progress = 0
        }
        card.addView(progress, LinearLayout.LayoutParams(-1, Ui.dp(this, 20)))
        feedback = Ui.note(this, if (calibrated) "Position check previously completed." else "A visible face is required to start.")
        card.addView(feedback)
        startCheck = button("Start 5-second check") { startCalibration() }
        card.addView(startCheck)
        nextButton = button("Continue to Done") { goTo(5) }.apply { isEnabled = calibrated }
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
        if (runningCheck || !devices.state.camera || !devices.state.faceFound) return
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
        card.addView(Ui.note(this, "Camera position checked. Press Start monitoring on the Home screen; " +
                "look at the road normally for the first 5 seconds while the app calibrates to you."))
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
            val sensorSet = edu.dlsu.esca_3_2526_c3.monitor.DeviceSettings(this@SetupActivity).sensorUrl.isNotBlank()
            fun badge(v: android.view.View) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@SetupActivity, 4) })
            badge(Ui.deviceIndicator(this@SetupActivity, "📷 Camera", state.camera))
            badge(Ui.deviceIndicator(this@SetupActivity, "▣ Sensor board", state.sensor, notSet = !sensorSet))
            badge(Ui.deviceIndicator(this@SetupActivity, "⌖ GPS", state.gps, "Location fix", "No fix yet", notSet = !sensorSet))
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
        val aim = state.aim
        offsetLabel?.text = when {
            DriverMonitoringService.active -> "Monitoring is running: stop it to use the camera here."
            !state.camera -> "Camera is offline. No alignment measurement available."
            !state.faceFound -> "No face detected. Look ahead in good lighting."
            aim?.offset == null -> "Face found. Measuring the camera angle …"
            aim.ok -> String.format(java.util.Locale.US, "Camera angle %.0f° (limit %.0f°): good.", aim.offset, aim.limit)
            else -> String.format(java.util.Locale.US, "Camera angle %.0f° (limit %.0f°). Left/right: %s. Up/down: %s.",
                aim.offset, aim.limit, aim.horizontal, aim.vertical)
        }
        startCheck?.isEnabled = !runningCheck && state.camera && state.faceFound
    }
}

/** Limits of the detection settings shown in the setup texts. */
private object ModelLimits {
    fun cameraOffsetDeg(context: android.content.Context): Double =
        try { ModelFiles.config(context).calibration.maxCameraOffsetDeg } catch (_: Exception) { 25.0 }
}
