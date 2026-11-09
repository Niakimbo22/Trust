package fr.niakimbo.trust

import android.app.Application
import android.content.Context
import fr.niakimbo.trust.data.SettingsStore
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
}

class TrustApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
