// UNBUILT: never built with the Android SDK. Type-checked only (android-all 17 + Compose desktop + stubs), 21 Oct 2026.
package de.ammar.qadam.nfc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import de.ammar.qadam.Bridge
import de.ammar.qadam.core.engine.Action
import de.ammar.qadam.qlog
import de.ammar.qadam.service.QadamService

/**
 * Android opens this invisible screen when a Qadam tag (qadam://tag/<name>) touches the
 * phone (SPEC §4 signal 11, §9). It tells the service — chore mode starts, a Level 3
 * block lifts — shows a short message and closes at once.
 */
class TagActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = TagWriter.nameFromUri(intent?.data)
        if (name != null) {
            qlog("tag tapped: $name")
            Bridge.lastTag.value = name to System.currentTimeMillis()
            val svc = Bridge.service
            if (svc != null && svc.isReady) {
                svc.onAction(Action.TagTapped(name))
            } else {
                QadamService.start(this, Intent(QadamService.ACTION_TAG).putExtra(QadamService.EXTRA_TAG, name))
            }
            Toast.makeText(this, "Qadam: chore mode ($name)", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
