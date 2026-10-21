// UNBUILT: written without the Android SDK; not yet compiled.
package de.ammar.qadam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

/** The app's only screen-hosting activity. Step 1: an empty Compose screen. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { Text("Qadam") }
            }
        }
    }
}
