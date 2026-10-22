package de.ammar.qadam.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import de.ammar.qadam.Bridge
import de.ammar.qadam.QadamApp
import de.ammar.qadam.core.engine.Action
import de.ammar.qadam.core.logger.DayTotals
import de.ammar.qadam.core.logger.Reports
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

/**
 * SPEC §10 screen 1 — Today: drift minutes, triggers, chore minutes, the current state,
 * and in Phase 0 the live debug view of every signal (how we check each sensor works).
 */
@Composable
fun TodayScreen(modifier: Modifier) {
    val repo = (LocalContext.current.applicationContext as QadamApp).repo
    val live by Bridge.live.collectAsState()
    var totals by remember { mutableStateOf<DayTotals?>(null) }
    var pauseMsg by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            totals = repo.totals(LocalDate.now(), ZoneId.systemDefault())
            delay(30_000)
        }
    }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Section("Now") {
                KeyValue("Logger", if (live.serviceRunning) "running" else "NOT running")
                if (!live.serviceRunning) Hint("Grant Activity recognition on the Permissions screen; the logger then starts.")
                KeyValue("State", live.state.name)
                KeyValue("Level", live.level.toString())
                KeyValue("Window", live.window.name)
                KeyValue("Chore mode", yesNo(live.chore))
                val svc = Bridge.service
                Button(onClick = {
                    val r = svc?.onAction(Action.Pause)
                    pauseMsg = when {
                        r == null -> "The logger isn't running."
                        r.accepted -> "Paused for 1 hour."
                        else -> r.reason
                    }
                }) { Text("Pause 1 hour (${svc?.pausesLeft() ?: 0} left today)") }
                if (pauseMsg.isNotEmpty()) Hint(pauseMsg)
            }
        }
        item {
            Section("Today") {
                val t = totals
                if (t == null) {
                    Hint("Loading…")
                } else {
                    KeyValue("Drift (rules)", Reports.minutesText(t.driftMin))
                    KeyValue("Drift apps in front", Reports.minutesText(t.driftAppMin))
                    KeyValue("Chore mode", Reports.minutesText(t.choreMin))
                    KeyValue("Nudges / interventions", t.triggers.toString())
                    KeyValue("“Wrong” (false positives)", t.falsePositives.toString())
                    KeyValue("Overrides", t.overrides.toString())
                    KeyValue("Labels", t.labels.entries.joinToString { "${it.key.csv} ${it.value}" }.ifEmpty { "–" })
                }
            }
        }
        item {
            Section("Live signals (debug)") {
                Hint("Updated ${if (live.timeMs > 0) hhmm(live.timeMs) else "–"} · every 2 s while this screen is open")
                KeyValue("Accessibility (scroll counter)", if (live.accessibilityOn) "on" else "OFF")
                KeyValue("Usage access", if (live.usageAccess) "granted" else "MISSING")
                KeyValue("1 Screen on", yesNo(live.screenOn))
                KeyValue("2 App in front", live.app ?: "–")
                KeyValue("   category / session", "${live.appCategory?.name ?: "–"} / ${live.sessionS} s")
                KeyValue("3 Scrolls / taps (total)", "${live.scrollsTotal} / ${live.tapsTotal}")
                KeyValue("4 Steps total / last 5 min", "${live.stepsTotal} / ${live.stepsLast5min}")
                KeyValue("   step source", live.stepSource)
                KeyValue("   motion sensors running", yesNo(live.sensorsRunning))
                KeyValue("5 Motion |a| (m/s²)", fmt(live.motionRms))
                val g = live.gravity
                KeyValue("6 Gravity x / y / z", if (g == null) "–" else "${fmt(g.first, 1)} / ${fmt(g.second, 1)} / ${fmt(g.third, 1)}")
                KeyValue("7 Proximity near", yesNo(live.proximityNear))
                KeyValue("8 Light (lx)", fmt(live.lux, 0))
                KeyValue("9 Audio / headphones", "${yesNo(live.audio)} / ${yesNo(live.headphones)}")
                KeyValue("   in a call", yesNo(live.inCall))
                KeyValue("10 Charging", yesNo(live.charging))
            }
        }
        item {
            Section("Sensor check") {
                if (live.sensors.isEmpty()) Hint("Starts with the logger.")
                for (s in live.sensors) KeyValue(s.name, s.note)
            }
        }
    }
}
