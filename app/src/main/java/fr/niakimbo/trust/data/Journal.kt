package fr.niakimbo.trust.data

import fr.niakimbo.trust.data.db.Bookmark
import fr.niakimbo.trust.data.db.HistoryEntry
import fr.niakimbo.trust.data.db.JournalDao
import fr.niakimbo.trust.data.db.Note
import fr.niakimbo.trust.data.db.TrustDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Marque-pages, notes et historique : tout ce que l'utilisateur garde, stocké sur le téléphone (Room). */
class Journal(private val database: TrustDatabase, private val scope: CoroutineScope) {
    private val dao: JournalDao = database.journal()

    val bookmarks = dao.bookmarks()
    val notes = dao.notes()
    val history = dao.history()

    fun bookmarksIn(code: String, chapter: Int) = dao.bookmarksIn(code, chapter)
    fun notesIn(code: String, chapter: Int) = dao.notesIn(code, chapter)

    fun toggleBookmark(code: String, bookName: String, chapter: Int, verse: Int) = scope.launch {
        if (dao.bookmarkCount(code, chapter, verse) > 0) dao.deleteBookmark(code, chapter, verse)
        else dao.saveBookmark(Bookmark(code = code, bookName = bookName, chapter = chapter, verse = verse))
    }

    fun removeBookmark(b: Bookmark) = scope.launch { dao.deleteBookmark(b.code, b.chapter, b.verse) }

    suspend fun note(code: String, chapter: Int, verse: Int): Note? = dao.note(code, chapter, verse)

    /** Texte vide = suppression de la note. */
    fun saveNote(code: String, bookName: String, chapter: Int, verse: Int, text: String) = scope.launch {
        val existing = dao.note(code, chapter, verse)
        if (text.isBlank()) dao.deleteNote(code, chapter, verse)
        else dao.saveNote(
            Note(id = existing?.id ?: 0, code = code, bookName = bookName, chapter = chapter, verse = verse, text = text.trim()),
        )
    }

    /** Appelé pendant l'écoute : un chapitre écouté = une ligne d'historique, mise à jour. */
    fun recordListening(code: String, bookName: String, chapter: Int, verse: Int) = scope.launch {
        if (chapter <= 0) return@launch
        val existing = dao.historyEntry(code, chapter)
        dao.saveHistory(
            HistoryEntry(
                id = existing?.id ?: 0,
                code = code,
                bookName = bookName,
                chapter = chapter,
                lastVerse = verse,
            ),
        )
    }

    fun clearHistory() = scope.launch { dao.clearHistory() }

    // ---------------- Sauvegarde / restauration ----------------

    suspend fun exportBackup(): JournalBackup = JournalBackup(
        exportedAt = System.currentTimeMillis(),
        bookmarks = dao.allBookmarks().map { BookmarkItem(it.code, it.bookName, it.chapter, it.verse, it.createdAt) },
        notes = dao.allNotes().map { NoteItem(it.code, it.bookName, it.chapter, it.verse, it.text, it.updatedAt) },
        history = dao.allHistory().map { HistoryItem(it.code, it.bookName, it.chapter, it.lastVerse, it.updatedAt) },
    )

    /** Fusionne un Carnet importé avec celui du téléphone (rien n'est supprimé), d'un seul bloc. */
    suspend fun importBackup(incoming: JournalBackup): ImportResult = withContext(NonCancellable) {
        database.withTransaction {
            val result = JournalBackupCodec.merge(exportBackup(), incoming)
            if (result.changed) {
                val m = result.merged
                dao.clearBookmarks()
                dao.clearNotes()
                dao.clearHistory()
                dao.insertBookmarks(m.bookmarks.map { Bookmark(code = it.code, bookName = it.bookName, chapter = it.chapter, verse = it.verse, createdAt = it.createdAt) })
                dao.insertNotes(m.notes.map { Note(code = it.code, bookName = it.bookName, chapter = it.chapter, verse = it.verse, text = it.text, updatedAt = it.updatedAt) })
                dao.insertHistory(m.history.map { HistoryEntry(code = it.code, bookName = it.bookName, chapter = it.chapter, lastVerse = it.lastVerse, updatedAt = it.updatedAt) })
            }
            result
        }
    }
}
