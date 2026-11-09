package fr.niakimbo.trust.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import fr.niakimbo.trust.BuildConfig
import java.io.File

/**
 * Deux stockages pour l'audio (toujours tel quel, sans aucune modification) :
 * - les livres téléchargés pour l'écoute hors-ligne (jamais effacés automatiquement) ;
 * - un cache d'écoute (1 Go max, les plus anciens partent en premier).
 * La lecture regarde d'abord les téléchargements, puis le cache, puis Internet.
 */
@OptIn(UnstableApi::class)
class AudioCache(context: Context) {
    val database = StandaloneDatabaseProvider(context)

    val streamCache = SimpleCache(
        File(context.cacheDir, "audio"),
        LeastRecentlyUsedCacheEvictor(1024L * 1024 * 1024),
        database,
    )

    val downloadCache = SimpleCache(File(context.filesDir, "downloads"), NoOpCacheEvictor(), database)

    val http: DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
        .setUserAgent("Trust/${BuildConfig.VERSION_NAME} (Android; github.com/Niakimbo22/trust)")

    private val streaming = CacheDataSource.Factory()
        .setCache(streamCache)
        .setUpstreamDataSourceFactory(http)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Source de lecture : téléchargements (lecture seule) → cache d'écoute → réseau. */
    val dataSourceFactory: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(downloadCache)
        .setCacheWriteDataSinkFactory(null)
        .setUpstreamDataSourceFactory(streaming)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}
