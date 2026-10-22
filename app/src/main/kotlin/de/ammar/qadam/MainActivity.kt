package de.ammar.qadam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import de.ammar.qadam.service.QadamService
import de.ammar.qadam.ui.DataScreen
import de.ammar.qadam.ui.PermissionsScreen
import de.ammar.qadam.ui.QadamTheme
import de.ammar.qadam.ui.RulesScreen
import de.ammar.qadam.ui.TagsScreen
import de.ammar.qadam.ui.TodayScreen

/**
 * The app's one activity (one full screen of an app). It hosts the five screens of
 * SPEC §10 behind a bottom bar, and starts the logger service if it isn't running.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QadamService.start(this)
        setContent {
            QadamTheme {
                Root(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Bridge.uiVisible = true
        if (Bridge.service == null) QadamService.start(this)
        Bridge.service?.wake()
    }

    override fun onPause() {
        Bridge.uiVisible = false
        super.onPause()
    }
}

private val TABS = listOf("Today" to "☀", "Rules" to "⚖", "Tags" to "◉", "Data" to "▤", "Permissions" to "✓")

@Composable
private fun Root(activity: ComponentActivity) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, (label, glyph) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(glyph) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            0 -> TodayScreen(m)
            1 -> RulesScreen(m)
            2 -> TagsScreen(activity, m)
            3 -> DataScreen(m)
            else -> PermissionsScreen(activity, m)
        }
    }
}
