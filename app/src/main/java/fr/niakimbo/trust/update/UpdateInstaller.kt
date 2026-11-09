package fr.niakimbo.trust.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Installe un APK par-dessus l'app avec PackageInstaller (l'API officielle d'Android).
 * L'APK est écrit directement dans la session d'installation pendant le téléchargement :
 * aucun fichier temporaire à nettoyer, aucun FileProvider nécessaire.
 * Le résultat arrive ensuite dans InstallResultReceiver.
 */
class UpdateInstaller(private val context: Context) {

    /** Android exige l'autorisation « Installer des applis inconnues » pour Trust. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    suspend fun downloadAndCommit(release: ReleaseInfo, onProgress: (Float?) -> Unit) =
        withContext(Dispatchers.IO) {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (release.apkSize > 0) setSize(release.apkSize)
                if (Build.VERSION.SDK_INT >= 31) {
                    // Si Trust s'est déjà installé lui-même, Android peut éviter la confirmation.
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
                }
            }
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                download(release, session, onProgress)
                session.commit(resultIntent(sessionId).intentSender)
            } catch (t: Throwable) {
                session.abandon()
                throw t
            } finally {
                session.close()
            }
        }

    private suspend fun download(
        release: ReleaseInfo,
        session: PackageInstaller.Session,
        onProgress: (Float?) -> Unit,
    ) {
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", GitHubReleaseSource.USER_AGENT)
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw UpdateException("Téléchargement impossible (${conn.responseCode}).")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize.takeIf { it > 0 }
            onProgress(if (total != null) 0f else null)
            conn.inputStream.use { input ->
                session.openWrite("trust.apk", 0, total ?: -1).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastReported = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        if (total != null) {
                            val pct = (written * 100 / total).toInt()
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(written.toFloat() / total)
                            }
                        }
                    }
                    session.fsync(out)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun resultIntent(sessionId: Int): PendingIntent {
        val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
        // MUTABLE obligatoire : le système ajoute le statut de l'installation dans l'Intent.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }
}
