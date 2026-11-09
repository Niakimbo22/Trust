package fr.niakimbo.trust.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import fr.niakimbo.trust.TrustApp

/** Reçoit le résultat de PackageInstaller et le transmet à UpdateManager. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = (context.applicationContext as TrustApp).container.updateManager
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android demande une confirmation à l'utilisateur (écran système « Mettre à jour ? »).
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) manager.onConfirmationRequired(context, confirm)
                else manager.onInstallFailed(status, "Confirmation impossible.")
            }
            PackageInstaller.STATUS_SUCCESS -> manager.onInstalled()
            else -> manager.onInstallFailed(
                status,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            )
        }
    }
}
