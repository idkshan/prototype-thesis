package edu.dlsu.esca_3_2526_c3

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import edu.dlsu.esca_3_2526_c3.dms.AlertCommand
import edu.dlsu.esca_3_2526_c3.dms.GuidedPlan
import edu.dlsu.esca_3_2526_c3.dms.GuidedRecorder
import edu.dlsu.esca_3_2526_c3.monitor.DeviceSettings
import edu.dlsu.esca_3_2526_c3.monitor.LiveFrame
import edu.dlsu.esca_3_2526_c3.monitor.MonitorSession
import java.io.File
import java.util.Locale

/**
 * Guided recording: the screen (and voice) tell the participant what to do and when;
 * the normal detection pipeline runs and logs every frame, and labels.csv is written
 * from the schedule (dms/Guided.kt). The session then appears in History, labelled.
 */
class GuidedRecordingActivity : Activity(), MonitorSession.Listener {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: DeviceSettings
    private lateinit var body: LinearLayout
    private var session: MonitorSession? = null
    private var recorder: GuidedRecorder? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speak = true
    private var participant = ""
    private var condition = "day"
    private var plan = GuidedPlan.STANDARD
    private var lastTick = -1.0
    private var finished = false

    private var instruction: TextView? = null
    private var countdown: TextView? = null
    private var progress: ProgressBar? = null
    private var stepText: TextView? = null
    private var status: TextView? = null
    private var preview: ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = DeviceSettings(this)
        Ui.nightMode = Ui.nightPreference(this)
        val scroller = ScrollView(this).apply { setBackgroundColor(Ui.canvas); isFillViewport = true }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@GuidedRecordingActivity, 16)
            setPadding(p, p, p, Ui.dp(this@GuidedRecordingActivity, 28))
        }
        scroller.addView(body)
        // Navy top bar behind the Android status bar, then the scrolling page.
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.canvas) }
        val header = Ui.header(this, "Guided recording")
        screen.addView(header)
        screen.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(screen)
        Ui.fitSystemBars(this, screen, header)
        tts = TextToSpeech(this) { ok ->
            ttsReady = ok == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.US
        }
        showSetup()
    }

    override fun onDestroy() {
        if (session != null) finishRecording(early = true)
        tts?.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ setup
    private fun showSetup() {
        body.removeAllViews()
        body.addView(Ui.title(this, "Guided recording"))
        val card = Ui.card(this)
        card.addView(Ui.note(this, "Before you start: the participant has read and signed the consent form; the camera is " +
            "mounted in front of the driver; the phone is on DMS-NET. Recordings contain personal data (Data Privacy Act of 2012)."))
        card.addView(Ui.note(this, "Participant ID (no names), e.g. P01"))
        val id = EditText(this).apply { setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT; hint = "P01" }
        card.addView(id)
        card.addView(Ui.note(this, "Condition"))
        val cond = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val day = RadioButton(this).apply { text = "Day"; this.id = 1001; isChecked = true }
        val night = RadioButton(this).apply { text = "Night"; this.id = 1002 }
        cond.addView(day); cond.addView(night)
        card.addView(cond)
        card.addView(Ui.note(this, "Script"))
        val script = RadioGroup(this)
        GuidedPlan.ALL.forEachIndexed { i, p ->
            script.addView(RadioButton(this).apply {
                text = "${p.name.replaceFirstChar { it.uppercase() }} (%.1f min, %d actions)".format(p.totalSeconds / 60.0, p.steps.count { it.label != null })
                this.id = 2000 + i
                isChecked = i == 0
            })
        }
        card.addView(script)
        val voice = CheckBox(this).apply { text = "Speak the instructions"; isChecked = true }
        card.addView(voice)
        status = Ui.note(this, "")
        card.addView(Button(this).apply {
            text = "▶  Start guided recording"
            isAllCaps = false
            setOnClickListener {
                val pid = id.text.toString().trim().ifEmpty { "P00" }
                if (!Regex("[A-Za-z0-9_-]{1,20}").matches(pid)) { status?.text = "Use letters, numbers, - or _ for the participant ID."; return@setOnClickListener }
                participant = pid
                condition = if (cond.checkedRadioButtonId == 1002) "night" else "day"
                plan = GuidedPlan.ALL[(script.checkedRadioButtonId - 2000).coerceIn(0, GuidedPlan.ALL.size - 1)]
                speak = voice.isChecked
                start()
            }
        })
        card.addView(status)
        body.addView(card, Ui.blockParams(this))
        val steps = Ui.card(this)
        steps.addView(Ui.title(this, "What the participant will do"))
        for (p in GuidedPlan.ALL) {
            steps.addView(Ui.eyebrow(this, p.name.uppercase()))
            steps.addView(Ui.note(this, p.steps.filter { it.label != null }.joinToString("\n") {
                "• ${it.instruction} (%.0f s)".format(it.seconds)
            }))
        }
        steps.addView(Ui.note(this, "Each action starts after a spoken 3-2-1 countdown. Between actions: normal driving."))
        body.addView(steps, Ui.blockParams(this))
    }

    // ------------------------------------------------------------------ recording
    private fun big(size: Float, color: Int) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(0, Ui.dp(this@GuidedRecordingActivity, 8), 0, Ui.dp(this@GuidedRecordingActivity, 8))
    }

    private fun start() {
        // The ESP32-CAM serves one viewer: normal monitoring must be stopped first.
        if (DriverMonitoringService.active) {
            android.widget.Toast.makeText(this, "Stop monitoring on the Home screen first.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        body.removeAllViews()
        val card = Ui.card(this)
        stepText = Ui.note(this, "$participant · $condition · ${plan.name} script")
        card.addView(stepText)
        instruction = big(30f, Ui.navy).apply { text = "Connecting to the camera …" }
        card.addView(instruction)
        countdown = big(44f, Ui.red)
        card.addView(countdown)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        card.addView(progress, LinearLayout.LayoutParams(-1, -2))
        status = Ui.note(this, "")
        card.addView(status)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Not done: remove label"
            isAllCaps = false
            setOnClickListener {
                val s = session; val r = recorder
                if (s == null || r == null) return@setOnClickListener
                val seg = r.markLastActionNotDone(s.now())
                status?.text = if (seg == null) "No action has started yet." else "Label removed: ${seg.label} at %.1f s".format(seg.start)
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(Button(this).apply {
            text = "■  Stop"
            isAllCaps = false
            setOnClickListener { finishRecording(early = true) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(buttons)
        body.addView(card, Ui.blockParams(this))
        preview = ImageView(this).apply { adjustViewBounds = true; setBackgroundColor(if (Ui.nightMode) Color.rgb(44, 55, 72) else Color.rgb(232, 239, 248)); contentDescription = "Camera" }
        body.addView(preview, LinearLayout.LayoutParams(-1, -2))

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val s = MonitorSession(this, settings, this, logName = "${participant}_$condition")
        session = s
        s.start()
        main.postDelayed(tick, 100)
    }

    private val tick = object : Runnable {
        override fun run() {
            val s = session ?: return
            val r = recorder
            if (r != null) {
                val t = s.now()
                if (speak && ttsReady) for (c in r.cuesBetween(lastTick, t)) tts?.speak(c.speak, TextToSpeech.QUEUE_ADD, null, "c${c.t}")
                lastTick = t
                val st = r.state(t)
                if (st.finished) { finishRecording(early = false); return }
                instruction?.text = st.text
                instruction?.setTextColor(if (st.countdown) Ui.amber else if (plan.steps[st.step].label != null) Ui.red else Ui.navy)
                countdown?.text = "%.0f".format(Math.ceil(st.secondsLeft))
                progress?.progress = (1000 * (t - r.startT) / (r.endT - r.startT)).toInt().coerceIn(0, 1000)
                stepText?.text = "$participant · $condition · step ${st.step + 1} of ${plan.steps.size}"
            }
            main.postDelayed(this, 100)
        }
    }

    override fun onFrame(frame: LiveFrame) {
        main.post {
            preview?.setImageBitmap(frame.image)
            if (recorder == null && session != null) {
                // the script starts with the first camera picture, on the clock of the frames
                recorder = GuidedRecorder(plan, frame.features.t)
                lastTick = frame.features.t - 0.001
            }
        }
    }

    override fun onAlert(cmd: AlertCommand, pushed: Boolean) {}

    override fun onStatus(text: String) {
        main.post { if (recorder == null) instruction?.text = text else status?.text = text }
    }

    private fun finishRecording(early: Boolean) {
        val s = session ?: return
        if (finished) return
        finished = true
        main.removeCallbacks(tick)
        session = null
        val r = recorder
        r?.stop(if (early) s.now() else r.endT)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        instruction?.text = "Saving …"
        countdown?.text = ""
        Thread {
            s.stop()
            val dir = s.logDir
            var saved = "No session folder (logging failed)."
            if (dir != null && r != null) {
                File(dir, "labels.csv").writeText(r.labelsCsv())
                File(dir, "guided.json").writeText(r.summary(participant, condition).toString(2))
                saved = "Saved ${r.recordedSegments().size} labels in ${dir.name} (labels.csv). Share it from History."
            } else if (dir != null) {
                saved = "Stopped before the first camera picture: no labels written."
            }
            main.post {
                instruction?.text = if (early) "Stopped" else "Finished"
                instruction?.setTextColor(Ui.navy)
                status?.text = saved
            }
        }.start()
    }
}
