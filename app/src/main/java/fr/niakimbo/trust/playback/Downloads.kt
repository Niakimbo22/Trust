package fr.niakimbo.trust.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import fr.niakimbo.trust.content.BookInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** État du téléchargement d'un livre. */
sealed interface BookDownload {
    data object None : BookDownload
    data class InProgress(val percent: Int) : BookDownload
    data object Done : BookDownload
    data object Failed : BookDownload
}

/**
 * Écoute hors-ligne : télécharge les fichiers audio d'un livre (copie exacte, non modifiée)
 * dans le stockage de l'app, via le service de téléchargement de Media3.
 */
@OptIn(UnstableApi::class)
class Downloads(private val context: Context, cache: AudioCache, private val scope: CoroutineScope) {
    val manager = DownloadManager(
        context, cache.database, cache.downloadCache, cache.http, Executors.newFixedThreadPool(2),
    ).apply { maxParallelDownloads = 2 }

    private val _states = MutableStateFlow<Map<String, BookDownload>>(emptyMap())
    /** Par code de livre. */
    val states: StateFlow<Map<String, BookDownload>> = _states.asStateFlow()

    private val _bytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _bytes.asStateFlow()

    init {
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(m: DownloadManager, d: Download, e: Exception?) = refresh()
            override fun onDownloadRemoved(m: DownloadManager, d: Download) = refresh()
            override fun onInitialized(m: DownloadManager) {
                refresh()
                resumeUnfinished()
            }
        })
        // Progression : on rafraîchit chaque seconde tant qu'un téléchargement tourne.
        scope.launch {
            while (true) {
                if (manager.currentDownloads.isNotEmpty()) refresh()
                delay(1000)
            }
        }
    }

    fun download(book: BookInfo) {
        book.audio.forEachIndexed { i, a ->
            val request = DownloadRequest.Builder("${book.code}#$i", Uri.parse(a.url)).build()
            DownloadService.sendAddDownload(context, TrustDownloadService::class.java, request, false)
        }
    }

    fun remove(book: BookInfo) {
        book.audio.indices.forEach { i ->
            DownloadService.sendRemoveDownload(context, TrustDownloadService::class.java, "${book.code}#$i", false)
        }
    }

    fun removeAll() = DownloadService.sendRemoveAllDownloads(context, TrustDownloadService::class.java, false)

    /**
     * Des téléchargements coupés par la fermeture de l'app restaient bloqués « en cours » :
     * on relance le service, qui les reprend là où ils en étaient.
     */
    private fun resumeUnfinished() {
        val unfinished = manager.currentDownloads.any {
            it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_RESTARTING
        }
        // Peut être refusé si l'app n'est pas au premier plan : sans gravité, ce sera pour la prochaine ouverture.
        if (unfinished) runCatching { DownloadService.start(context, TrustDownloadService::class.java) }
    }

    private fun refresh() {
        val all = mutableListOf<Download>()
        manager.downloadIndex.getDownloads().use { c -> while (c.moveToNext()) all += c.download }
        val byBook = all.groupBy { it.request.id.substringBefore('#') }
        _states.value = byBook.mapValues { (_, list) ->
            when {
                list.all { it.state == Download.STATE_COMPLETED } -> BookDownload.Done
                list.any { it.state == Download.STATE_FAILED } -> BookDownload.Failed
                else -> {
                    val pct = list.map { if (it.state == Download.STATE_COMPLETED) 100f else it.percentDownloaded.coerceAtLeast(0f) }.average()
                    BookDownload.InProgress(pct.toInt())
                }
            }
        }
        _bytes.value = all.sumOf { it.bytesDownloaded }
    }
}
