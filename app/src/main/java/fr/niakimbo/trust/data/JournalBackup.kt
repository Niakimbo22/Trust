package fr.niakimbo.trust.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Sauvegarde du Carnet (marque-pages, notes, historique) dans un fichier JSON choisi par l'utilisateur.
 * Aucun serveur : le fichier reste où il le range (téléphone, carte SD, Drive…).
 */
@Serializable
data class JournalBackup(
    val app: String = APP,
    val format: Int = FORMAT,
    val exportedAt: Long = 0,
    val bookmarks: List<BookmarkItem> = emptyList(),
    val notes: List<NoteItem> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
) {
    companion object {
        const val APP = "trust-carnet"
        const val FORMAT = 1
    }
}

@Serializable
data class BookmarkItem(
    val code: String,
    val bookName: String,
    val chapter: Int,
    val verse: Int,
    val createdAt: Long = 0,
)

@Serializable
data class NoteItem(
    val code: String,
    val bookName: String,
    val chapter: Int,
    val verse: Int,
    val text: String,
    val updatedAt: Long = 0,
)

@Serializable
data class HistoryItem(
    val code: String,
    val bookName: String,
    val chapter: Int,
    @SerialName("verse") val lastVerse: Int,
    val updatedAt: Long = 0,
)

/** Ce qu'un import a changé, pour le dire à l'utilisateur. */
data class ImportResult(
    val merged: JournalBackup,
    val addedBookmarks: Int,
    val addedNotes: Int,
    val updatedNotes: Int,
    val addedHistory: Int,
) {
    val changed: Boolean get() = addedBookmarks + addedNotes + updatedNotes + addedHistory > 0

    fun summary(): String = if (!changed) {
        "Rien de nouveau : ce Carnet est déjà à jour."
    } else {
        buildList {
            if (addedBookmarks > 0) add(plural(addedBookmarks, "marque-page ajouté", "marque-pages ajoutés"))
            if (addedNotes > 0) add(plural(addedNotes, "note ajoutée", "notes ajoutées"))
            if (updatedNotes > 0) add(plural(updatedNotes, "note mise à jour", "notes mises à jour"))
            if (addedHistory > 0) add(plural(addedHistory, "écoute ajoutée", "écoutes ajoutées") + " à l'historique")
        }.joinToString(", ", prefix = "Carnet importé : ", postfix = ".")
    }
}

internal fun plural(n: Int, one: String, many: String) = "$n ${if (n > 1) many else one}"

object JournalBackupCodec {
    /** Au-delà, ce n'est sûrement pas un Carnet (et on évite de tout charger en mémoire). */
    const val MAX_BYTES = 20L * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(backup: JournalBackup): String = json.encodeToString(JournalBackup.serializer(), backup)

    /** Lit un fichier de sauvegarde ; les entrées incohérentes sont ignorées. Erreur lisible sinon. */
    fun decode(text: String): JournalBackup {
        val notOurs = IllegalArgumentException("Ce fichier n'est pas une sauvegarde du Carnet de Trust.")
        // « app » doit être écrit dans le fichier : un autre JSON ne doit pas passer pour un Carnet vide
        val app = runCatching { json.parseToJsonElement(text).jsonObject["app"]?.jsonPrimitive?.content }.getOrNull()
        if (app != JournalBackup.APP) throw notOurs
        val b = runCatching { json.decodeFromString(JournalBackup.serializer(), text) }.getOrElse { throw notOurs }
        if (b.format > JournalBackup.FORMAT) {
            throw IllegalArgumentException("Cette sauvegarde vient d'une version plus récente de Trust : mets l'app à jour.")
        }
        return b.copy(
            bookmarks = b.bookmarks.filter { it.code.isNotBlank() && it.chapter > 0 && it.verse >= 0 },
            notes = b.notes.filter { it.code.isNotBlank() && it.chapter > 0 && it.verse >= 0 && it.text.isNotBlank() }
                .map { it.copy(text = it.text.trim()) },
            history = b.history.filter { it.code.isNotBlank() && it.chapter > 0 }
                .map { it.copy(lastVerse = it.lastVerse.coerceAtLeast(0)) },
        )
    }

    /**
     * Fusion d'un Carnet importé avec celui du téléphone : rien n'est jamais perdu.
     * - marque-pages : union (celui du téléphone garde sa date) ;
     * - notes : pour un même verset, la plus récente gagne ;
     * - historique : pour un même chapitre, l'écoute la plus récente gagne.
     */
    fun merge(current: JournalBackup, incoming: JournalBackup): ImportResult {
        val bookmarks = LinkedHashMap<Triple<String, Int, Int>, BookmarkItem>()
        current.bookmarks.forEach { bookmarks.putIfAbsent(Triple(it.code, it.chapter, it.verse), it) }
        var addedBookmarks = 0
        incoming.bookmarks.forEach { if (bookmarks.putIfAbsent(Triple(it.code, it.chapter, it.verse), it) == null) addedBookmarks++ }

        val notes = LinkedHashMap<Triple<String, Int, Int>, NoteItem>()
        current.notes.forEach { n -> notes.merge(Triple(n.code, n.chapter, n.verse), n) { a, b -> if (b.updatedAt > a.updatedAt) b else a } }
        var addedNotes = 0
        var updatedNotes = 0
        incoming.notes.forEach { n ->
            val key = Triple(n.code, n.chapter, n.verse)
            val old = notes[key]
            when {
                old == null -> { notes[key] = n; addedNotes++ }
                n.updatedAt > old.updatedAt && n.text != old.text -> { notes[key] = n; updatedNotes++ }
            }
        }

        val history = LinkedHashMap<Pair<String, Int>, HistoryItem>()
        current.history.forEach { h -> history.merge(h.code to h.chapter, h) { a, b -> if (b.updatedAt > a.updatedAt) b else a } }
        var addedHistory = 0
        incoming.history.forEach { h ->
            val key = h.code to h.chapter
            val old = history[key]
            if (old == null) addedHistory++
            if (old == null || h.updatedAt > old.updatedAt) history[key] = h
        }

        return ImportResult(
            merged = current.copy(bookmarks = bookmarks.values.toList(), notes = notes.values.toList(), history = history.values.toList()),
            addedBookmarks = addedBookmarks,
            addedNotes = addedNotes,
            updatedNotes = updatedNotes,
            addedHistory = addedHistory,
        )
    }
}
