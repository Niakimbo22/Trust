package fr.niakimbo.trust

import android.app.Application
import android.content.Context
import fr.niakimbo.trust.content.ContentRepository
import fr.niakimbo.trust.data.Journal
import fr.niakimbo.trust.data.SettingsStore
import fr.niakimbo.trust.data.db.TrustDatabase
import fr.niakimbo.trust.playback.AudioCache
import fr.niakimbo.trust.playback.PlaybackHub
import fr.niakimbo.trust.playback.PlayerController
import fr.niakimbo.trust.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Injection manuelle : un seul conteneur qui crée les objets partagés de l'app.
 * Plus simple qu'Hilt pour débuter, et facile à remplacer plus tard.
 */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(context)
    val updateManager = UpdateManager(context, settings, appScope)
    val content = ContentRepository(context)
    val audioCache by lazy { AudioCache(context) }
    val playbackHub = PlaybackHub()
    val database by lazy { TrustDatabase.create(context) }
    val journal by lazy { Journal(database.journal(), appScope) }
    val player = PlayerController(context, content, playbackHub, appScope) { code, name, chapter, verse ->
        journal.recordListening(code, name, chapter, verse)
    }
}

class TrustApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
