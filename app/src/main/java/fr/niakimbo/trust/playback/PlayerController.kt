package fr.niakimbo.trust.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import fr.niakimbo.trust.content.Book
import fr.niakimbo.trust.content.ContentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Minuteur de sommeil. */
sealed interface SleepTimer {
    data class At(val endAtMillis: Long, val minutes: Int) : SleepTimer
    data class EndOfChapter(val chapter: Int) : SleepTimer
}

data class PlayerUiState(
    val book: Book? = null,
    val ref: VerseRef? = null,
    /** Temps global dans le livre, en secondes. */
    val position: Double = 0.0,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val speed: Float = 1f,
    val sleep: SleepTimer? = null,
    val error: String? = null,
    val clock: VerseClock? = null,
)

/** Dernière écoute, pour « Reprendre ». */
data class ResumePoint(val code: String, val name: String, val position: Double, val chapter: Int, val verse: Int, val savedAt: Long)

/**
 * Pilote le service audio depuis l'interface (via un MediaController) et expose un état simple.
 * Toute la logique « verset » vit ici et dans [VerseClock].
 */
class PlayerController(
    private val context: Context,
    private val repo: ContentRepository,
    private val hub: PlaybackHub,
    private val scope: CoroutineScope,
    /** Appelé pendant l'écoute (toutes les ~5 s) : sert à l'historique. */
    private val onProgress: (code: String, bookName: String, chapter: Int, verse: Int) -> Unit = { _, _, _, _ -> },
) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(PlayerUiState(speed = prefs.getFloat(KEY_SPEED, 1f)))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _resume = MutableStateFlow(loadResume())
    val resume: StateFlow<ResumePoint?> = _resume.asStateFlow()

    private var controller: MediaController? = null
    private var connecting: Job? = null
    private var ticker: Job? = null
    private var clock: VerseClock? = null
    private var lastSaved = 0L
    private var lastTitleChapter = -1

    // ---------------- Connexion au service ----------------

    /** À appeler au démarrage de l'écran : se reconnecte au service s'il joue déjà. */
    fun connect() {
        if (controller != null || connecting?.isActive == true) return
        connecting = scope.launch { runCatching { ensureController() } }
    }

    private val connectMutex = Mutex()

    /** Un seul MediaController, même si l'écran et une commande le demandent en même temps. */
    private suspend fun ensureController(): MediaController = connectMutex.withLock {
        controller?.let { return it }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val c = suspendCancellableCoroutine<MediaController> { cont ->
            future.addListener({
                runCatching { future.get() }
                    .onSuccess { cont.resume(it) }
                    .onFailure { cont.resumeWithException(it) }
            }, ContextCompat.getMainExecutor(context))
            cont.invokeOnCancellation { future.cancel(true) }
        }
        controller = c
        c.addListener(listener)
        c.setPlaybackParameters(PlaybackParameters(_state.value.speed))
        adoptExistingSession(c)
        syncFromPlayer()
        if (c.isPlaying) startTicker()
        c
    }

    /** Le service jouait déjà (app rouverte) : on retrouve le livre d'après l'id du média. */
    private suspend fun adoptExistingSession(c: MediaController) {
        val code = c.currentMediaItem?.mediaId?.substringBefore('#') ?: return
        if (_state.value.book?.code == code) return
        val book = runCatching { repo.book(code) }.getOrNull() ?: return
        setBook(book)
    }

    private fun setBook(book: Book) {
        clock = book.timings?.let { VerseClock(it) }
        hub.clock = clock
        lastTitleChapter = -1
        _state.update { it.copy(book = book, clock = clock, error = null) }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromPlayer()
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                if (player.isPlaying) startTicker() else saveResume(force = true)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update {
                it.copy(error = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    -> "Pas de connexion : l'audio n'a pas pu être chargé."
                    else -> "Lecture impossible (${error.errorCodeName})."
                })
            }
        }
    }

    private fun syncFromPlayer() {
        val c = controller ?: return
        val clock = clock
        val pos = clock?.toGlobal(c.currentMediaItemIndex, c.currentPosition) ?: 0.0
        val ref = clock?.verseAt(pos)
        _state.update {
            it.copy(
                position = pos,
                ref = ref ?: it.ref,
                isPlaying = c.isPlaying,
                buffering = c.playbackState == Player.STATE_BUFFERING,
                speed = c.playbackParameters.speed,
                error = if (c.isPlaying) null else it.error,
            )
        }
        if (ref != null) {
            updateTitle(c, ref.chapter)
            checkSleepEndOfChapter(ref)
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive && controller?.isPlaying == true) {
                syncFromPlayer()
                saveResume()
                checkSleepTimer()
                delay(TICK_MS)
            }
        }
    }

    // ---------------- Commandes ----------------

    /** Ouvre un livre et lit à partir d'un verset (verset 0 = annonce du chapitre). */
    fun play(code: String, chapter: Int, verse: Int = 0) = scope.launch {
        val book = runCatching { repo.book(code) }.getOrElse {
            _state.update { s -> s.copy(error = it.message ?: "Livre indisponible.") }
            return@launch
        }
        val clock = book.timings?.let { VerseClock(it) } ?: run {
            _state.update { it.copy(error = "L'audio de ce livre n'est pas encore prêt.") }
            return@launch
        }
        val target = clock.verseStart(chapter, verse) ?: clock.chapterStart(chapter) ?: 0.0
        startAt(book, target, playNow = true)
    }

    /** Reprend la dernière écoute. */
    fun resumeLast() = scope.launch {
        val r = _resume.value ?: return@launch
        val book = runCatching { repo.book(r.code) }.getOrNull() ?: return@launch
        // on recule un peu pour retrouver le fil
        startAt(book, (r.position - 2.0).coerceAtLeast(0.0), playNow = true)
    }

    private suspend fun startAt(book: Book, global: Double, playNow: Boolean) {
        val c = runCatching { ensureController() }.getOrElse {
            _state.update { s -> s.copy(error = "Le lecteur n'a pas démarré.") }
            return
        }
        if (_state.value.book?.code != book.code || c.mediaItemCount != book.info.audio.size ||
            c.currentMediaItem?.mediaId?.startsWith(book.code + "#") != true
        ) {
            setBook(book)
            c.setMediaItems(mediaItems(book))
            c.prepare()
        }
        seekGlobal(global)
        if (playNow) c.play()
    }

    private fun mediaItems(book: Book): List<MediaItem> = book.info.audio.mapIndexed { i, a ->
        MediaItem.Builder()
            .setMediaId("${book.code}#$i")
            .setUri(a.url)
            .setMediaMetadata(metadata(book, null))
            .build()
    }

    private fun metadata(book: Book, chapter: Int?) = MediaMetadata.Builder()
        .setTitle(if (chapter != null && chapter > 0) "${book.info.short} $chapter" else book.info.short)
        .setArtist("Bible Crampon · Mission Web")
        .setAlbumTitle(book.info.name)
        .build()

    /** Titre de la notification = livre + chapitre en cours (sans couper la lecture). */
    private fun updateTitle(c: MediaController, chapter: Int) {
        if (chapter == lastTitleChapter || chapter <= 0) return
        val book = _state.value.book ?: return
        val item = c.currentMediaItem ?: return
        lastTitleChapter = chapter
        c.replaceMediaItem(
            c.currentMediaItemIndex,
            item.buildUpon().setMediaMetadata(metadata(book, chapter)).build(),
        )
    }

    fun togglePlay() {
        val c = controller
        if (c == null || c.mediaItemCount == 0) {
            resumeLast()
            return
        }
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) seekGlobal(0.0)
            c.play()
        }
    }

    fun pause() { controller?.pause() }

    fun seekGlobal(seconds: Double) {
        val c = controller ?: return
        val clock = clock ?: return
        val p = clock.toFile(seconds)
        c.seekTo(p.index, p.positionMs)
        syncFromPlayer()
        saveResume(force = true)
    }

    fun seekTo(ref: VerseRef) {
        val clock = clock ?: return
        clock.verseStart(ref.chapter, ref.verse)?.let { seekGlobal(it) }
    }

    fun skip(seconds: Double) = seekGlobal(_state.value.position + seconds)

    fun nextVerse() = step(+1)
    fun previousVerse() = step(-1)

    private fun step(direction: Int) {
        val clock = clock ?: return
        PlaybackHub.targetForStep(clock, _state.value.position, direction)?.let { seekGlobal(it) }
    }

    fun nextChapter() = jumpChapter(+1)
    fun previousChapter() = jumpChapter(-1)

    private fun jumpChapter(direction: Int) {
        val clock = clock ?: return
        val cur = _state.value.ref?.chapter ?: return
        val chapters = clock.chapters
        val target = if (direction < 0 && _state.value.position - (clock.chapterStart(cur) ?: 0.0) > 3.0) {
            cur // « précédent » revient d'abord au début du chapitre en cours
        } else {
            chapters.getOrNull(chapters.indexOf(cur) + direction) ?: return
        }
        clock.chapterStart(target)?.let { seekGlobal(it) }
    }

    fun setSpeed(speed: Float) {
        prefs.edit().putFloat(KEY_SPEED, speed).apply()
        controller?.setPlaybackParameters(PlaybackParameters(speed))
        _state.update { it.copy(speed = speed) }
    }

    // ---------------- Minuteur de sommeil ----------------

    fun setSleepMinutes(minutes: Int?) {
        _state.update {
            it.copy(sleep = minutes?.let { m -> SleepTimer.At(System.currentTimeMillis() + m * 60_000L, m) })
        }
    }

    fun setSleepEndOfChapter() {
        val ch = _state.value.ref?.chapter ?: return
        _state.update { it.copy(sleep = SleepTimer.EndOfChapter(ch.coerceAtLeast(1))) }
    }

    private fun checkSleepTimer() {
        val s = _state.value.sleep as? SleepTimer.At ?: return
        if (System.currentTimeMillis() >= s.endAtMillis) {
            controller?.pause()
            _state.update { it.copy(sleep = null) }
        }
    }

    private fun checkSleepEndOfChapter(ref: VerseRef) {
        val s = _state.value.sleep as? SleepTimer.EndOfChapter ?: return
        if (ref.chapter != s.chapter && controller?.isPlaying == true) {
            // on recule au tout début du chapitre suivant, pour reprendre proprement
            controller?.pause()
            clock?.chapterStart(ref.chapter)?.let { seekGlobal(it) }
            _state.update { it.copy(sleep = null) }
        }
    }

    // ---------------- Reprise ----------------

    private fun saveResume(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSaved < SAVE_EVERY_MS) return
        val s = _state.value
        val book = s.book ?: return
        val ref = s.ref ?: return
        lastSaved = now
        val point = ResumePoint(book.code, book.info.short, s.position, ref.chapter, ref.verse, now)
        prefs.edit()
            .putString(KEY_CODE, point.code)
            .putString(KEY_NAME, point.name)
            .putFloat(KEY_POS, point.position.toFloat())
            .putInt(KEY_CHAPTER, point.chapter)
            .putInt(KEY_VERSE, point.verse)
            .putLong(KEY_SAVED_AT, now)
            .apply()
        _resume.value = point
        onProgress(book.code, book.info.short, ref.chapter, ref.verse)
    }

    private fun loadResume(): ResumePoint? {
        val code = prefs.getString(KEY_CODE, null) ?: return null
        return ResumePoint(
            code,
            prefs.getString(KEY_NAME, null) ?: code,
            prefs.getFloat(KEY_POS, 0f).toDouble(),
            prefs.getInt(KEY_CHAPTER, 1),
            prefs.getInt(KEY_VERSE, 1),
            prefs.getLong(KEY_SAVED_AT, 0),
        )
    }

    private companion object {
        const val TICK_MS = 250L
        const val SAVE_EVERY_MS = 5_000L
        const val KEY_SPEED = "speed"
        const val KEY_CODE = "resume_code"
        const val KEY_NAME = "resume_name"
        const val KEY_POS = "resume_pos"
        const val KEY_CHAPTER = "resume_chapter"
        const val KEY_VERSE = "resume_verse"
        const val KEY_SAVED_AT = "resume_saved_at"
    }
}
