package de.ammar.qadam.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import de.ammar.qadam.Bridge
import de.ammar.qadam.accessibility.QadamAccessibilityService
import de.ammar.qadam.service.ForegroundApp
import de.ammar.qadam.service.Grayscale
import de.ammar.qadam.service.QadamService
import kotlinx.coroutines.delay

/**
 * SPEC §10 screen 5 — Permissions: each permission with its status and a button to its
 * settings page, plus the HiOS battery settings of SPEC §13 (no status: Android can't
 * report them).
 */
@Composable
fun PermissionsScreen(activity: Activity, modifier: Modifier) {
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            refresh++
        }
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh++
        QadamService.start(activity)
    }
    fun open(intent: Intent) = runCatching { activity.startActivity(intent) }
    fun appInfo() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
    // The statuses below are plain values, not Compose state: rebuild them every 2 s.
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
      key(refresh) {
        run {
            Section("Logger service") {
                KeyValue("Running", yesNo(Bridge.service != null))
                if (Bridge.service == null) {
                    OutlinedButton(onClick = { QadamService.start(activity) }) { Text("Start it") }
                }
            }
        }
        run {
            Section("1. Activity recognition (required)") {
                val ok = granted(activity, Manifest.permission.ACTIVITY_RECOGNITION)
                KeyValue("Status", if (ok) "granted" else "MISSING")
                Hint("Android 14 lets the health-type logger run only with this. It also lets a phone with a step sensor use it.")
                if (!ok) OutlinedButton(onClick = { askPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION) }) { Text("Grant") }
            }
        }
        run {
            Section("2. Notifications") {
                val ok = Build.VERSION.SDK_INT < 33 || granted(activity, Manifest.permission.POST_NOTIFICATIONS)
                KeyValue("Status", if (ok) "granted" else "MISSING")
                Hint("For the “Right now?” questions, screen-off nudges and the morning summary.")
                if (!ok) {
                    OutlinedButton(onClick = { askPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Grant") }
                }
            }
        }
        run {
            Section("3. Usage access") {
                val ok = ForegroundApp(activity).hasAccess()
                KeyValue("Status", if (ok) "granted" else "MISSING")
                Hint("Tells Qadam which app is in front. Settings → Usage access → Qadam → on.")
                OutlinedButton(onClick = { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Open Usage access") }
            }
        }
        run {
            Section("4. Accessibility: Qadam scroll counter") {
                val ok = accessibilityEnabled(activity)
                KeyValue("Status", if (ok) "on" else "OFF")
                Hint("Counts scrolls and taps (never reads the screen) and shows the nudges over apps.")
                Hint("If the switch is greyed out (“Restricted setting”): App info → ⋮ (top right) → Allow restricted settings, then try again.")
                OutlinedButton(onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open Accessibility") }
                OutlinedButton(onClick = { appInfo() }) { Text("Open App info") }
            }
        }
        run {
            Section("5. Battery: Unrestricted") {
                val pm = activity.getSystemService(PowerManager::class.java)
                val ok = pm.isIgnoringBatteryOptimizations(activity.packageName)
                KeyValue("Android battery optimisation", if (ok) "off (good)" else "ON")
                if (!ok) {
                    OutlinedButton(onClick = {
                        @Suppress("BatteryLife")
                        open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")))
                    }) { Text("Allow running in the background") }
                }
                Hint("Also: App info → Battery → Unrestricted.")
                OutlinedButton(onClick = { appInfo() }) { Text("Open App info") }
            }
        }
        run {
            Section("6. HiOS (Tecno) settings — no status available") {
                Text("a) Settings → Battery Lab → Battery Saver Settings → App Power Saving Management → Qadam off.")
                Text("b) Phone Master → Auto-start Management → allow Qadam.")
                Text("c) Recent apps: pull Qadam's card down / tap its lock icon so it is locked.")
                Hint("Menu names change between HiOS versions. The real test: 3 days without the logger being killed (Today shows the service; the morning summary reports off-time).")
            }
        }
        run {
            Section("7. NFC") {
                val nfc = NfcAdapter.getDefaultAdapter(activity)
                KeyValue("Status", when {
                    nfc == null -> "no NFC"
                    nfc.isEnabled -> "on"
                    else -> "OFF"
                })
                if (nfc != null && !nfc.isEnabled) {
                    OutlinedButton(onClick = { open(Intent(Settings.ACTION_NFC_SETTINGS)) }) { Text("Open NFC settings") }
                }
            }
        }
        run {
            Section("8. Grayscale (optional, Level 2)") {
                val ok = Grayscale(activity).canWrite()
                KeyValue("Status", if (ok) "granted" else "not granted")
                Hint("One-time grant from the laptop, with the phone on USB debugging:")
                Text("adb shell pm grant de.ammar.qadam android.permission.WRITE_SECURE_SETTINGS")
                Hint("It lets Qadam switch Android's colour correction to greyscale and back.")
            }
        }
      }
    }
}

private fun granted(context: Context, permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun accessibilityEnabled(context: Context): Boolean {
    if (Bridge.a11y != null) return true
    val me = ComponentName(context, QadamAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabled.split(':').any { it.equals(me, ignoreCase = true) }
}
