// UNBUILT: never built with the Android SDK. Type-checked only (android-all 17 + Compose desktop + stubs), 21 Oct 2026.
package de.ammar.qadam

import android.util.Log
import de.ammar.qadam.accessibility.QadamAccessibilityService
import de.ammar.qadam.core.model.AppCategory
import de.ammar.qadam.core.model.DriftState
import de.ammar.qadam.core.model.TimeWindow
import de.ammar.qadam.service.QadamService
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger

/** Log tag (AGENTS.md): read with `adb logcat -s Qadam`. */
const val TAG = "Qadam"

fun qlog(msg: String) {
    Log.i(TAG, msg)
}

/** One sensor as the debug view shows it. */
data class SensorStatus(val name: String, val present: Boolean, val note: String)

/** Everything the Today screen's live debug view shows (SPEC §10). Never screen content. */
data class LiveSignals(
    val timeMs: Long = 0,
    val serviceRunning: Boolean = false,
    val accessibilityOn: Boolean = false,
    val usageAccess: Boolean = false,
    val screenOn: Boolean = false,
    val app: String? = null,
    val appCategory: AppCategory? = null,
    val sessionS: Int = 0,
    val audio: Boolean = false,
    val headphones: Boolean = false,
    val charging: Boolean = false,
    val inCall: Boolean = false,
    val sensorsRunning: Boolean = false,
    val stepsTotal: Int = 0,
    val stepsLast5min: Int = 0,
    val stepSource: String = "",
    val motionRms: Double? = null,
    val gravity: Triple<Double, Double, Double>? = null,
    val proximityNear: Boolean? = null,
    val lux: Double? = null,
    val scrollsTotal: Int = 0,
    val tapsTotal: Int = 0,
    val state: DriftState = DriftState.IDLE,
    val window: TimeWindow = TimeWindow.DAY,
    val level: Int = 0,
    val chore: Boolean = false,
    val sensors: List<SensorStatus> = emptyList(),
)

/**
 * The meeting point inside the app's process. The foreground service, the accessibility
 * service, the NFC activity and the screens are separate Android components; they find
 * each other here instead of through Android's slower inter-process routes.
 */
object Bridge {
    @Volatile
    var service: QadamService? = null

    @Volatile
    var a11y: QadamAccessibilityService? = null

    /** Scroll gestures and taps counted by the accessibility service since the last tick. */
    val scrolls = AtomicInteger()
    val taps = AtomicInteger()

    /** Live values for the debug view. */
    val live = MutableStateFlow(LiveSignals())

    /** The last tag read (name and time), for the Tags screen's test. */
    val lastTag = MutableStateFlow<Pair<String, Long>?>(null)

    /** True while a Qadam screen is visible: the service then refreshes [live] more often. */
    @Volatile
    var uiVisible = false
}
