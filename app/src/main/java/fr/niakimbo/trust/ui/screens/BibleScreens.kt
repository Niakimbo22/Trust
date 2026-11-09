package fr.niakimbo.trust.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.niakimbo.trust.AppContainer
import fr.niakimbo.trust.content.Book
import fr.niakimbo.trust.content.BookInfo
import fr.niakimbo.trust.content.BooksIndex
import fr.niakimbo.trust.content.Verse
import fr.niakimbo.trust.ui.components.SectionLabel
import fr.niakimbo.trust.ui.theme.ScriptureFamily
import kotlinx.coroutines.launch

/** Résultat d'un chargement réseau/cache, pour les écrans. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
    data class Failed(val message: String) : Loadable<Nothing>
}

@Composable
private fun <T> rememberLoad(key: Any?, load: suspend () -> T): Pair<Loadable<T>, () -> Unit> {
    var attempt by remember { mutableStateOf(0) }
    val state by produceState<Loadable<T>>(Loadable.Loading, key, attempt) {
        value = Loadable.Loading
        value = runCatching { Loadable.Ready(load()) }
            .getOrElse { Loadable.Failed(it.message ?: "Erreur de chargement") }
    }
    return state to { attempt++ }
}

@Composable
private fun <T> LoadableContent(state: Loadable<T>, retry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        Loadable.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
        is Loadable.Failed -> Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(state.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = retry) { Text("Réessayer") }
        }
        is Loadable.Ready -> content(state.value)
    }
}

@Composable
private fun BackHeader(title: String, subtitle: String? = null, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        actions()
    }
}

// ---------------- Liste des livres ----------------

@Composable
fun BooksScreen(container: AppContainer, onOpenBook: (String) -> Unit) {
    val (state, retry) = rememberLoad(Unit) { container.content.index() }
    LoadableContent(state, retry) { index: BooksIndex ->
        val groups = index.books.groupBy { it.testament }
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item {
                Text(
                    "Bible",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                Text(
                    "Traduction Crampon · ${index.books.count { it.hasAudio }} livres audio sur ${index.books.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            listOf("AT" to "Ancien Testament", "NT" to "Nouveau Testament").forEach { (key, label) ->
                val books = groups[key].orEmpty()
                if (books.isNotEmpty()) {
                    item { SectionLabel(label, Modifier.padding(top = 12.dp)) }
                    items(books, key = { it.code }) { book -> BookRow(book) { onOpenBook(book.code) } }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun BookRow(book: BookInfo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(book.short, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            "${book.chapters} ch.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Icon(
            Icons.Filled.Headphones,
            contentDescription = if (book.hasAudio) "Audio disponible" else "Audio bientôt",
            modifier = Modifier.size(16.dp),
            tint = if (book.hasAudio) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

// ---------------- Chapitres d'un livre ----------------

@Composable
fun ChaptersScreen(container: AppContainer, code: String, onBack: () -> Unit, onOpenChapter: (Int) -> Unit) {
    val (state, retry) = rememberLoad(code) { container.content.book(code) }
    Column(Modifier.fillMaxSize()) {
        val title = (state as? Loadable.Ready)?.value?.info?.name ?: ""
        BackHeader(title, onBack = onBack)
        LoadableContent(state, retry) { book: Book ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(64.dp),
                contentPadding = PaddingValues(20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(book.text.chapters.map { it.c }) { c ->
                    Surface(
                        onClick = { onOpenChapter(c) },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.aspectRatio(1f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("$c", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Lecture d'un chapitre (texte synchronisé) ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    container: AppContainer,
    code: String,
    chapter: Int,
    onBack: () -> Unit,
    onOpenChapter: (Int) -> Unit,
) {
    val (state, retry) = rememberLoad(code) { container.content.book(code) }
    val player by container.player.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var notesFor by remember { mutableStateOf<Verse?>(null) }

    Column(Modifier.fillMaxSize()) {
        val book = (state as? Loadable.Ready)?.value
        val playingHere = player.book?.code == code && player.ref?.chapter == chapter
        BackHeader(
            title = book?.let { "${it.info.short} $chapter" } ?: "",
            subtitle = book?.info?.name,
            onBack = onBack,
        ) {
            if (book?.timings != null && !(playingHere && player.isPlaying)) {
                IconButton(onClick = { container.player.play(code, chapter, 0) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Écouter ce chapitre", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        LoadableContent(state, retry) { b: Book ->
            val ch = b.chapter(chapter)
            if (ch == null) {
                Text("Chapitre introuvable.", Modifier.padding(20.dp))
                return@LoadableContent
            }
            val listState = rememberLazyListState()
            val current = if (playingHere) player.ref?.verse else null

            // Suit le verset lu (on fait défiler seulement si le verset sort de l'écran).
            LaunchedEffect(current, playingHere) {
                val v = current ?: return@LaunchedEffect
                if (v <= 0) return@LaunchedEffect
                val idx = v // l'élément 0 est l'en-tête : le verset v est à l'index v
                val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
                if (idx !in visible.dropLast(1).drop(1)) {
                    scope.launch { listState.animateScrollToItem(idx, -200) }
                }
            }

            LazyColumn(state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                item {
                    if (b.timings == null) {
                        Text(
                            "Audio bientôt disponible pour ce livre.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        Spacer(Modifier.height(8.dp))
                    }
                }
                itemsIndexed(ch.verses, key = { _, v -> v.v }) { _, verse ->
                    VerseItem(
                        verse = verse,
                        highlighted = verse.v == current,
                        onClick = { if (b.timings != null) container.player.play(code, chapter, verse.v) },
                        onLongClick = { if (verse.notes.isNotEmpty()) notesFor = verse },
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        val chapters = b.text.chapters.map { it.c }
                        val i = chapters.indexOf(chapter)
                        val prev = chapters.getOrNull(i - 1)
                        val next = chapters.getOrNull(i + 1)
                        TextButton(onClick = { prev?.let(onOpenChapter) }, enabled = prev != null) {
                            Icon(Icons.Filled.ChevronLeft, null); Text("Chapitre ${prev ?: ""}")
                        }
                        TextButton(onClick = { next?.let(onOpenChapter) }, enabled = next != null) {
                            Text("Chapitre ${next ?: ""}"); Icon(Icons.Filled.ChevronRight, null)
                        }
                    }
                }
            }
        }
    }

    notesFor?.let { v ->
        AlertDialog(
            onDismissRequest = { notesFor = null },
            confirmButton = { TextButton(onClick = { notesFor = null }) { Text("Fermer") } },
            title = { Text("Notes · verset ${v.v}") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(v.notes) { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VerseItem(verse: Verse, highlighted: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        verse.h?.let { heading ->
            Text(
                heading,
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
            )
        }
        val text = buildAnnotatedString {
            withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)) {
                append("${verse.v}  ")
            }
            append(verse.t)
            if (verse.notes.isNotEmpty()) {
                withStyle(SpanStyle(color = colors.onSurfaceVariant, fontSize = 12.sp)) { append("  †") }
            }
        }
        Text(
            text,
            fontFamily = ScriptureFamily,
            fontSize = 19.sp,
            lineHeight = 30.sp,
            color = if (highlighted) colors.onBackground else colors.onBackground.copy(alpha = 0.92f),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (highlighted) colors.primaryContainer.copy(alpha = 0.55f) else colors.background)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}
