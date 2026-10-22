package de.ammar.qadam.accessibility

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import de.ammar.qadam.Bridge
import de.ammar.qadam.core.engine.Action
import de.ammar.qadam.core.engine.Overlay
import de.ammar.qadam.qlog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Level 1–3 overlays (SPEC §7), drawn as accessibility overlays: windows that lie on
 * top of every app. Built from plain Android views, because Compose needs extra plumbing
 * outside an activity.
 *
 * - Level 1 (Nudge): a small card at the bottom; the app behind stays usable.
 * - Level 2 (Friction): full screen; type the next action (≥ 3 words) to get 5 minutes.
 * - Level 3 (Block): full screen over drift apps; tap a tag, or hold 30 s and give a reason.
 *
 * Buttons go to the service, which asks `core`'s engine; a refusal shows its reason.
 */
class Overlays(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var shown: Overlay = Overlay.None

    fun show(o: Overlay) {
        if (o == shown) return
        remove()
        shown = o
        when (o) {
            Overlay.None -> {}
            is Overlay.Nudge -> add(nudge(o), cardParams())
            is Overlay.Friction -> add(friction(o), fullParams())
            is Overlay.Block -> add(block(o), fullParams())
        }
    }

    private fun add(v: View, p: WindowManager.LayoutParams) {
        try {
            wm.addView(v, p)
            view = v
        } catch (e: Exception) {
            qlog("overlay failed: $e")
            shown = Overlay.None
        }
    }

    private fun remove() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        handler.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------------ window parameters

    private fun cardParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.BOTTOM }

    /** Full screen and focusable, so the text field can get the keyboard. */
    private fun fullParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.OPAQUE,
    ).apply {
        // Deprecated since Android 11 in favour of window insets; still honoured for overlay windows (unchecked).
        @Suppress("DEPRECATION")
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    // ------------------------------------------------------------------ building blocks

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), service.resources.displayMetrics).toInt()

    private fun column(bg: Int, radius: Int = 0): LinearLayout = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun text(s: String, sizeSp: Float, color: Int = INK, bold: Boolean = false) = TextView(service).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(service).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    /** Always a way out of a full-screen overlay: the home screen (then Settings, the dialer …). */
    private fun homeButton() = button("Go to the home screen") {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    private fun act(a: Action, error: TextView?): Boolean {
        val r = Bridge.service?.onAction(a)
        if (r == null) {
            error?.text = "Qadam's service isn't running."
            return false
        }
        if (!r.accepted) error?.text = r.reason
        return r.accepted
    }

    private fun hhmm(ms: Long): String =
        DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    // ------------------------------------------------------------------ Level 1

    private fun nudge(o: Overlay.Nudge): View {
        val root = column(CARD, radius = 16)
        val err = text("", 13f, WARN)
        root.addView(text("Time to get up?", 18f, bold = true))
        root.addView(text(o.plan, 16f))
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(button("Getting up") { act(Action.GettingUp, err) }, lp)
        if (o.canMore) row.addView(button("5 more min") { act(Action.MoreMinutes, err) }, lp)
        row.addView(button("Wrong, not drifting") { act(Action.Wrong, err) }, lp)
        root.addView(row)
        root.addView(err)
        return LinearLayout(service).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    // ------------------------------------------------------------------ Level 2

    private fun friction(o: Overlay.Friction): View {
        val root = column(PAPER)
        root.gravity = Gravity.CENTER_VERTICAL
        val err = text("", 14f, WARN)
        root.addView(text("Still here.", 26f, bold = true))
        root.addView(text("Your plan: ${o.plan}", 16f))
        root.addView(text("What is your next concrete action? At least ${o.minWords} words. That buys 5 minutes.", 16f))
        val input = EditText(service).apply {
            hint = "e.g. wash the plates now"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(INK)
        }
        root.addView(input)
        root.addView(button("Give me 5 minutes") { act(Action.NextAction(input.text.toString()), err) })
        root.addView(err)
        root.addView(text("Or just get up: walking ends this.", 14f, INK_2))
        root.addView(homeButton())
        return root
    }

    // ------------------------------------------------------------------ Level 3

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun block(o: Overlay.Block): View {
        val root = column(PAPER)
        root.gravity = Gravity.CENTER_VERTICAL
        val err = text("", 14f, WARN)
        root.addView(text("Blocked until ${hhmm(o.untilMs)}", 26f, bold = true))
        root.addView(text("Walk to a Qadam tag and tap it to unlock.", 18f))
        root.addView(text("Calls, Settings and your allowed apps still work.", 14f, INK_2))

        root.addView(homeButton())
        if (o.overridesLeft <= 0) {
            root.addView(text("No overrides left today.", 14f, INK_2))
            return root
        }
        val holdS = (o.holdMs / 1000).toInt()
        val reason = EditText(service).apply {
            hint = "Why do you need it now?"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            visibility = View.GONE
            setTextColor(INK)
        }
        var heldMs = 0L
        val unlock = button("Unlock (override, ${o.overridesLeft} left today)") {
            act(Action.Override(reason.text.toString(), heldMs), err)
        }.apply { visibility = View.GONE }

        val hold = Button(service).apply {
            text = "Hold $holdS s to override"
            isAllCaps = false
        }
        var downAt = 0L
        val ticker = object : Runnable {
            override fun run() {
                val held = SystemClock.elapsedRealtime() - downAt
                val left = ((o.holdMs - held) / 1000).coerceAtLeast(0)
                hold.text = if (left > 0) "Keep holding… $left s" else "Release"
                if (left > 0) handler.postDelayed(this, 250)
            }
        }
        hold.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.elapsedRealtime()
                    handler.post(ticker)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(ticker)
                    val held = SystemClock.elapsedRealtime() - downAt
                    if (held >= o.holdMs) {
                        heldMs = held
                        hold.visibility = View.GONE
                        reason.visibility = View.VISIBLE
                        unlock.visibility = View.VISIBLE
                    } else {
                        hold.text = "Hold $holdS s to override"
                        err.text = "Released after ${held / 1000} s — hold the full $holdS s."
                    }
                }
            }
            true
        }
        root.addView(hold)
        root.addView(reason)
        root.addView(unlock)
        root.addView(err)
        return root
    }

    private companion object {
        val INK = Color.parseColor("#1B1B1B")
        val INK_2 = Color.parseColor("#5A5A55")
        val WARN = Color.parseColor("#B3261E")
        val CARD = Color.parseColor("#F3F1EA")
        val PAPER = Color.parseColor("#FBFAF6")
    }
}
