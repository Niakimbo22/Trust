package fr.niakimbo.trust.content

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Contenu biblique (texte + timings), servi par le repo GitHub public et gardé en cache disque.
 * - books.json est rafraîchi au plus toutes les 12 h (sinon le cache sert, même hors-ligne).
 * - text/ et timings/ sont téléchargés à la première ouverture d'un livre, puis lus en local.
 *   Quand books.json change de version, on revalide ces fichiers.
 */
class ContentRepository(context: Context) {
    private val dir = File(context.filesDir, "content").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("content", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private var index: BooksIndex? = null
    private val books = mutableMapOf<String, Book>()

    suspend fun index(forceRefresh: Boolean = false): BooksIndex = mutex.withLock {
        index?.takeIf { !forceRefresh && !stale() }?.let { return it }
        withContext(Dispatchers.IO) {
            val file = File(dir, "books.json")
            val fresh = runCatching { download("books.json") }.getOrNull()
            val parsed = when {
                fresh != null -> json.decodeFromString<BooksIndex>(fresh).also {
                    val old = index ?: runCatching { json.decodeFromString<BooksIndex>(file.readText()) }.getOrNull()
                    if (old != null && old.version != it.version) invalidateBooks()
                    file.writeText(fresh)
                    prefs.edit().putLong(KEY_INDEX_TIME, System.currentTimeMillis()).apply()
                }
                file.exists() -> json.decodeFromString<BooksIndex>(file.readText())
                else -> throw IOException("Pas de connexion : impossible de charger la liste des livres.")
            }
            index = parsed
            parsed
        }
    }

    suspend fun book(code: String): Book {
        books[code]?.let { return it }
        val info = index().books.firstOrNull { it.code == code } ?: throw IOException("Livre inconnu : $code")
        return withContext(Dispatchers.IO) {
            val text = json.decodeFromString<BookText>(cached(info.text))
            val timings = info.timings?.let { path ->
                runCatching { json.decodeFromString<BookTimings>(cached(path)) }.getOrNull()
            }
            Book(info, text, timings).also { books[code] = it }
        }
    }

    /** Texte de tous les livres (téléchargé une fois, ~8 Mo, puis lu depuis le disque). */
    suspend fun allTexts(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<Pair<BookInfo, BookText>> {
        val books = index().books
        return withContext(Dispatchers.IO) {
            books.mapIndexed { i, info ->
                onProgress(i, books.size)
                info to (loadedBook(info.code)?.text ?: json.decodeFromString<BookText>(cached(info.text)))
            }.also { onProgress(books.size, books.size) }
        }
    }

    /** Livre déjà en mémoire (sans réseau), ou null. */
    fun loadedBook(code: String): Book? = books[code]

    private fun stale() = System.currentTimeMillis() - prefs.getLong(KEY_INDEX_TIME, 0) > INDEX_TTL_MS

    private fun invalidateBooks() {
        books.clear()
        File(dir, "text").deleteRecursively()
        File(dir, "timings").deleteRecursively()
    }

    private fun cached(path: String): String {
        val file = File(dir, path)
        if (file.exists()) return file.readText()
        val body = download(path)
        file.parentFile?.mkdirs()
        File(file.path + ".tmp").apply { writeText(body); renameTo(file) }
        return body
    }

    private fun download(path: String): String {
        val conn = URL("$BASE_URL/$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} pour $path")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val BASE_URL = "https://raw.githubusercontent.com/Niakimbo22/trust/main/content"
        const val KEY_INDEX_TIME = "index_time"
        const val INDEX_TTL_MS = 12 * 60 * 60 * 1000L
    }
}
