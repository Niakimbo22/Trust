package fr.niakimbo.trust.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.niakimbo.trust.AppContainer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.produceState
import fr.niakimbo.trust.content.BooksIndex
import fr.niakimbo.trust.data.ThemeMode
import fr.niakimbo.trust.ui.components.ScreenTitle
import fr.niakimbo.trust.ui.components.SectionLabel
import fr.niakimbo.trust.ui.components.TrustCard
import fr.niakimbo.trust.ui.components.UpdateBanner
import fr.niakimbo.trust.ui.components.UpdateContent
import fr.niakimbo.trust.ui.theme.ScriptureFamily
import java.time.LocalTime

private val screenPadding = Modifier.padding(horizontal = 20.dp)

// ---------------- Accueil ----------------

@Composable
fun HomeScreen(container: AppContainer, onResume: () -> Unit, onOpenBible: () -> Unit) {
    val update by container.updateManager.state.collectAsStateWithLifecycle()
    val resume by container.player.resume.collectAsStateWithLifecycle()
    val index by produceState<BooksIndex?>(null) { value = runCatching { container.content.index() }.getOrNull() }
    val greeting = if (LocalTime.now().hour in 5..17) "Bonjour" else "Bonsoir"

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.padding(top = 24.dp)) {
            Text(greeting, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Trust", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
        }

        UpdateBanner(update, container.updateManager)

        TrustCard {
            SectionLabel("Reprendre")
            val r = resume
            if (r == null) {
                Text("Aucune écoute pour l'instant", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Choisis un livre : ta dernière écoute apparaîtra ici, prête à reprendre au verset près.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(onClick = onOpenBible, modifier = Modifier.padding(top = 4.dp)) { Text("Ouvrir la Bible") }
            } else {
                val name = index?.books?.firstOrNull { it.code == r.code }?.short ?: r.code
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("$name ${r.chapter}", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (r.verse > 0) "Verset ${r.verse}" else "Début du chapitre",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledIconButton(onClick = onResume, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Reprendre")
                    }
                }
            }
        }

        Column(Modifier.padding(vertical = 24.dp)) {
            Text(
                "« Heureux ceux qui n'ont pas vu et qui ont cru ! »",
                fontFamily = ScriptureFamily,
                fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            )
            Text(
                "Jean 20, 29",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// ---------------- Réglages ----------------

@Composable
fun SettingsScreen(container: AppContainer, onOpenAbout: () -> Unit) {
    val settings = container.settings
    val manager = container.updateManager
    val theme by settings.themeMode.collectAsStateWithLifecycle()
    val autoCheck by settings.autoCheckFlow.collectAsStateWithLifecycle()
    val update by manager.state.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Réglages", Modifier.padding(bottom = 0.dp))

        TrustCard {
            SectionLabel("Apparence")
            val options = listOf(ThemeMode.SYSTEM to "Système", ThemeMode.DARK to "Sombre", ThemeMode.LIGHT to "Clair")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = theme == mode,
                        onClick = { settings.setThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(label) }
                }
            }
        }

        TrustCard {
            SectionLabel("Mises à jour")
            Text(
                "Version ${manager.installedVersionName}",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Vérifier au démarrage", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Au plus une fois par jour",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoCheck, onCheckedChange = { settings.autoCheckUpdates = it })
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
            UpdateContent(update, manager, showCheckButton = true)
        }

        TrustCard {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenAbout).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("À propos et crédits", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------- À propos ----------------

@Composable
fun AboutScreen(container: AppContainer, onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
        }
        Column(screenPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                Text("Trust", style = MaterialTheme.typography.displaySmall)
                Text(
                    "Version ${container.updateManager.installedVersionName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TrustCard {
                SectionLabel("Texte")
                Text("La Sainte Bible, traduction du chanoine Augustin Crampon, édition de 1923.", style = MaterialTheme.typography.bodyLarge)
                Credit("Œuvre du domaine public.")
                Link("Source : Wikisource") { uri.openUri("https://fr.wikisource.org/wiki/Auteur:Augustin_Crampon") }
            }

            TrustCard {
                SectionLabel("Audio")
                Text("Lecture de la Bible Crampon enregistrée par Mission Web.", style = MaterialTheme.typography.bodyLarge)
                Credit("Enregistrements diffusés sans aucune modification, sous licence Creative Commons BY-NC-ND (attribution, pas d'utilisation commerciale, pas de modification). Hébergés par Internet Archive.")
                Link("mission-web.com") { uri.openUri("http://www.mission-web.com") }
                Link("Licence CC BY-NC-ND 2.0 France") { uri.openUri("https://creativecommons.org/licenses/by-nc-nd/2.0/fr/") }
            }

            TrustCard {
                SectionLabel("Application")
                Text("Polices Literata et Inter, sous licence SIL Open Font License.", style = MaterialTheme.typography.bodyLarge)
                Credit("Trust est gratuit, sans publicité ni pistage.")
                Link("Code source sur GitHub") { uri.openUri("https://github.com/Niakimbo22/trust") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Credit(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 6.dp),
)

@Composable
private fun Link(text: String, onClick: () -> Unit) = Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 10.dp).clickable(onClick = onClick),
)
