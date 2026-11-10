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
import fr.niakimbo.trust.data.ThemeMode
import fr.niakimbo.trust.ui.components.ScreenTitle
import fr.niakimbo.trust.ui.components.SectionLabel
import fr.niakimbo.trust.ui.components.TrustCard
import fr.niakimbo.trust.ui.components.UpdateBanner
import fr.niakimbo.trust.ui.components.UpdateContent
import fr.niakimbo.trust.ui.theme.ScriptureFamily
import java.time.LocalTime
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import fr.niakimbo.trust.data.JournalBackupCodec
import fr.niakimbo.trust.data.plural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight

private val screenPadding = Modifier.padding(horizontal = 20.dp)

// ---------------- Accueil ----------------

@Composable
fun HomeScreen(
    container: AppContainer,
    onResume: () -> Unit,
    onPlay: (code: String, chapter: Int, verse: Int) -> Unit,
    onOpenBible: () -> Unit,
    onOpenJournal: (Int) -> Unit,
) {
    val bookmarks by container.journal.bookmarks.collectAsStateWithLifecycle(emptyList())
    val notes by container.journal.notes.collectAsStateWithLifecycle(emptyList())
    val history by container.journal.history.collectAsStateWithLifecycle(emptyList())
    val update by container.updateManager.state.collectAsStateWithLifecycle()
    val resume by container.player.resume.collectAsStateWithLifecycle()
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
                val name = r.name
                Row(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Reprendre l'écoute", onClick = onResume),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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

        // Écoutes récentes : les autres chapitres écoutés, à reprendre là où on les a laissés.
        val recent = history.filter { h -> resume.let { it == null || it.code != h.code || it.chapter != h.chapter } }.take(RECENT_COUNT)
        if (recent.isNotEmpty()) {
            TrustCard {
                SectionLabel("Écoutes récentes")
                recent.forEach { h ->
                    val verse = h.lastVerse.coerceAtLeast(1)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClickLabel = "Reprendre l'écoute") { onPlay(h.code, h.chapter, verse) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${h.bookName} ${h.chapter}", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Verset $verse · " + DateUtils.getRelativeTimeSpanString(h.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (history.size > recent.size + (if (resume != null) 1 else 0)) {
                    TextButton(onClick = { onOpenJournal(2) }) { Text("Tout l'historique") }
                }
            }
        }

        TrustCard {
            SectionLabel("Carnet")
            JournalRow("Marque-pages", bookmarks.size) { onOpenJournal(0) }
            JournalRow("Notes", notes.size) { onOpenJournal(1) }
            JournalRow("Historique", history.size) { onOpenJournal(2) }
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

private const val RECENT_COUNT = 4

@Composable
private fun JournalRow(label: String, count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
            SectionLabel("Lecture")
            val scale by settings.textScale.collectAsStateWithLifecycle()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Taille du texte", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { settings.setTextScale(scale - 0.1f) },
                    enabled = scale > 0.86f,
                    modifier = Modifier.semantics { contentDescription = "Texte plus petit" },
                ) { Text("A−") }
                Text("${(scale * 100).roundToInt()} %", style = MaterialTheme.typography.bodyMedium)
                TextButton(
                    onClick = { settings.setTextScale(scale + 0.1f) },
                    enabled = scale < 1.39f,
                    modifier = Modifier.semantics { contentDescription = "Texte plus grand" },
                ) { Text("A+") }
            }
            Text(
                "Au commencement était le Verbe, et le Verbe était en Dieu.",
                fontFamily = ScriptureFamily,
                fontSize = 19.sp * scale,
                lineHeight = 30.sp * scale,
                modifier = Modifier.padding(top = 6.dp),
            )
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

        JournalBackupCard(container)

        TrustCard {
            SectionLabel("Écoute hors-ligne")
            val bytes by container.downloads.totalBytes.collectAsStateWithLifecycle()
            val states by container.downloads.states.collectAsStateWithLifecycle()
            val done = states.values.count { it == fr.niakimbo.trust.playback.BookDownload.Done }
            Text(
                if (done == 0) "Aucun livre téléchargé" else "$done livre${if (done > 1) "s" else ""} · ${"%.1f".format(bytes / 1e9)} Go",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Télécharge un livre depuis sa page (liste des chapitres). L'audio écouté en streaming est aussi gardé en cache (1 Go max).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (states.isNotEmpty()) {
                TextButton(onClick = { container.downloads.removeAll() }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Tout supprimer")
                }
            }
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

/** Sauvegarde et restauration du Carnet dans un fichier choisi par l'utilisateur (aucun serveur). */
@Composable
private fun JournalBackupCard(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            message = runCatching {
                val backup = container.journal.exportBackup()
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("fichier inaccessible")
                    out.use { it.write(JournalBackupCodec.encode(backup).toByteArray()) }
                }
                backup
            }.fold(
                onSuccess = {
                    "Carnet enregistré : ${plural(it.bookmarks.size, "marque-page", "marque-pages")}, " +
                        "${plural(it.notes.size, "note", "notes")}, ${plural(it.history.size, "écoute", "écoutes")}."
                },
                onFailure = { "Impossible d'enregistrer le fichier." },
            )
            busy = false
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            message = runCatching {
                val text = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: error("fichier inaccessible")
                    input.use {
                        val bytes = it.readNBytesCompat(JournalBackupCodec.MAX_BYTES + 1)
                        require(bytes.size <= JournalBackupCodec.MAX_BYTES) { "Fichier trop gros pour être un Carnet." }
                        String(bytes)
                    }
                }
                container.journal.importBackup(JournalBackupCodec.decode(text)).summary()
            }.getOrElse { (it as? IllegalArgumentException)?.message ?: "Impossible de lire ce fichier." }
            busy = false
        }
    }

    TrustCard {
        SectionLabel("Sauvegarde du Carnet")
        Text(
            "Enregistre tes marque-pages, notes et historique dans un fichier, pour les garder ou les retrouver sur un autre téléphone. L'import complète le Carnet sans rien effacer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.padding(top = 4.dp)) {
            TextButton(onClick = { exportLauncher.launch("trust-carnet-${LocalDate.now()}.json") }, enabled = !busy) {
                Text("Exporter")
            }
            TextButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                enabled = !busy,
            ) { Text("Importer") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** Lit au plus [limit] octets (InputStream.readNBytes n'existe qu'à partir d'Android 13). */
private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (total < limit) {
        val n = read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
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
    // zone tactile d'au moins 48 dp (le texte seul ne fait qu'une vingtaine)
    modifier = Modifier.padding(top = 2.dp).clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically),
)
