// UNBUILT: never built with the Android SDK. Type-checked only (android-all 17 + Compose desktop + stubs), 21 Oct 2026.
package de.ammar.qadam.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.ammar.qadam.Bridge
import de.ammar.qadam.qlog

/**
 * A broadcast receiver is a small entry point Android calls when something happens.
 * This one runs after a reboot (and after an app update) and starts the logger again —
 * SPEC §12 scenario 7.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            qlog("boot/update: starting service")
            QadamService.start(context)
        }
    }
}

/**
 * Buttons inside Qadam's notifications: the six sampling answers and the three Level 1
 * buttons of the screen-off nudge. If the service isn't running, it is started with the
 * button's intent, so the tap isn't lost.
 */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = Bridge.service
        if (svc != null && svc.isReady) {
            svc.handleIntent(intent)
        } else {
            QadamService.start(context, Intent(intent))
        }
    }

    companion object {
        const val ACTION_LABEL = "de.ammar.qadam.LABEL"
        const val ACTION_LADDER = "de.ammar.qadam.LADDER"
        const val EXTRA_LABEL = "label"
        const val EXTRA_PLANNED = "planned"
        const val EXTRA_WHICH = "which"
        const val WHICH_UP = "up"
        const val WHICH_MORE = "more"
        const val WHICH_WRONG = "wrong"
    }
}
