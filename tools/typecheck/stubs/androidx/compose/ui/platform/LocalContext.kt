package androidx.compose.ui.platform
import androidx.compose.runtime.staticCompositionLocalOf
val LocalContext = staticCompositionLocalOf<android.content.Context> { error("no context") }
