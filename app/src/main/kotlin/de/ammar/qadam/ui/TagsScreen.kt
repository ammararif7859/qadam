// UNBUILT: never built with the Android SDK. Type-checked only (android-all 17 + Compose desktop + stubs), 21 Oct 2026.
package de.ammar.qadam.ui

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import de.ammar.qadam.Bridge
import de.ammar.qadam.nfc.TagWriter

/**
 * SPEC §10 screen 3 — Tags: write a name onto an NFC sticker and test it.
 *
 * While "Write" or "Test" is armed, Android's reader mode hands every tag to this screen
 * (and not to [de.ammar.qadam.nfc.TagActivity]), so writing a tag doesn't start chore mode.
 */
@Composable
fun TagsScreen(activity: Activity, modifier: Modifier) {
    val adapter = remember { NfcAdapter.getDefaultAdapter(activity) }
    var name by remember { mutableStateOf("kitchen") }
    var mode by remember { mutableStateOf("") } // "", "write", "test"
    var result by remember { mutableStateOf("") }
    val lastTag by Bridge.lastTag.collectAsState()

    DisposableEffect(mode, name) {
        if (adapter != null && mode.isNotEmpty()) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
            adapter.enableReaderMode(activity, { tag ->
                // Runs on an NFC thread; post the result back to the screen.
                val text = if (mode == "write") {
                    TagWriter.write(tag, name) ?: "Written: ${TagWriter.uriFor(name)}. Tap it again to test."
                } else {
                    TagWriter.read(tag)?.let { "This is Qadam tag “$it”." } ?: "Not a Qadam tag."
                }
                activity.runOnUiThread {
                    result = text
                    mode = ""
                }
            }, flags, null)
        }
        onDispose { adapter?.disableReaderMode(activity) }
    }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Section("NFC") {
                when {
                    adapter == null -> Text("This phone has no NFC.")
                    !adapter.isEnabled -> {
                        Text("NFC is switched off.")
                        OutlinedButton(onClick = { activity.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) {
                            Text("Open NFC settings")
                        }
                    }
                    else -> Text("NFC is on.")
                }
            }
        }
        item {
            Section("Write a tag") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.lowercase().trim() },
                    label = { Text("Tag name (e.g. kitchen, laundry)") },
                    singleLine = true,
                )
                if (!TagWriter.isValidName(name)) Hint("1–24 small letters, digits, - or _")
                Button(
                    onClick = { mode = "write"; result = "Hold an NTAG213/215 sticker to the back of the phone…" },
                    enabled = adapter != null && TagWriter.isValidName(name),
                ) { Text("Write “$name”") }
                OutlinedButton(onClick = { mode = "test"; result = "Hold a tag to the phone…" }, enabled = adapter != null) {
                    Text("Test a tag")
                }
                if (mode.isNotEmpty()) {
                    OutlinedButton(onClick = { mode = ""; result = "" }) { Text("Cancel") }
                }
                if (result.isNotEmpty()) Text(result)
            }
        }
        item {
            Section("Last tag tap") {
                val t = lastTag
                Text(if (t == null) "None since the app started." else "“${t.first}” at ${hhmm(t.second)}")
                Hint("A real tap (outside this screen) starts 30 min of chore mode and lifts a Level 3 block.")
            }
        }
        item {
            Section("Where to stick them (SPEC §9)") {
                Text("Far from the bed, ideally outside your room: next to the kitchen sink or the fridge, by the laundry.")
                Text("Never on the desk or near the bed: an unlock you can reach from the bed is no unlock.")
            }
        }
    }
}
