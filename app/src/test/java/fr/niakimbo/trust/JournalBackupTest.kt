package fr.niakimbo.trust

import fr.niakimbo.trust.data.BookmarkItem
import fr.niakimbo.trust.data.HistoryItem
import fr.niakimbo.trust.data.JournalBackup
import fr.niakimbo.trust.data.JournalBackupCodec
import fr.niakimbo.trust.data.NoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sauvegarde du Carnet : format du fichier et fusion à l'import (rien ne doit se perdre). */
class JournalBackupTest {
    private val phone = JournalBackup(
        exportedAt = 1,
        bookmarks = listOf(BookmarkItem("JHN", "Jean", 3, 16, createdAt = 100)),
        notes = listOf(NoteItem("JHN", "Jean", 3, 16, "Dieu a tant aimé le monde", updatedAt = 200)),
        history = listOf(HistoryItem("MRK", "Marc", 1, 12, updatedAt = 300)),
    )

    @Test fun roundTrip() {
        val text = JournalBackupCodec.encode(phone)
        assertTrue(text.contains("\"app\": \"trust-carnet\""))
        assertEquals(phone, JournalBackupCodec.decode(text))
    }

    @Test fun rejectsOtherFiles() {
        assertThrows(IllegalArgumentException::class.java) { JournalBackupCodec.decode("pas du json") }
        assertThrows(IllegalArgumentException::class.java) { JournalBackupCodec.decode("""{"version": 7, "books": []}""") }
        val newer = assertThrows(IllegalArgumentException::class.java) {
            JournalBackupCodec.decode("""{"app": "trust-carnet", "format": 99}""")
        }
        assertTrue(newer.message!!.contains("plus récente"))
    }

    @Test fun dropsBrokenEntriesAndIgnoresUnknownFields() {
        val b = JournalBackupCodec.decode(
            """
            {"app": "trust-carnet", "format": 1, "futur": true,
             "bookmarks": [{"code": "", "bookName": "?", "chapter": 1, "verse": 1}, {"code": "GEN", "bookName": "Genèse", "chapter": 1, "verse": 1}],
             "notes": [{"code": "GEN", "bookName": "Genèse", "chapter": 1, "verse": 1, "text": "   "},
                       {"code": "GEN", "bookName": "Genèse", "chapter": 1, "verse": 2, "text": "  Au commencement  "}],
             "history": [{"code": "GEN", "bookName": "Genèse", "chapter": 0, "verse": 3}, {"code": "GEN", "bookName": "Genèse", "chapter": 2, "verse": -1}]}
            """.trimIndent(),
        )
        assertEquals(listOf("GEN"), b.bookmarks.map { it.code })
        assertEquals(listOf("Au commencement"), b.notes.map { it.text })
        assertEquals(listOf(2 to 0), b.history.map { it.chapter to it.lastVerse })
    }

    @Test fun importAddsWithoutLosingAnything() {
        val file = JournalBackup(
            bookmarks = listOf(BookmarkItem("JHN", "Jean", 3, 16, createdAt = 50), BookmarkItem("PSA", "Psaumes", 23, 1)),
            notes = listOf(NoteItem("PSA", "Psaumes", 23, 1, "Le Seigneur est mon berger", updatedAt = 10)),
            history = listOf(HistoryItem("LUK", "Luc", 15, 3, updatedAt = 5)),
        )
        val r = JournalBackupCodec.merge(phone, file)
        assertEquals(1, r.addedBookmarks)
        assertEquals(1, r.addedNotes)
        assertEquals(0, r.updatedNotes)
        assertEquals(1, r.addedHistory)
        assertEquals(2, r.merged.bookmarks.size)
        // le marque-page déjà présent garde sa date d'origine
        assertEquals(100L, r.merged.bookmarks.first { it.code == "JHN" }.createdAt)
        assertEquals(2, r.merged.notes.size)
        assertEquals(2, r.merged.history.size)
        assertTrue(r.summary().contains("1 marque-page ajouté"))
    }

    @Test fun mostRecentNoteAndListeningWin() {
        val file = JournalBackup(
            notes = listOf(NoteItem("JHN", "Jean", 3, 16, "version plus récente", updatedAt = 999)),
            history = listOf(HistoryItem("MRK", "Marc", 1, 40, updatedAt = 10)), // plus ancienne : ignorée
        )
        val r = JournalBackupCodec.merge(phone, file)
        assertEquals(1, r.updatedNotes)
        assertEquals("version plus récente", r.merged.notes.single().text)
        assertEquals(12, r.merged.history.single().lastVerse)
        // une note plus ancienne ne remplace pas celle du téléphone
        val older = JournalBackupCodec.merge(phone, JournalBackup(notes = listOf(NoteItem("JHN", "Jean", 3, 16, "vieille", updatedAt = 1))))
        assertFalse(older.changed)
        assertEquals("Dieu a tant aimé le monde", older.merged.notes.single().text)
    }

    @Test fun importingTheSameFileTwiceChangesNothing() {
        val once = JournalBackupCodec.merge(JournalBackup(), phone)
        assertTrue(once.changed)
        val twice = JournalBackupCodec.merge(once.merged, phone)
        assertFalse(twice.changed)
        assertEquals("Rien de nouveau : ce Carnet est déjà à jour.", twice.summary())
        assertEquals(once.merged, twice.merged)
    }
}
