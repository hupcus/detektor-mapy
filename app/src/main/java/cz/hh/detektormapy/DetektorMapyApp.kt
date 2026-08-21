package cz.hh.detektormapy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import cz.hh.detektormapy.net.NetworkUsageStore
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class DetektorMapyApp : Application() {

    /**
     * Injected here only to exist from the first moment of the process.
     *
     * It restores today's download counter from disk, and that has to happen before any tile is
     * fetched -- created lazily when the storage screen opens, it would fold in a stale total
     * long after the counting had already started.
     */
    @Inject
    lateinit var networkUsage: NetworkUsageStore

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_TRACKING,
            getString(R.string.track_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.track_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
    }
}
