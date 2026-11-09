package fr.niakimbo.trust.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import fr.niakimbo.trust.AppContainer
import fr.niakimbo.trust.content.BibleSearch
import fr.niakimbo.trust.content.SearchHit
import fr.niakimbo.trust.content.SearchIndex
import fr.niakimbo.trust.ui.theme.ScriptureFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Recherche dans toute la Bible (sans accents ni majuscules ; tous les mots doivent y être). */
@Composable
fun SearchScreen(container: AppContainer, onBack: () -> Unit, onOpen: (String, Int, Int) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var index by remember { mutableStateOf<SearchIndex?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<Pair<List<SearchHit>, Int>?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        runCatching {
            val texts = container.content.allTexts { d, t -> progress = d / t.toFloat() }
            index = withContext(Dispatchers.Default) { SearchIndex(texts) }
        }.onFailure { error = "Connexion nécessaire la première fois, pour récupérer le texte de la Bible." }
    }
    LaunchedEffect(query, index) {
        val idx = index ?: return@LaunchedEffect
        delay(250) // on attend que la frappe se calme
        results = if (query.isBlank()) null else withContext(Dispatchers.Default) { idx.search(query) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Rechercher un mot, une phrase…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        when {
            error != null -> Message(error!!)
            index == null -> Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text("Préparation de la recherche…", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            results == null -> Message("Tape au moins un mot. Les accents et majuscules ne comptent pas.")
            results!!.first.isEmpty() -> Message("Aucun verset trouvé.")
            else -> {
                val (hits, total) = results!!
                val words = BibleSearch.words(query)
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Text(
                            if (total > hits.size) "$total versets · ${hits.size} premiers affichés" else "$total verset${if (total > 1) "s" else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    items(hits, key = { "${it.code}${it.chapter}:${it.verse}" }) { h ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onOpen(h.code, h.chapter, h.verse) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            Text("${h.book} ${h.chapter}, ${h.verse}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(
                                highlight(h.text, words),
                                fontFamily = ScriptureFamily,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) = Box(Modifier.fillMaxWidth().padding(24.dp)) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Met en gras les mots cherchés (comparaison sans accents, caractère par caractère). */
@Composable
private fun highlight(text: String, words: List<String>) = buildAnnotatedString {
    // NFD peut changer la longueur : on normalise lettre par lettre pour garder les positions.
    val norm = text.map { BibleSearch.normalize(it.toString()).firstOrNull() ?: it }.joinToString("")
    val marks = BooleanArray(text.length)
    for (w in words) {
        var i = norm.indexOf(w)
        while (i >= 0) {
            for (k in i until minOf(i + w.length, text.length)) marks[k] = true
            i = norm.indexOf(w, i + w.length)
        }
    }
    val color = MaterialTheme.colorScheme.primary
    text.forEachIndexed { i, ch ->
        if (marks[i]) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color)) { append(ch) } else append(ch)
    }
}
