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

    /** One visible LOW / MEDIUM / HIGH scale, including older Firebase event values. */
    fun riskLevel(value: String): String = when {
        value.startsWith("High", ignoreCase = true) -> "HIGH"
        value.startsWith("Medium", ignoreCase = true) || value.equals("Warning", true) -> "MEDIUM"
        else -> "LOW"
    }

    fun riskColor(value: String): Int = when (riskLevel(value)) {
        "HIGH" -> red
        "MEDIUM" -> yellow
        else -> green
    }

    /** no eye-closure duration is measured. */
    fun alertCause(behavior: String): String = when (behavior) {
        "Drowsiness" -> "Drowsiness · manual test"
        "Yawning" -> "Yawning · manual test"
        "Gaze deviation" -> "Looking away · manual test"
        "Phone usage" -> "Phone use · manual test"
        else -> "$behavior · manual test"
    }

    /** device indicator */
    fun deviceIndicator(c: Context, label: String, working: Boolean) =
        text(c, label, 12f, white, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(c, 5), dp(c, 8), dp(c, 5), dp(c, 8))
            background = shape(if (working) green else red, c, 8)
        }


    // Night mode changes the page and card backgrounds without changing risk colors.
    var nightMode = false
    val canvas: Int get() = if (nightMode) Color.rgb(9, 15, 28) else background
    val surface: Int get() = if (nightMode) Color.rgb(29, 38, 55) else white


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
        background = shape(if (selected) Color.rgb(232, 239, 253) else Ui.background, c, 10)
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
