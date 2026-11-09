package fr.niakimbo.trust.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.niakimbo.trust.AppContainer
import fr.niakimbo.trust.playback.PlayerUiState
import fr.niakimbo.trust.playback.SleepTimer
import fr.niakimbo.trust.ui.components.TrustCard
import fr.niakimbo.trust.ui.theme.ScriptureFamily
import kotlinx.coroutines.delay

private val SPEEDS = listOf(0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)
private val SLEEP_MINUTES = listOf(10, 15, 30, 45, 60)

fun formatTime(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0).toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "${speed.toString().trimEnd('0')}×".replace('.', ',')

@Composable
fun PlayerScreen(container: AppContainer, onOpenText: (String, Int) -> Unit, onOpenBible: () -> Unit) {
    val state by container.player.state.collectAsStateWithLifecycle()
    val resume by container.player.resume.collectAsStateWithLifecycle()
    val book = state.book

    if (book == null || state.clock == null) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Rien en écoute", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Choisis un chapitre dans la Bible, ou reprends ta dernière écoute.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            resume?.let { r ->
                FilledTonalButton(onClick = { container.player.resumeLast() }) {
                    Text("Reprendre · ${r.name} ${r.chapter}, ${r.verse.coerceAtLeast(1)}")
                }
                Spacer(Modifier.height(8.dp))
            }
            TextButton(onClick = onOpenBible) { Text("Ouvrir la Bible") }
            state.error?.let { ErrorText(it) }
        }
        return
    }

    val clock = state.clock!!
    val ref = state.ref
    val chapter = ref?.chapter?.coerceAtLeast(1) ?: 1
    val verse = ref?.verse ?: 0
    val verseText = book.chapter(chapter)?.verses?.firstOrNull { it.v == verse }?.t

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        Text(book.info.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("${book.info.short} $chapter", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 4.dp))
        Text(
            if (verse > 0) "Verset $verse" else "Chapitre $chapter",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Verset en cours
        Box(Modifier.fillMaxWidth().height(220.dp).padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = verseText ?: "",
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "verse",
            ) { text ->
                Text(
                    text,
                    fontFamily = ScriptureFamily,
                    fontSize = 21.sp,
                    lineHeight = 32.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }

        ChapterProgress(state, chapter) { container.player.seekGlobal(it) }

        // Commandes principales
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { container.player.previousVerse() }) {
                Icon(Icons.Filled.SkipPrevious, "Verset précédent", Modifier.size(30.dp))
            }
            IconButton(onClick = { container.player.skip(-10.0) }) {
                Icon(Icons.Filled.Replay10, "Reculer de 10 secondes", Modifier.size(28.dp))
            }
            FilledIconButton(
                onClick = { container.player.togglePlay() },
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                if (state.buffering && !state.isPlaying) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (state.isPlaying) "Pause" else "Lecture",
                        Modifier.size(36.dp),
                    )
                }
            }
            IconButton(onClick = { container.player.skip(10.0) }) {
                Icon(Icons.Filled.Forward10, "Avancer de 10 secondes", Modifier.size(28.dp))
            }
            IconButton(onClick = { container.player.nextVerse() }) {
                Icon(Icons.Filled.SkipNext, "Verset suivant", Modifier.size(30.dp))
            }
        }

        // Chapitres
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { container.player.previousChapter() }) { Text("‹ Chapitre précédent") }
            TextButton(onClick = { container.player.nextChapter() }, enabled = clock.chapters.lastOrNull() != chapter) {
                Text("Chapitre suivant ›")
            }
        }

        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeedChip(state.speed) { container.player.setSpeed(it) }
            SleepChip(
                state.sleep,
                onMinutes = { container.player.setSleepMinutes(it) },
                onEndOfChapter = { container.player.setSleepEndOfChapter() },
            )
            // Marque-page sur le verset en cours
            val marks by remember(book.code, chapter) { container.journal.bookmarksIn(book.code, chapter) }
                .collectAsStateWithLifecycle(emptyList())
            val marked = verse > 0 && marks.any { it.verse == verse }
            FilterChip(
                selected = marked,
                enabled = verse > 0,
                onClick = { container.journal.toggleBookmark(book.code, book.info.short, chapter, verse) },
                label = { Text(if (marked) "Marqué" else "Marquer") },
                leadingIcon = {
                    Icon(if (marked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, null, Modifier.size(18.dp))
                },
            )
        }

        Spacer(Modifier.height(16.dp))
        TrustCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Suivre le texte", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Le verset lu est surligné, touche un verset pour y aller.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onOpenText(book.code, chapter) }) { Text("Ouvrir") }
            }
        }
        state.error?.let { ErrorText(it) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ErrorText(message: String) = Text(
    message,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.error,
    textAlign = TextAlign.Center,
    modifier = Modifier.padding(top = 16.dp),
)

@Composable
private fun ChapterProgress(state: PlayerUiState, chapter: Int, onSeek: (Double) -> Unit) {
    val clock = state.clock ?: return
    val start = clock.chapterStart(chapter) ?: 0.0
    val end = clock.chapterEnd(chapter).coerceAtLeast(start + 1)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val value = if (dragging) dragValue else ((state.position - start) / (end - start)).toFloat().coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = value,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                onSeek(start + dragValue * (end - start))
                dragging = false
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                formatTime(value * (end - start)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                "-" + formatTime((1 - value) * (end - start)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SpeedChip(speed: Float, onSpeed: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text("Vitesse ${formatSpeed(speed)}") },
            leadingIcon = { Icon(Icons.Filled.Speed, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SPEEDS.forEach { s ->
                DropdownMenuItem(
                    text = { Text(formatSpeed(s) + if (s == speed) "  ✓" else "") },
                    onClick = { onSpeed(s); open = false },
                )
            }
        }
    }
}

@Composable
private fun SleepChip(sleep: SleepTimer?, onMinutes: (Int?) -> Unit, onEndOfChapter: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleep) {
        while (sleep is SleepTimer.At) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val label = when (sleep) {
        null -> "Minuteur"
        is SleepTimer.EndOfChapter -> "Fin du chapitre"
        is SleepTimer.At -> formatTime((sleep.endAtMillis - now) / 1000.0)
    }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(label) },
            leadingIcon = { Icon(Icons.Filled.Bedtime, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SLEEP_MINUTES.forEach { m ->
                DropdownMenuItem(text = { Text("$m min") }, onClick = { onMinutes(m); open = false })
            }
            DropdownMenuItem(text = { Text("Fin du chapitre") }, onClick = { onEndOfChapter(); open = false })
            if (sleep != null) {
                DropdownMenuItem(text = { Text("Désactiver") }, onClick = { onMinutes(null); open = false })
            }
        }
    }
}
