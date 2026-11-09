package fr.niakimbo.trust.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import fr.niakimbo.trust.BuildConfig
import fr.niakimbo.trust.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/** Tout ce que l'UI a besoin de savoir sur les mises à jour. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object NoRelease : UpdateState
    /** Build debug : les MAJ in-app ne concernent que les APK de release. */
    data object Disabled : UpdateState
    data class Available(val release: ReleaseInfo, val note: String? = null) : UpdateState
    data class NeedsPermission(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float?) : UpdateState
    data class Installing(val release: ReleaseInfo) : UpdateState
    data class Error(val message: String, val release: ReleaseInfo? = null) : UpdateState
}

/**
 * Orchestration des MAJ in-app :
 * 1. vérifier la dernière release GitHub (au lancement 1x/jour max, ou bouton manuel) ;
 * 2. si elle est plus récente, la proposer (jamais de téléchargement silencieux) ;
 * 3. télécharger + installer par-dessus via PackageInstaller.
 */
class UpdateManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val source: GitHubReleaseSource = GitHubReleaseSource(),
    private val installer: UpdateInstaller = UpdateInstaller(context),
) {
    private val _state = MutableStateFlow<UpdateState>(
        if (BuildConfig.DEBUG) UpdateState.Disabled else UpdateState.Idle,
    )
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null
    private var foregroundActivity: Activity? = null
    private var pendingConfirmation: Intent? = null

    val installedVersionName: String get() = BuildConfig.VERSION_NAME
    val installedVersionCode: Int get() = BuildConfig.VERSION_CODE

    /** Au lancement : vérifie seulement si l'option est active et si la dernière vérif date de 20 h ou plus. */
    fun checkIfDue() {
        if (BuildConfig.DEBUG || !settings.autoCheckUpdates) return
        val elapsed = System.currentTimeMillis() - settings.lastUpdateCheck
        if (elapsed in 0 until AUTO_CHECK_INTERVAL_MS) return
        check(silent = true)
    }

    /** Bouton « Vérifier maintenant ». */
    fun checkNow() {
        if (BuildConfig.DEBUG) return
        check(silent = false)
    }

    private fun check(silent: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (!silent) _state.value = UpdateState.Checking
            try {
                val release = source.fetchLatest()
                settings.lastUpdateCheck = System.currentTimeMillis()
                _state.value = when {
                    release == null -> if (silent) UpdateState.Idle else UpdateState.NoRelease
                    release.versionCode > installedVersionCode -> UpdateState.Available(release)
                    else -> if (silent) UpdateState.Idle else UpdateState.UpToDate
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Vérif silencieuse : on ne dérange pas l'utilisateur pour une erreur réseau.
                _state.value = if (silent) UpdateState.Idle else UpdateState.Error(e.toUserMessage())
            }
        }
    }

    /** Lance le téléchargement + l'installation de la release proposée. */
    fun install(release: ReleaseInfo) {
        if (job?.isActive == true) return
        if (!installer.canInstall()) {
            _state.value = UpdateState.NeedsPermission(release)
            return
        }
        job = scope.launch {
            _state.value = UpdateState.Downloading(release, null)
            try {
                installer.downloadAndCommit(release) { p ->
                    _state.value = UpdateState.Downloading(release, p)
                }
                _state.value = UpdateState.Installing(release)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Error(e.toUserMessage(), release)
            }
        }
    }

    /** Ouvre le réglage Android « Installer des applis inconnues » pour Trust. */
    fun openInstallPermissionSettings(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
        activity.startActivity(intent)
    }

    // --- Cycle de vie de l'activité ---

    fun onActivityResumed(activity: Activity) {
        foregroundActivity = activity
        // Retour depuis l'écran confirmation demandé en arrière-plan : on l'affiche maintenant.
        pendingConfirmation?.let {
            pendingConfirmation = null
            activity.startActivity(it)
        }
        // Retour depuis le réglage « applis inconnues » : on reprend l'installation automatiquement.
        val s = _state.value
        if (s is UpdateState.NeedsPermission && installer.canInstall()) install(s.release)
    }

    fun onActivityPaused(activity: Activity) {
        if (foregroundActivity === activity) foregroundActivity = null
    }

    // --- Résultats de PackageInstaller (appelés par InstallResultReceiver) ---

    fun onConfirmationRequired(context: Context, confirm: Intent) {
        val activity = foregroundActivity
        if (activity != null) {
            activity.startActivity(confirm)
        } else {
            // App en arrière-plan : Android bloquerait l'ouverture. On garde l'écran pour le retour.
            pendingConfirmation = confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun onInstalled() {
        // En pratique Android redémarre l'app avant d'arriver ici.
        _state.value = UpdateState.UpToDate
    }

    fun onInstallFailed(status: Int, message: String?) {
        val release = when (val s = _state.value) {
            is UpdateState.Installing -> s.release
            is UpdateState.Downloading -> s.release
            is UpdateState.Available -> s.release
            else -> null
        }
        _state.value = when {
            status == PackageInstaller.STATUS_FAILURE_ABORTED && release != null ->
                UpdateState.Available(release, note = "Installation annulée.")
            status == PackageInstaller.STATUS_FAILURE_CONFLICT ||
                status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.Error("APK incompatible avec la version installée (signature différente ?).", release)
            status == PackageInstaller.STATUS_FAILURE_STORAGE ->
                UpdateState.Error("Espace de stockage insuffisant.", release)
            else -> UpdateState.Error(message ?: "L'installation a échoué.", release)
        }
    }

    private fun Exception.toUserMessage(): String = when (this) {
        is UpdateException -> message ?: "Erreur de mise à jour."
        is IOException -> "Pas de connexion à Internet."
        else -> "Erreur inattendue : ${javaClass.simpleName}"
    }

    companion object {
        private const val AUTO_CHECK_INTERVAL_MS = 20L * 60 * 60 * 1000
    }
}
