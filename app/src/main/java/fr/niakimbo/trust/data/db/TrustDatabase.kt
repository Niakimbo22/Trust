package fr.niakimbo.trust.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Marque-page sur un verset. */
@Entity(tableName = "bookmarks", indices = [Index(value = ["code", "chapter", "verse"], unique = true)])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val bookName: String,
    val chapter: Int,
    val verse: Int,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Note personnelle sur un verset (une par verset). */
@Entity(tableName = "notes", indices = [Index(value = ["code", "chapter", "verse"], unique = true)])
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val bookName: String,
    val chapter: Int,
    val verse: Int,
    val text: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Historique : un chapitre écouté, avec le dernier verset atteint. */
@Entity(tableName = "history", indices = [Index(value = ["code", "chapter"], unique = true)])
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val bookName: String,
    val chapter: Int,
    val lastVerse: Int,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface JournalDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun bookmarks(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE code = :code AND chapter = :chapter")
    fun bookmarksIn(code: String, chapter: Int): Flow<List<Bookmark>>

    @Upsert
    suspend fun saveBookmark(b: Bookmark)

    @Query("DELETE FROM bookmarks WHERE code = :code AND chapter = :chapter AND verse = :verse")
    suspend fun deleteBookmark(code: String, chapter: Int, verse: Int)

    @Query("SELECT COUNT(*) FROM bookmarks WHERE code = :code AND chapter = :chapter AND verse = :verse")
    suspend fun bookmarkCount(code: String, chapter: Int, verse: Int): Int

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun notes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE code = :code AND chapter = :chapter")
    fun notesIn(code: String, chapter: Int): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE code = :code AND chapter = :chapter AND verse = :verse LIMIT 1")
    suspend fun note(code: String, chapter: Int, verse: Int): Note?

    @Upsert
    suspend fun saveNote(n: Note)

    @Query("DELETE FROM notes WHERE code = :code AND chapter = :chapter AND verse = :verse")
    suspend fun deleteNote(code: String, chapter: Int, verse: Int)

    @Query("SELECT * FROM history ORDER BY updatedAt DESC LIMIT :limit")
    fun history(limit: Int = 100): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history WHERE code = :code AND chapter = :chapter LIMIT 1")
    suspend fun historyEntry(code: String, chapter: Int): HistoryEntry?

    @Upsert
    suspend fun saveHistory(h: HistoryEntry)

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    // ---- Sauvegarde / restauration du Carnet ----

    @Query("SELECT * FROM bookmarks ORDER BY createdAt")
    suspend fun allBookmarks(): List<Bookmark>

    @Query("SELECT * FROM notes ORDER BY updatedAt")
    suspend fun allNotes(): List<Note>

    @Query("SELECT * FROM history ORDER BY updatedAt")
    suspend fun allHistory(): List<HistoryEntry>

    @Query("DELETE FROM bookmarks")
    suspend fun clearBookmarks()

    @Query("DELETE FROM notes")
    suspend fun clearNotes()

    @Insert
    suspend fun insertBookmarks(items: List<Bookmark>)

    @Insert
    suspend fun insertNotes(items: List<Note>)

    @Insert
    suspend fun insertHistory(items: List<HistoryEntry>)
}

@Database(entities = [Bookmark::class, Note::class, HistoryEntry::class], version = 1, exportSchema = false)
abstract class TrustDatabase : RoomDatabase() {
    abstract fun journal(): JournalDao

    companion object {
        fun create(context: Context): TrustDatabase =
            Room.databaseBuilder(context, TrustDatabase::class.java, "trust.db").build()
    }
}
