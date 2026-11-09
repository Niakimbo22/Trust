package fr.niakimbo.trust.playback

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import fr.niakimbo.trust.R
import fr.niakimbo.trust.TrustApp

/** Service qui fait les téléchargements en arrière-plan, avec une notification de progression. */
@OptIn(UnstableApi::class)
class TrustDownloadService : DownloadService(
    NOTIFICATION_ID, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, CHANNEL_ID, R.string.downloads_channel, 0,
) {
    private val helper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = (application as TrustApp).container.downloads.manager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        helper.buildProgressNotification(this, R.drawable.ic_headphones, null, getString(R.string.downloads_progress), downloads, notMetRequirements)

    private companion object {
        const val NOTIFICATION_ID = 7
        const val CHANNEL_ID = "downloads"
    }
}
