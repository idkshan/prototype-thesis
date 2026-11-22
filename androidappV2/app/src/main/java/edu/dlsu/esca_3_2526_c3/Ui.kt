package edu.dlsu.esca_3_2526_c3

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Shared view builders and colors for the driver and supervisor screens.
 */
object Ui {
    // Base colors used across headers, labels, risk indicators, and navigation.
    val navy = Color.rgb(15, 23, 42)
    val background = Color.rgb(245, 247, 251)
    val white = Color.WHITE
    val muted = Color.rgb(100, 116, 139)
    val blue = Color.rgb(37, 99, 235)
    val green = Color.rgb(22, 163, 74)
    val amber = Color.rgb(180, 83, 9)
    val red = Color.rgb(220, 38, 38)
    val yellow = Color.rgb(250, 204, 21)

    val orange = Color.rgb(234, 88, 12)

    /**
     * One visible scale for the risk model's tiers and the behaviour levels:
     * NORMAL (tier NONE / "Normal") < LOW < MEDIUM ("Warning") < HIGH. Older Firebase values still work.
     */
    fun riskLevel(value: String): String = when {
        value.startsWith("High", ignoreCase = true) -> "HIGH"
        value.startsWith("Medium", ignoreCase = true) || value.equals("Warning", true) -> "MEDIUM"
        value.startsWith("Low", ignoreCase = true) -> "LOW"
        value.equals("Unknown", true) || value.equals("UNAVAILABLE", true) -> "NO DATA"
        value.startsWith("Calibrating", ignoreCase = true) -> "CALIBRATING"
        value.startsWith("Not monitoring", ignoreCase = true) || value.equals("OFF", true) -> "OFF"
        else -> "NORMAL"
    }

    fun riskColor(value: String): Int = when (riskLevel(value)) {
        "HIGH" -> red
        "MEDIUM" -> orange
        "LOW" -> yellow
        "NORMAL" -> green
        else -> muted
    }

    /** Text colour on a [riskColor] background. */
    fun onRiskColor(value: String): Int = if (riskLevel(value) == "LOW") Color.BLACK else Color.WHITE

    /** Alert label; developer-mode test events are marked as such. */
    fun alertCause(behavior: String, simulated: Boolean = false): String {
        val name = when (behavior) {
            "Gaze deviation" -> "Looking away"
            "Phone usage" -> "Phone use"
            else -> behavior
        }
        return if (simulated) "$name · manual test" else name
    }

    /**
     * Device status badge: icon, name and state in words ("Online" / "Offline"), not colour alone.
     * Green = working, red = not working, grey = not set up.
     */
    fun deviceIndicator(c: Context, label: String, working: Boolean, onText: String = "Online",
                        offText: String = "Offline", notSet: Boolean = false, pending: String? = null): TextView {
        val state = when {
            notSet -> "– Not set"
            pending != null -> "… $pending"
            working -> "✓ $onText"
            else -> "✕ $offText"
        }
        return text(c, "$label\n$state", 12f, white, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(c, 5), dp(c, 8), dp(c, 5), dp(c, 8))
            background = shape(if (notSet || pending != null) muted else if (working) green else red, c, 8)
            contentDescription = "$label: ${state.drop(2)}"
        }
    }

    /** Setting with an on / off switch and a short explanation. */
    fun switchRow(c: Context, label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(c, 8), 0, dp(c, 8))
            val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(c, label, 15f, navy, true))
            if (detail.isNotEmpty()) texts.addView(text(c, detail, 12f, muted))
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            val sw = android.widget.Switch(c).apply {
                isChecked = checked
                contentDescription = label
                setOnCheckedChangeListener { _, value -> onChange(value) }
            }
            addView(sw)
            setOnClickListener { sw.toggle() }
        }

    /** One choice out of a few (e.g. Night mode: Off / On / Auto), shown as a row of buttons. */
    fun choiceRow(c: Context, label: String, options: List<Pair<String, String>>, selected: String,
                  onPick: (String) -> Unit): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(c, 8), 0, dp(c, 8))
        addView(text(c, label, 15f, navy, true))
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        for ((value, name) in options) {
            val on = value == selected
            row.addView(text(c, if (on) "✓ $name" else name, 13f, if (on) white else navy, on).apply {
                gravity = Gravity.CENTER
                setPadding(dp(c, 6), dp(c, 10), dp(c, 6), dp(c, 10))
                background = shape(if (on) blue else if (nightMode) Color.rgb(44, 55, 72) else Color.rgb(232, 239, 248), c, 10)
                contentDescription = "$label: $name" + if (on) ", selected" else ""
                isClickable = true
                setOnClickListener { onPick(value) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(c, 2), dp(c, 6), dp(c, 2), 0) })
        }
        addView(row)
    }

    /** Tappable settings row: title, explanation and an arrow. */
    fun linkRow(c: Context, label: String, detail: String, onTap: () -> Unit) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(c, 10), 0, dp(c, 10))
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(c, label, 15f, navy, true))
        if (detail.isNotEmpty()) texts.addView(text(c, detail, 12f, muted))
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(c, "›", 24f, muted))
        isClickable = true
        contentDescription = label
        setOnClickListener { onTap() }
    }

    /** Big coloured status panel with text (risk level), used by the driver and supervisor screens. */
    fun statusPanel(c: Context, value: String, color: Int, size: Float = 28f) = text(c, value, size,
        if (color == yellow) Color.BLACK else white, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(c, 12), dp(c, 20), dp(c, 12), dp(c, 20))
        background = shape(color, c, 16)
    }

    // Night mode changes the page and card backgrounds without changing risk colors.
    var nightMode = false

    /** Saved choice: "off", "on" or "auto" (dark from 6 pm to 6 am). Older versions saved a boolean "night". */
    fun nightChoice(c: Context): String {
        val prefs = c.getSharedPreferences("driver_display", Context.MODE_PRIVATE)
        return prefs.getString("night_mode", null) ?: if (prefs.getBoolean("night", false)) "on" else "auto"
    }

    fun saveNightChoice(c: Context, choice: String) {
        c.getSharedPreferences("driver_display", Context.MODE_PRIVATE).edit().putString("night_mode", choice).apply()
    }

    /** Whether the screens are dark now. */
    fun nightPreference(c: Context): Boolean = when (nightChoice(c)) {
        "on" -> true
        "off" -> false
        else -> java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY).let { it >= 18 || it < 6 }
    }
    val canvas: Int get() = if (nightMode) Color.rgb(9, 15, 28) else background
    val surface: Int get() = if (nightMode) Color.rgb(29, 38, 55) else white


    /**
     * Screens draw behind the Android status bar and navigation bar (always on Android 15; on older
     * versions too, so every phone looks the same). The navy [header] is extended up behind the
     * status bar, so the clock, Wi-Fi and notification icons are white on navy and the title starts
     * below them; [bottom] (the tab bar), or else [root], is padded above the navigation bar and
     * the keyboard.
     */
    fun fitSystemBars(activity: android.app.Activity, root: View, header: View?, bottom: View? = null) {
        val window = activity.window
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                (if (android.os.Build.VERSION.SDK_INT >= 26 && !nightMode) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        @Suppress("DEPRECATION") run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // white status-bar icons (navy header); dark navigation handle on the light tab bar by day
            window.insetsController?.setSystemBarsAppearance(
                if (nightMode) 0 else android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        }
        val hp = header?.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
        val bp = bottom?.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int; val left: Int; val right: Int; val bars: Int; val ime: Int
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val sys = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                top = sys.top; left = sys.left; right = sys.right; bars = sys.bottom
                ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION") run {
                    top = insets.systemWindowInsetTop; left = insets.systemWindowInsetLeft
                    right = insets.systemWindowInsetRight; bars = insets.systemWindowInsetBottom
                }
                ime = 0
            }
            if (header != null && hp != null) header.setPadding(hp[0] + left, hp[1] + top, hp[2] + right, hp[3])
            val below = maxOf(bars, ime)
            if (bottom != null && bp != null && ime == 0) {
                bottom.setPadding(bp[0] + left, bp[1], bp[2] + right, bp[3] + below)
                root.setPadding(0, if (header == null) top else 0, 0, 0)
            } else {
                bottom?.let { b -> bp?.let { b.setPadding(it[0] + left, it[1], it[2] + right, it[3]) } }
                root.setPadding(left, if (header == null) top else 0, right, below)
            }
            insets
        }
        root.requestApplyInsets()
    }

    /** Convert density-independent pixels to physical pixels */
    fun dp(c: Context, n: Int): Int = (n * c.resources.displayMetrics.density + 0.5f).toInt()


    /** solid, rounded background for cards and badges. */
    private fun shape(color: Int, c: Context, radius: Int = 16): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(c, radius).toFloat()
        }


    /** label, adapting dark text colors when night mode is enabled */
    private fun text(c: Context, value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(c).apply {
            this.text = value
            textSize = size

            // Replace dark foreground colors so labels remain legible at night.
            setTextColor(when {
                nightMode && color == navy -> white
                nightMode && color == muted -> Color.rgb(203, 213, 225)
                else -> color
            })

            if (bold) setTypeface(null, Typeface.BOLD)
        }

    fun header(c: Context, title: String) =
        LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 20), dp(c, 22), dp(c, 20), dp(c, 22))
            setBackgroundColor(navy)
            addView(text(c, title, 23f, white, true))
        }


    /** Standard full-width layout parameters */
    fun blockParams(c: Context) = LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(c, 14)
    }


    /** Rounded content container */
    fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(surface, c)
        elevation = dp(c, 2).toFloat()
        setPadding(dp(c, 17), dp(c, 16), dp(c, 17), dp(c, 17))
    }


    /** Main card heading */
    fun title(c: Context, value: String) = text(c, value, 18f, navy, true).apply {
        setPadding(0, 0, 0, dp(c, 12))
    }


    /** Small uppercase-style section label */
    fun eyebrow(c: Context, value: String) = text(c, value, 11f, muted, true).apply {
        setPadding(0, 0, 0, dp(c, 8))
        letterSpacing = 0.09f
    }


    /** Secondary text */
    fun note(c: Context, value: String) = text(c, value, 12f, muted).apply {
        setPadding(0, dp(c, 6), 0, dp(c, 10))
    }


    /** Compact colored status label */
    fun chip(c: Context, value: String, color: Int) = text(c, value, 12f, if (color == yellow) Color.rgb(113, 63, 18) else color, true).apply {
        setPadding(dp(c, 10), dp(c, 7), dp(c, 10), dp(c, 7))
        background = shape(Color.argb(20, Color.red(color), Color.green(color), Color.blue(color)), c, 8)
    }


    /** Larger status label */
    fun bigChip(c: Context, value: String, color: Int) = text(c, value, 18f, color, true).apply {
        setPadding(dp(c, 10), dp(c, 7), dp(c, 10), dp(c, 7))
        background = shape(Color.argb(20, Color.red(color), Color.green(color), Color.blue(color)), c, 8)
    }


    /** Invisible vertical spacer */
    fun space(c: Context, h: Int) = View(c).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(c, h))
    }


    /** Centered temporary panel for features that do not yet have live content. */
    fun placeholder(c: Context, heading: String, detail: String, height: Int) =
        LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER

            // Use a darker placeholder surface in night mode.
            background = shape(if (nightMode) Color.rgb(44, 55, 72) else Color.rgb(232, 239, 248), c, 12)

            addView(text(c, heading, 15f, navy, true))
            addView(text(c, detail, 12f, muted))
            layoutParams = LinearLayout.LayoutParams(-1, dp(c, height))
        }


    /** Two-column label/value row */
    fun pair(c: Context, label: String, value: String) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(c, 8), 0, dp(c, 8))
        addView(text(c, label, 14f, muted), LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(c, value, 14f, navy, true))
    }


    /** Alert row */
    fun alert(c: Context, label: String, time: String, level: String, color: Int) =
        LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(c, 10), 0, dp(c, 10))
            val names = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(c, label, 14f, navy, true))
                addView(text(c, time, 12f, muted))
            }
            addView(names, LinearLayout.LayoutParams(0, -2, 1f))
            addView(chip(c, level, color))
        }


    /** Session-history row */
    fun session(c: Context, date: String, duration: String, alerts: String) =
        LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(c, 14), 0, dp(c, 14))
            val left = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(c, date, 15f, navy, true))
                addView(text(c, duration, 12f, muted))
            }
            addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            addView(chip(c, alerts, blue))
        }


    /** Clickable bottom-navigation label */
    fun tab(c: Context, label: String, onTap: () -> Unit) =
        TextView(c).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setOnClickListener { onTap() }
            isClickable = true
        }


    /** Visually distinguish the selected navigation tab from inactive tabs. */
    fun activeTab(view: TextView, active: Boolean) {
        // Use brighter text for the active tab when night mode is enabled.
        view.setTextColor(if (active) (if (nightMode) Color.rgb(147, 197, 253) else blue) else (if (nightMode) Color.LTGRAY else muted))
        view.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)

        // Match the selected-tab background to the current theme.
        view.background = shape(if (active) (if (nightMode) Color.rgb(45, 59, 85) else Color.rgb(235, 241, 253)) else surface, view.context, 12)
    }


    /** Supervisor driver-list row */
    fun monitorRow(
        c: Context, name: String, risk: String, event: String, color: Int, selected: Boolean
    ) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12))
        background = shape(if (nightMode) Color.rgb(44, 55, 72) else if (selected) Color.rgb(232, 239, 253) else Ui.background, c, 10)
        val content = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(c, name, 15f, navy, true))
            addView(text(c, event, 12f, muted))
        }
        addView(content, LinearLayout.LayoutParams(0, -2, 1f))
        addView(chip(c, risk.uppercase(), color))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 8) }
    }
}
