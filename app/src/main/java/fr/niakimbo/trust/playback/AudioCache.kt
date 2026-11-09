package fr.niakimbo.trust.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import fr.niakimbo.trust.BuildConfig
import java.io.File

/**
 * Cache disque de l'audio écouté (1 Go max, les plus anciens partent en premier).
 * Réécouter un passage ne retélécharge rien. L'audio reste tel quel : aucune modification.
 */
@OptIn(UnstableApi::class)
class AudioCache(context: Context) {
    val cache = SimpleCache(
        File(context.cacheDir, "audio"),
        LeastRecentlyUsedCacheEvictor(1024L * 1024 * 1024),
        StandaloneDatabaseProvider(context),
    )

    private val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
        .setUserAgent("Trust/${BuildConfig.VERSION_NAME} (Android; github.com/Niakimbo22/trust)")

    val dataSourceFactory: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(http)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}
