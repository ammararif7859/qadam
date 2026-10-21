// UNBUILT: never built with the Android SDK. Type-checked only (android-all 17 + Compose desktop + stubs), 21 Oct 2026.
package de.ammar.qadam.service

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process

/**
 * Signals 1, 9 and 10 of SPEC §4, read on demand: screen on/off, audio playing,
 * headphones, a phone call, charging. None of these needs a permission.
 */
class PhoneState(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    fun screenOn(): Boolean = power.isInteractive

    /**
     * True while the lock screen shows. Then no app counts as "in front", so no overlay
     * can ever cover the lock screen or its emergency-call button (AGENTS.md safety rule).
     */
    fun locked(): Boolean = keyguard.isKeyguardLocked

    /** True while any app plays music or video sound. */
    fun audioPlaying(): Boolean = audio.isMusicActive

    fun headphones(): Boolean =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headphoneTypes }

    /** Ringing or in a call (phone or internet call): Qadam must not interfere (SPEC §7). */
    fun inCall(): Boolean = audio.mode in callModes

    fun charging(): Boolean {
        // ACTION_BATTERY_CHANGED is "sticky": registering with no receiver returns the last value.
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private val headphoneTypes: Set<Int> = buildSet {
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        add(AudioDeviceInfo.TYPE_USB_HEADSET)
        if (Build.VERSION.SDK_INT >= 31) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private val callModes: Set<Int> = buildSet {
        add(AudioManager.MODE_RINGTONE)
        add(AudioManager.MODE_IN_CALL)
        add(AudioManager.MODE_IN_COMMUNICATION)
        if (Build.VERSION.SDK_INT >= 30) add(AudioManager.MODE_CALL_SCREENING)
        if (Build.VERSION.SDK_INT >= 33) {
            add(AudioManager.MODE_CALL_REDIRECT)
            add(AudioManager.MODE_COMMUNICATION_REDIRECT)
        }
    }
}

/**
 * Signal 2 of SPEC §4: which app is in front, from Android's usage statistics
 * (needs "Usage access", a special permission granted in Settings).
 */
class ForegroundApp(private val context: Context) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)

    /** Package of the app in front, or null if unknown. */
    var current: String? = null
        private set

    /** When [current] came to the front (wall-clock ms). */
    var sinceMs: Long = 0
        private set

    private var lastQueryMs = 0L

    fun hasAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        // checkOpNoThrow is the current name again (API 36 deprecated unsafeCheckOpNoThrow).
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Reads the usage events since the last call and returns the app in front. */
    fun poll(nowMs: Long): String? {
        if (!hasAccess()) return null
        val from = if (lastQueryMs == 0L) nowMs - 60 * 60_000L else lastQueryMs - 2_000L
        // Returns null while the phone is locked after a reboot (Android 11+, per the docs).
        val events = usm.queryEvents(from, nowMs) ?: return current
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && e.timeStamp >= sinceMs) {
                if (e.packageName != current) {
                    current = e.packageName
                    sinceMs = e.timeStamp
                }
            }
        }
        lastQueryMs = nowMs
        return current
    }
}
