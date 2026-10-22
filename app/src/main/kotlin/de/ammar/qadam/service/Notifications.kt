package de.ammar.qadam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import de.ammar.qadam.MainActivity
import de.ammar.qadam.R
import de.ammar.qadam.core.model.Label

/**
 * All of Qadam's notifications. A notification channel is a category the user can switch
 * on or off in Settings; Android 8+ requires one for every notification.
 */
class Notifications(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_SERVICE, "Logging (always on)", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Qadam's logger runs. Android requires it."
                    setShowBadge(false)
                },
                NotificationChannel(CH_SAMPLING, "Right now? (about 5 a day)", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Experience sampling: one tap tells Qadam what you are doing."
                },
                NotificationChannel(CH_NUDGE, "Nudges with the screen off", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Level 1 when audio plays with the screen off."
                },
                NotificationChannel(CH_SUMMARY, "Morning summary", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun broadcast(code: Int, intent: Intent): PendingIntent = PendingIntent.getBroadcast(
        context, code, intent.setClass(context, ActionReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The permanent notification of the foreground service. */
    fun foreground(text: String = "Logging. No interventions until you switch them on."): Notification =
        Notification.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_qadam)
            .setContentTitle("Qadam")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openApp())
            .build()

    fun updateForeground(text: String) = nm.notify(ID_FOREGROUND, foreground(text))

    /** Experience sampling (SPEC §11): six answers, one tap, the app doesn't open. */
    fun showSampling(plannedMs: Long) {
        val small = RemoteViews(context.packageName, R.layout.notification_sampling_small)
        val big = RemoteViews(context.packageName, R.layout.notification_sampling_big)
        for ((i, pair) in LABEL_BUTTONS.withIndex()) {
            val (viewId, label) = pair
            val pi = broadcast(
                REQ_LABEL + i,
                Intent(ActionReceiver.ACTION_LABEL)
                    .putExtra(ActionReceiver.EXTRA_LABEL, label.name)
                    .putExtra(ActionReceiver.EXTRA_PLANNED, plannedMs),
            )
            small.setOnClickPendingIntent(viewId, pi)
            big.setOnClickPendingIntent(viewId, pi)
        }
        val n = Notification.Builder(context, CH_SAMPLING)
            .setSmallIcon(R.drawable.ic_stat_qadam)
            .setContentTitle("Right now?")
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setCustomHeadsUpContentView(small) // heads-up can be as low as 88 dp: one row
            .setAutoCancel(true)
            .setTimeoutAfter(60 * 60_000L)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        nm.notify(ID_SAMPLING, n)
    }

    fun cancelSampling() = nm.cancel(ID_SAMPLING)

    /** Level 1 while the screen is off (audio drift): the three buttons as notification actions. */
    fun showNudge(plan: String, canMore: Boolean) {
        fun action(code: Int, title: String, which: String) = Notification.Action.Builder(
            null, title,
            broadcast(code, Intent(ActionReceiver.ACTION_LADDER).putExtra(ActionReceiver.EXTRA_WHICH, which)),
        ).build()
        val b = Notification.Builder(context, CH_NUDGE)
            .setSmallIcon(R.drawable.ic_stat_qadam)
            .setContentTitle("Time to get up?")
            .setContentText(plan)
            .setStyle(Notification.BigTextStyle().bigText(plan))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(action(REQ_LADDER, "Getting up", ActionReceiver.WHICH_UP))
        if (canMore) b.addAction(action(REQ_LADDER + 1, "5 more minutes", ActionReceiver.WHICH_MORE))
        b.addAction(action(REQ_LADDER + 2, "Wrong, not drifting", ActionReceiver.WHICH_WRONG))
        nm.notify(ID_NUDGE, b.build())
    }

    fun cancelNudge() = nm.cancel(ID_NUDGE)

    /** SPEC §8: yesterday's numbers and how long Qadam was off. */
    fun showMorning(text: String) {
        val n = Notification.Builder(context, CH_SUMMARY)
            .setSmallIcon(R.drawable.ic_stat_qadam)
            .setContentTitle("Qadam — yesterday")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        nm.notify(ID_MORNING, n)
    }

    companion object {
        const val CH_SERVICE = "service"
        const val CH_SAMPLING = "sampling"
        const val CH_NUDGE = "nudge"
        const val CH_SUMMARY = "summary"
        const val ID_FOREGROUND = 1
        const val ID_SAMPLING = 2
        const val ID_NUDGE = 3
        const val ID_MORNING = 4
        private const val REQ_LABEL = 100
        private const val REQ_LADDER = 200

        private val LABEL_BUTTONS = listOf(
            R.id.label_chores to Label.CHORES,
            R.id.label_studying to Label.STUDYING,
            R.id.label_eating to Label.EATING,
            R.id.label_resting to Label.RESTING,
            R.id.label_drifting to Label.DRIFTING,
            R.id.label_other to Label.OTHER,
        )
    }
}
