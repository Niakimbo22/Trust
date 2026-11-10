package fr.niakimbo.trust.content

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Contenu biblique (texte + timings), servi par le repo GitHub public et gardé en cache disque.
 * - books.json : la copie en cache sert tout de suite (même hors-ligne ou réseau lent) ; au-delà de 12 h
 *   elle est rafraîchie en arrière-plan. On n'attend le réseau que s'il n'y a encore aucune copie.
 * - text/ et timings/ sont téléchargés à la première ouverture d'un livre, puis lus en local.
 *   Quand l'empreinte d'un livre change dans books.json, ses fichiers sont retéléchargés à la prochaine
 *   ouverture ; sans réseau, l'ancienne copie continue de servir (un livre préparé hors-ligne reste lisible).
 */
class ContentRepository(context: Context) {
    private val dir = File(context.filesDir, "content").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("content", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var index: BooksIndex? = null
    @Volatile private var lastRefreshAttempt = 0L
    private val books = ConcurrentHashMap<String, Book>()

    suspend fun index(forceRefresh: Boolean = false): BooksIndex {
        if (!forceRefresh) {
            cachedIndex()?.let { cached ->
                if (stale()) refreshInBackground()
                return cached
            }
        }
        return refresh() ?: cachedIndex()
            ?: throw IOException("Pas de connexion : impossible de charger la liste des livres.")
    }

    /** Copie locale de books.json (mémoire, sinon disque), sans réseau. */
    private suspend fun cachedIndex(): BooksIndex? {
        index?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                index ?: runCatching { json.decodeFromString<BooksIndex>(File(dir, "books.json").readText()) }
                    .getOrNull()?.also { index = it }
            }
        }
    }

    /** Télécharge books.json. Null si le réseau ne répond pas (ou répond autre chose). */
    private suspend fun refresh(): BooksIndex? = withContext(Dispatchers.IO) {
        mutex.withLock {
            lastRefreshAttempt = System.currentTimeMillis()
            val file = File(dir, "books.json")
            val body = runCatching { download("books.json") }.getOrNull() ?: return@withLock null
            val fresh = runCatching { json.decodeFromString<BooksIndex>(body) }.getOrNull() ?: return@withLock null
            val old = index ?: runCatching { json.decodeFromString<BooksIndex>(file.readText()) }.getOrNull()
            if (old != null && old.version != fresh.version) books.clear() // revalidés à la prochaine ouverture
            writeAtomically(file, body)
            prefs.edit().putLong(KEY_INDEX_TIME, System.currentTimeMillis()).apply()
            index = fresh
            fresh
        }
    }

    private fun refreshInBackground() {
        // hors-ligne, inutile de réessayer à chaque ouverture de livre
        if (System.currentTimeMillis() - lastRefreshAttempt < RETRY_REFRESH_MS) return
        lastRefreshAttempt = System.currentTimeMillis()
        scope.launch { runCatching { refresh() } }
    }

    suspend fun book(code: String): Book {
        books[code]?.let { return it }
        val info = index().books.firstOrNull { it.code == code } ?: throw IOException("Livre inconnu : $code")
        return withContext(Dispatchers.IO) {
            val text = try {
                cached<BookText>(info.text, info.rev)
            } catch (e: IOException) {
                throw IOException("Pas de connexion : ce livre n'a pas encore été chargé sur ce téléphone.", e)
            }
            val timings = info.timings?.let { path -> runCatching { cached<BookTimings>(path, info.rev) }.getOrNull() }
            Book(info, text, timings).also {
                // Timings attendus mais pas arrivés (réseau) : on ne retient pas ce livre « sans audio »,
                // la prochaine ouverture réessaiera.
                if (info.timings == null || timings != null) books[code] = it
            }
        }
    }

    /** Texte de tous les livres (téléchargé une fois, ~8 Mo, puis lu depuis le disque). */
    suspend fun allTexts(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<Pair<BookInfo, BookText>> {
        val books = index().books
        return withContext(Dispatchers.IO) {
            books.mapIndexed { i, info ->
                onProgress(i, books.size)
                info to (loadedBook(info.code)?.text ?: cached<BookText>(info.text, info.rev))
            }.also { onProgress(books.size, books.size) }
        }
    }

    /** Livre déjà en mémoire (sans réseau), ou null. */
    fun loadedBook(code: String): Book? = books[code]

    private fun stale() = System.currentTimeMillis() - prefs.getLong(KEY_INDEX_TIME, 0) > INDEX_TTL_MS

    /**
     * Fichier du cache s'il est à jour (même empreinte), sinon téléchargé. Sans réseau, une ancienne copie
     * lisible sert quand même. Un fichier abîmé est remplacé.
     */
    private inline fun <reified T> cached(path: String, rev: String?): T {
        val file = File(dir, path)
        val key = KEY_REV + path
        val local = if (file.exists()) runCatching { json.decodeFromString<T>(file.readText()) }.getOrNull() else null
        if (local != null && (rev == null || prefs.getString(key, null) == rev)) return local
        val body = try {
            download(path)
        } catch (e: IOException) {
            if (local != null) return local
            throw e
        }
        val value = json.decodeFromString<T>(body) // on ne met en cache que ce qui se lit
        writeAtomically(file, body)
        prefs.edit().putString(key, rev).apply()
        return value
    }

    /** Écrit à côté puis renomme : jamais de fichier à moitié écrit, même si deux lectures se croisent. */
    private fun writeAtomically(file: File, body: String) {
        file.parentFile?.mkdirs()
        val tmp = File.createTempFile(file.name, ".tmp", file.parentFile)
        try {
            tmp.writeText(body)
            if (!tmp.renameTo(file)) file.writeText(body)
        } finally {
            tmp.delete()
        }
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
        const val KEY_REV = "rev:"
        const val INDEX_TTL_MS = 12 * 60 * 60 * 1000L
        const val RETRY_REFRESH_MS = 5 * 60 * 1000L
    }
}
