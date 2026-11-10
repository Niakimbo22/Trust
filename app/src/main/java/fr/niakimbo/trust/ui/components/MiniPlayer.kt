package fr.niakimbo.trust.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.niakimbo.trust.playback.PlayerUiState

/** Barre de lecture compacte, au-dessus de la navigation. */
@Composable
fun MiniPlayer(state: PlayerUiState, onOpen: () -> Unit, onToggle: () -> Unit, onNextVerse: () -> Unit) {
    val book = state.book ?: return
    val clock = state.clock ?: return
    val ref = state.ref
    val chapter = ref?.chapter?.coerceAtLeast(1) ?: 1
    val section = clock.sectionAt(state.position)
    val start = section.start
    val end = section.end.coerceAtLeast(start + 1)
    val progress = ((state.position - start) / (end - start)).toFloat().coerceIn(0f, 1f)

    Surface(onClick = onOpen, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                drawStopIndicator = {},
            )
            Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${book.info.short} $chapter", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if ((ref?.verse ?: 0) > 0) "Verset ${ref!!.verse}" else "Annonce du chapitre",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggle) {
                    if (state.buffering && !state.isPlaying) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (state.isPlaying) "Pause" else "Lecture",
                        )
                    }
                }
                IconButton(onClick = onNextVerse) { Icon(Icons.Filled.SkipNext, "Verset suivant") }
            }
        }
    }
}
