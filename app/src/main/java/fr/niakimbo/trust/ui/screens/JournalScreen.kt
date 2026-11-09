package fr.niakimbo.trust.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.niakimbo.trust.AppContainer
import java.text.DateFormat
import java.util.Date

/** Carnet : marque-pages, notes et historique d'écoute. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(container: AppContainer, initialTab: Int, onBack: () -> Unit, onOpen: (code: String, chapter: Int, verse: Int) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val bookmarks by container.journal.bookmarks.collectAsStateWithLifecycle(emptyList())
    val notes by container.journal.notes.collectAsStateWithLifecycle(emptyList())
    val history by container.journal.history.collectAsStateWithLifecycle(emptyList())

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
            Text("Carnet", style = MaterialTheme.typography.titleLarge)
        }
        PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            listOf("Marque-pages", "Notes", "Historique").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        when (tab) {
            0 -> EntryList(
                empty = "Aucun marque-page. Appui long sur un verset pour en ajouter.",
                entries = bookmarks.map { Entry("${it.bookName} ${it.chapter}, ${it.verse}", null, it.createdAt) { onOpen(it.code, it.chapter, it.verse) } },
                onDelete = { i -> container.journal.removeBookmark(bookmarks[i]) },
            )
            1 -> EntryList(
                empty = "Aucune note. Appui long sur un verset pour écrire.",
                entries = notes.map { Entry("${it.bookName} ${it.chapter}, ${it.verse}", it.text, it.updatedAt) { onOpen(it.code, it.chapter, it.verse) } },
                onDelete = { i -> notes[i].let { n -> container.journal.saveNote(n.code, n.bookName, n.chapter, n.verse, "") } },
            )
            else -> Column {
                EntryList(
                    empty = "Les chapitres écoutés apparaîtront ici.",
                    entries = history.map {
                        Entry("${it.bookName} ${it.chapter}", "Arrêt au verset ${it.lastVerse.coerceAtLeast(1)}", it.updatedAt) {
                            onOpen(it.code, it.chapter, it.lastVerse)
                        }
                    },
                    onDelete = null,
                    footer = if (history.isNotEmpty()) {
                        { TextButton(onClick = { container.journal.clearHistory() }) { Text("Effacer l'historique") } }
                    } else null,
                )
            }
        }
    }
}

private class Entry(val title: String, val body: String?, val time: Long, val onClick: () -> Unit)

@Composable
private fun EntryList(
    empty: String,
    entries: List<Entry>,
    onDelete: ((Int) -> Unit)?,
    footer: (@Composable () -> Unit)? = null,
) {
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        return
    }
    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(entries.size) { i ->
            val e = entries[i]
            Row(
                Modifier.fillMaxWidth().clickable(onClick = e.onClick).padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(e.title, style = MaterialTheme.typography.titleMedium)
                    e.body?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Text(fmt.format(Date(e.time)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onDelete != null) {
                    IconButton(onClick = { onDelete(i) }) {
                        Icon(Icons.Outlined.Delete, "Supprimer", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        footer?.let { f -> item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { f() } } }
    }
}
