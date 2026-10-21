// UNBUILT: never built with the Android SDK. Type-checked only (android-all 17 + Compose desktop + stubs), 21 Oct 2026.
package de.ammar.qadam.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import de.ammar.qadam.Bridge
import de.ammar.qadam.QadamApp
import de.ammar.qadam.core.engine.Overlay
import de.ammar.qadam.core.model.EventRow
import de.ammar.qadam.core.model.EventType
import de.ammar.qadam.core.signals.GestureCounter
import de.ammar.qadam.qlog
import kotlinx.coroutines.launch

/**
 * An accessibility service is a part of an app that Android tells about things happening
 * on screen; the user must switch it on by hand in Settings → Accessibility.
 *
 * Qadam's only listens to two event types (scrolled, clicked — see
 * `res/xml/accessibility_service_config.xml`) and only counts them. It never reads text,
 * titles, URLs or the screen's structure: `canRetrieveWindowContent` is false, so Android
 * doesn't even hand that over (SPEC §3, AGENTS.md privacy rules).
 *
 * It also draws the Level 1–3 overlays: only an accessibility service may draw over every
 * app without the separate "display over other apps" permission.
 */
class QadamAccessibilityService : AccessibilityService() {
    private var overlays: Overlays? = null
    private val scrollGestures = GestureCounter(400)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Bridge.a11y = this
        overlays = Overlays(this)
        log(EventType.ACCESSIBILITY_ON)
        qlog("accessibility connected")
        Bridge.service?.refreshOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Counts only. The package name is used just to skip Qadam's own overlays and the
        // system bar; nothing about the event is stored.
        val pkg = event.packageName?.toString()
        if (pkg == packageName || pkg == "com.android.systemui") return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                if (scrollGestures.onEvent(event.eventTime)) Bridge.scrolls.incrementAndGet()
            AccessibilityEvent.TYPE_VIEW_CLICKED -> Bridge.taps.incrementAndGet()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (Bridge.a11y !== this) return
        overlays?.show(Overlay.None)
        overlays = null
        Bridge.a11y = null
        log(EventType.ACCESSIBILITY_OFF)
        qlog("accessibility disconnected")
    }

    private fun log(type: EventType) {
        val app = application as QadamApp
        val now = System.currentTimeMillis()
        app.appScope.launch { app.repo.addEvents(listOf(EventRow(now, type))) }
    }

    /** Draws (or removes) the overlay the engine asked for. Safe to call every tick. */
    fun showOverlay(o: Overlay) {
        overlays?.show(o)
    }

    /** Level 3: go to the home screen. */
    fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }
}
