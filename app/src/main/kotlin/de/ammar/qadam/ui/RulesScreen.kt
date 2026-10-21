// UNBUILT: never built with the Android SDK. Type-checked only (android-all 17 + Compose desktop + stubs), 21 Oct 2026.
package de.ammar.qadam.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.ammar.qadam.Bridge
import de.ammar.qadam.QadamApp
import de.ammar.qadam.core.config.Knobs
import de.ammar.qadam.core.model.TimeWindow
import de.ammar.qadam.core.rules.Commitment
import de.ammar.qadam.core.rules.Decision
import de.ammar.qadam.core.rules.SettingEdit
import de.ammar.qadam.core.rules.Settings
import de.ammar.qadam.core.rules.TimeRange
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId

/** One installed app for the pickers. */
data class AppEntry(val pkg: String, val label: String)

fun launcherApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    return pm.queryIntentActivities(intent, 0)
        .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

/**
 * SPEC §10 screen 2 — Rules: drift and allowed apps, thresholds, bedtime, meal windows,
 * if-then plans, the ladder levels, and pending loosenings. Every change goes through the
 * §8 rule: tightening now, loosening after 24 hours.
 */
@Composable
fun RulesScreen(modifier: Modifier) {
    val context = LocalContext.current
    val repo = (context.applicationContext as QadamApp).repo
    val scope = rememberCoroutineScope()
    val settings by repo.settingsFlow.collectAsState(initial = Settings())
    val pending by repo.pendingFlow.collectAsState(initial = emptyList())
    var message by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf<String?>(null) } // "drift" or "allowed"
    val zone = ZoneId.systemDefault()

    fun request(edit: SettingEdit) {
        scope.launch {
            val now = System.currentTimeMillis()
            message = when (val d = repo.request(edit, now)) {
                is Decision.Immediate -> "Applied now."
                is Decision.Pending -> "A loosening: ${Commitment.label(d.change.effectiveMs, now, zone)}."
                is Decision.Rejected -> "Not possible: ${d.reason}."
            }
            Bridge.service?.reloadSettings()
        }
    }

    val labels = remember { launcherApps(context).associate { it.pkg to it.label } }

    LazyColumn(modifier.fillMaxSize()) {
        if (message.isNotEmpty()) item { Section("Last change") { Text(message) } }

        item {
            Section("Interventions") {
                val options = listOf(0 to "Logging only (Phase 0)", 1 to "Level 1 nudges only (Phase 2)", 3 to "Full ladder (Phase 3)")
                for ((level, text) in options) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { request(SettingEdit.SetMaxLevel(level)) }) {
                        RadioButton(selected = settings.maxLevel == level, onClick = { request(SettingEdit.SetMaxLevel(level)) })
                        Text(text)
                    }
                }
                Hint("Switching levels on works at once; switching them off waits 24 hours.")
            }
        }

        item {
            Section("Drift apps (only these can trigger anything)") {
                if (settings.driftApps.isEmpty()) Hint("None yet. Add PipePipe, YouTube …")
                for (pkg in settings.driftApps.sorted()) {
                    AppRow(labels[pkg] ?: pkg, pkg, "Remove") { request(SettingEdit.RemoveDriftApp(pkg)) }
                }
                OutlinedButton(onClick = { picker = "drift" }) { Text("Add a drift app") }
                Hint("Removing a drift app is a loosening: it waits 24 hours.")
            }
        }

        item {
            Section("Always-allowed apps (never blocked)") {
                Hint("The phone's dialer, SMS, clock, camera, Settings and home screen are always allowed anyway.")
                for (pkg in settings.allowedApps.sorted()) {
                    AppRow(labels[pkg] ?: pkg, pkg, "Remove") { request(SettingEdit.RemoveAllowedApp(pkg)) }
                }
                OutlinedButton(onClick = { picker = "allowed" }) { Text("Add an allowed app") }
            }
        }

        item {
            Section("Bedtime and meal windows") {
                TimeField("Bedtime (NIGHT starts)", settings.bedtime.toString()) { v ->
                    parseTime(v)?.let { request(SettingEdit.SetBedtime(it)) } ?: run { message = "Use HH:mm, e.g. 23:30" }
                }
                TimeField("Night ends", settings.nightEnd.toString()) { v ->
                    parseTime(v)?.let { request(SettingEdit.SetNightEnd(it)) } ?: run { message = "Use HH:mm, e.g. 06:00" }
                }
                TimeField("Meal windows (when you usually finish eating)", settings.mealWindows.joinToString(", ")) { v ->
                    val list = runCatching { v.split(",").filter { it.isNotBlank() }.map { TimeRange.parse(it) } }.getOrNull()
                    if (list == null) message = "Use 13:00-14:00, 19:30-20:15" else request(SettingEdit.SetMealWindows(list))
                }
                Hint("In meal windows the time thresholds are halved (SPEC §6 DANGER WINDOW).")
            }
        }

        item {
            Section("If-then plans (the Level 1 text)") {
                for (w in listOf(TimeWindow.DAY, TimeWindow.MEAL, TimeWindow.NIGHT)) {
                    val title = when (w) {
                        TimeWindow.DAY -> "Default (any time)"
                        TimeWindow.MEAL -> "Meal windows (empty = default)"
                        TimeWindow.NIGHT -> "Night (empty = default)"
                    }
                    TimeField(title, settings.plans[w].orEmpty(), wide = true) { request(SettingEdit.SetPlan(w, it.trim())) }
                }
            }
        }

        item {
            Section("Level 2 grayscale") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = settings.grayscale, onCheckedChange = { request(SettingEdit.SetGrayscale(it)) })
                    Text("  Grey screen during Levels 2 and 3")
                }
                Hint("Needs a one-time grant from the laptop — see the Permissions screen.")
            }
        }

        item {
            Section("Thresholds") {
                Hint("Placeholders until Phase 1. Higher/lower: the hint says which way waits 24 h.")
                val t = settings.thresholds()
                for (k in Knobs.editable) {
                    val loosen = if (k.looser == de.ammar.qadam.core.config.Looser.HIGHER) "higher = looser" else "lower = looser"
                    TimeField("${k.label} — $loosen", trimNum(t[k.key])) { v ->
                        val num = v.trim().replace(',', '.').toDoubleOrNull()
                        if (num == null) message = "Not a number: $v" else request(SettingEdit.SetThreshold(k.key, num))
                    }
                }
            }
        }

        item {
            Section("Pending loosenings") {
                if (pending.isEmpty()) Hint("None.")
                val now = System.currentTimeMillis()
                for ((id, p) in pending) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.edit.encode())
                            Hint(Commitment.label(p.effectiveMs, now, zone))
                        }
                        TextButton(onClick = {
                            scope.launch { repo.cancelPending(id, System.currentTimeMillis()) }
                        }) { Text("Cancel") }
                    }
                }
            }
        }
    }

    picker?.let { which ->
        AppPicker(
            title = if (which == "drift") "Add a drift app" else "Add an allowed app",
            onPick = { pkg ->
                request(if (which == "drift") SettingEdit.AddDriftApp(pkg) else SettingEdit.AddAllowedApp(pkg))
                picker = null
            },
            onDismiss = { picker = null },
        )
    }
}

private fun parseTime(s: String): LocalTime? = runCatching { LocalTime.parse(s.trim()) }.getOrNull()

private fun trimNum(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

@Composable
private fun AppRow(label: String, pkg: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Hint(pkg)
        }
        TextButton(onClick = onAction) { Text(action) }
    }
}

/** A text field with its own "Set" button (nothing changes until it is pressed). */
@Composable
private fun TimeField(label: String, current: String, wide: Boolean = false, onSet: (String) -> Unit) {
    var text by remember(current) { mutableStateOf(current) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Hint(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = !wide,
                modifier = if (wide) Modifier.weight(1f) else Modifier.width(220.dp),
            )
            Button(onClick = { onSet(text) }, enabled = text != current, modifier = Modifier.padding(start = 8.dp)) { Text("Set") }
        }
    }
}

/** The installed apps, searchable, for the drift and allowed lists. */
@Composable
private fun AppPicker(title: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apps = remember { launcherApps(context) }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true)
                val shown = apps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                LazyColumn(Modifier.heightIn(max = 400.dp).fillMaxWidth()) {
                    items(shown, key = { it.pkg }) { a ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(a.pkg) }.padding(vertical = 8.dp),
                        ) {
                            Text(a.label)
                            Hint(a.pkg)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
