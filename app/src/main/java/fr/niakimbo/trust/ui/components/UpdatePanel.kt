package fr.niakimbo.trust.ui.components

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.niakimbo.trust.update.UpdateManager
import fr.niakimbo.trust.update.UpdateState

/** La carte « Mise à jour disponible », affichée sur l'accueil. Rien si pas de MAJ. */
@Composable
fun UpdateBanner(state: UpdateState, manager: UpdateManager, modifier: Modifier = Modifier) {
    val visible = state is UpdateState.Available || state is UpdateState.NeedsPermission ||
        state is UpdateState.Downloading || state is UpdateState.Installing ||
        (state is UpdateState.Error && state.release != null)
    if (!visible) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
    ) {
        Column(Modifier.padding(18.dp)) {
            UpdateContent(state, manager, showCheckButton = false)
        }
    }
}

/** Contenu commun (accueil + réglages) : texte d'état + bouton adapté. */
@Composable
fun UpdateContent(state: UpdateState, manager: UpdateManager, showCheckButton: Boolean) {
    val activity = LocalContext.current as? Activity
    val colors = MaterialTheme.colorScheme

    when (state) {
        UpdateState.Disabled -> Hint("Build de développement : les mises à jour automatiques sont désactivées.")

        UpdateState.Idle -> if (showCheckButton) CheckButton(manager)

        UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(12.dp))
            Text("Vérification…", style = MaterialTheme.typography.bodyMedium)
        }

        UpdateState.UpToDate -> {
            Hint("Trust est à jour.")
            if (showCheckButton) { Spacer(Modifier.height(12.dp)); CheckButton(manager) }
        }

        UpdateState.NoRelease -> {
            Hint("Aucune version publiée pour l'instant.")
            if (showCheckButton) { Spacer(Modifier.height(12.dp)); CheckButton(manager) }
        }

        is UpdateState.Available -> {
            Title("Version ${state.release.versionName} disponible")
            state.note?.let { Hint(it) }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { manager.install(state.release) }) { Text("Installer la mise à jour") }
        }

        is UpdateState.NeedsPermission -> {
            Title("Une autorisation est nécessaire")
            Hint("Android doit autoriser Trust à installer ses propres mises à jour. Active « Autoriser cette source », puis reviens ici : l'installation reprendra toute seule.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { activity?.let(manager::openInstallPermissionSettings) }) { Text("Ouvrir le réglage") }
        }

        is UpdateState.Downloading -> {
            Title("Téléchargement de la version ${state.release.versionName}")
            Spacer(Modifier.height(12.dp))
            val p = state.progress
            if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        }

        is UpdateState.Installing -> {
            Title("Installation…")
            Hint("Confirme la mise à jour si Android te le demande. Trust redémarrera.")
        }

        is UpdateState.Error -> {
            Text(state.message, style = MaterialTheme.typography.bodyMedium, color = colors.error)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.release != null) {
                    Button(onClick = { manager.install(state.release) }) { Text("Réessayer") }
                } else if (showCheckButton) {
                    CheckButton(manager)
                }
            }
        }
    }
}

@Composable
private fun CheckButton(manager: UpdateManager) {
    OutlinedButton(onClick = manager::checkNow) { Text("Vérifier maintenant") }
}

@Composable
private fun Title(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)

@Composable
private fun Hint(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 4.dp),
)
