package de.ammar.qadam

import android.app.Application
import de.ammar.qadam.data.QadamDb
import de.ammar.qadam.data.Repo
import de.ammar.qadam.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The Application object: created once when the app's process starts, before any screen
 * or service. It holds the database and the notification channels everything else uses.
 */
class QadamApp : Application() {
    lateinit var repo: Repo
        private set

    /** For short database writes that must outlive a screen or service (e.g. "service stopped"). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        repo = Repo(QadamDb.open(this))
        Notifications(this).createChannels()
    }
}
