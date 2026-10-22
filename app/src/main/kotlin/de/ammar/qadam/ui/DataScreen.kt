package de.ammar.qadam.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import de.ammar.qadam.QadamApp
import de.ammar.qadam.data.DailyEntity
import de.ammar.qadam.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * SPEC §10 screen 4 — Data: export CSV through Android's file picker, delete all data,
 * and the retention rule (summary rows 30 days, then daily totals only).
 * The export is the only way data leaves the phone (SPEC §3).
 */
@Composable
fun DataScreen(modifier: Modifier) {
    val context = LocalContext.current
    val repo = (context.applicationContext as QadamApp).repo
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var rows by remember { mutableIntStateOf(0) }
    var daily by remember { mutableStateOf<List<DailyEntity>>(emptyList()) }
    var confirm by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()

    LaunchedEffect(message) {
        rows = repo.summaryCount()
        daily = repo.dailyTotals().take(14)
    }

    fun export(uri: Uri?, what: String, write: suspend (Repo, java.io.Writer) -> Int) {
        if (uri == null) return
        scope.launch {
            message = try {
                val n = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w -> write(repo, w) } ?: -1
                }
                if (n < 0) "Could not open the file." else "Exported $n $what rows."
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
        }
    }

    val summaryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        export(uri, "summary") { r, w -> r.exportSummary(w, zone) }
    }
    val eventLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        export(uri, "event") { r, w -> r.exportEvents(w, zone) }
    }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Section("Export") {
                KeyValue("Summary rows stored", rows.toString())
                Button(onClick = { summaryLauncher.launch("qadam-summary-${LocalDate.now()}.csv") }) {
                    Text("Export summary rows (CSV)")
                }
                OutlinedButton(onClick = { eventLauncher.launch("qadam-events-${LocalDate.now()}.csv") }) {
                    Text("Export events (CSV)")
                }
                Hint("Android's file picker asks where to save. The Phase 1 script reads the summary file.")
                Hint("Never put an export into the git repo.")
                if (message.isNotEmpty()) Text(message)
            }
        }
        item {
            Section("Retention") {
                Text("Summary rows are kept 30 days. Older days are reduced to daily totals.")
                if (daily.isEmpty()) Hint("No daily totals yet.")
                for (d in daily) {
                    KeyValue(d.date, "drift ${d.driftMin.toInt()} min · chores ${d.choreMin.toInt()} min · ${d.triggers} nudges")
                }
            }
        }
        item {
            Section("Delete all data") {
                Text("Deletes every summary row, event and daily total. Settings stay.")
                OutlinedButton(onClick = { confirm = true }) { Text("Delete all data…") }
            }
        }
    }

    if (confirm) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete all data?") },
            text = {
                OutlinedTextField(value = typed, onValueChange = { typed = it }, label = { Text("Type DELETE") }, singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = typed == "DELETE", onClick = {
                    confirm = false
                    scope.launch {
                        repo.deleteAllData(System.currentTimeMillis())
                        message = "All data deleted."
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
