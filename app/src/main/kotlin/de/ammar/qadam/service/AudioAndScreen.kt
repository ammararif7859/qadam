// UNBUILT: never built with the Android SDK. Type-checked only (android-all 17 + Compose desktop + stubs), 21 Oct 2026.
package de.ammar.qadam.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import de.ammar.qadam.core.engine.AudioControl
import de.ammar.qadam.qlog

/**
 * Pauses other apps' audio by taking "audio focus" (SPEC §7). Apps that respect focus
 * (PipePipe should) pause when another app takes it. Android 15+ allows this only to
 * apps on screen or running a foreground service — Qadam runs one.
 */
class AudioController(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var hold: AudioFocusRequest? = null

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * Level 2: HOLD takes transient focus, so the player pauses and may resume when
     * Qadam lets go (FREE) — that happens when steps start.
     */
    fun set(mode: AudioControl) {
        when (mode) {
            AudioControl.HOLD -> if (hold == null) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { }
                    .build()
                val r = am.requestAudioFocus(req)
                qlog("audio hold: request result $r")
                hold = req
            }
            AudioControl.FREE -> hold?.let {
                am.abandonAudioFocusRequest(it)
                hold = null
                qlog("audio hold released")
            }
        }
    }

    /** Level 3: permanent focus, so players stop; let go after a moment (they won't restart by themselves). */
    fun stopOthers() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        val r = am.requestAudioFocus(req)
        qlog("audio stop: request result $r")
        handler.postDelayed({ am.abandonAudioFocusRequest(req) }, 1500)
    }
}

/**
 * Level 2 grayscale (SPEC §7) through Android's own colour-correction setting. Writing it
 * needs WRITE_SECURE_SETTINGS, which only the one-time adb grant gives:
 * `adb shell pm grant de.ammar.qadam android.permission.WRITE_SECURE_SETTINGS`
 * The setting names are Android-internal (not public API) — unchecked on HiOS.
 */
class Grayscale(private val context: Context) {
    private var on = false
    private var previousEnabled = 0
    private var previousMode = -1

    fun canWrite(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun set(wanted: Boolean) {
        if (wanted == on || !canWrite()) return
        val cr = context.contentResolver
        try {
            if (wanted) {
                previousEnabled = Settings.Secure.getInt(cr, KEY_ENABLED, 0)
                previousMode = Settings.Secure.getInt(cr, KEY_MODE, -1)
                Settings.Secure.putInt(cr, KEY_MODE, MONOCHROMACY)
                Settings.Secure.putInt(cr, KEY_ENABLED, 1)
            } else {
                Settings.Secure.putInt(cr, KEY_ENABLED, previousEnabled)
                if (previousMode >= 0) Settings.Secure.putInt(cr, KEY_MODE, previousMode)
            }
            on = wanted
            qlog("grayscale $wanted")
        } catch (e: SecurityException) {
            qlog("grayscale failed: ${e.message}")
        }
    }

    private companion object {
        const val KEY_ENABLED = "accessibility_display_daltonizer_enabled"
        const val KEY_MODE = "accessibility_display_daltonizer"
        const val MONOCHROMACY = 0
    }
}

/** One vibration for Level 1 and Level 2 (SPEC §7). */
fun vibrateOnce(context: Context) {
    val effect = VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)
    if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(effect)
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java).vibrate(effect)
    }
}

/**
 * The phone's own dialer, SMS app, clock, camera, Settings and home screen. Their package
 * names differ per maker (HiOS has its own), so they are looked up at runtime and added to
 * the never-blocked list (AGENTS.md safety rules).
 */
object ProtectedRuntime {
    fun resolve(context: Context): Set<String> {
        val pm = context.packageManager
        val out = mutableSetOf<String>()
        fun handler(intent: Intent) {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0).forEach { out += it.activityInfo.packageName }
        }
        runCatching { context.getSystemService(TelecomManager::class.java).defaultDialerPackage?.let { out += it } }
        runCatching { Telephony.Sms.getDefaultSmsPackage(context)?.let { out += it } }
        handler(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        handler(Intent(Intent.ACTION_DIAL))
        handler(Intent(AlarmClock.ACTION_SHOW_ALARMS))
        handler(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        handler(Intent(Settings.ACTION_SETTINGS))
        qlog("protected apps from the phone: $out")
        return out
    }
}
