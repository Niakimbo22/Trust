package fr.niakimbo.trust.playback

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
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
    /** Information passagère (attente du réseau…), à montrer si aucune erreur. */
    val notice: String? = null,
    val clock: VerseClock? = null,
)

/** Dernière écoute, pour « Reprendre ». */
data class ResumePoint(val code: String, val name: String, val position: Double, val chapter: Int, val verse: Int, val savedAt: Long)

/**
 * Pilote le service audio depuis l'interface (via un MediaController) et expose un état simple.
 * Toute la logique « verset » vit ici, dans [VerseClock] et dans [PlaybackLogic].
 */
class PlayerController(
    private val context: Context,
    private val repo: ContentRepository,
    private val hub: PlaybackHub,
    private val scope: CoroutineScope,
    /** Connexion Internet disponible (voir [NetworkMonitor]). */
    private val online: StateFlow<Boolean>,
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
    private var sleepJob: Job? = null
    private var clock: VerseClock? = null
    private var lastSaved = 0L
    private var lastTitleKey = -1L
    /** Depuis quand le lecteur attend des données (0 = il n'attend pas). */
    private var waitingSince = 0L
    /** Lecture arrêtée par une erreur réseau : à relancer dès que possible. */
    private var pendingRetry = false

    // ---------------- Connexion au service ----------------

    /** À appeler quand l'écran revient : se reconnecte au service et relance une lecture coupée par le réseau. */
    fun connect() {
        if (controller?.isConnected == true) {
            retryIfPending()
            return
        }
        if (connecting?.isActive == true) return
        connecting = scope.launch { runCatching { ensureController() } }
    }

    private val connectMutex = Mutex()

    private val connectionListener = object : MediaController.Listener {
        /** Le service s'est arrêté : on oublie ce contrôleur, le prochain appel en recrée un. */
        override fun onDisconnected(controller: MediaController) {
            if (this@PlayerController.controller !== controller) return
            this@PlayerController.controller = null
            ticker?.cancel()
            _state.update { it.copy(isPlaying = false, buffering = false, notice = null) }
        }
    }

    /** Un seul MediaController, même si l'écran et une commande le demandent en même temps. */
    private suspend fun ensureController(): MediaController = connectMutex.withLock {
        controller?.let { old ->
            if (old.isConnected) return old
            old.removeListener(listener)
            old.release()
            controller = null
        }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).setListener(connectionListener).buildAsync()
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
        if (wantsToPlay(c)) startTicker()
        c
    }

    /** Le service jouait déjà (app rouverte) : on retrouve le livre d'après l'id du média. */
    private suspend fun adoptExistingSession(c: MediaController) {
        val code = c.currentMediaItem?.mediaId?.substringBefore('#') ?: return
        if (_state.value.book?.code == code) return
        val book = runCatching { repo.book(code) }.getOrNull() ?: return
        val clock = book.timings?.let { VerseClock(it) } ?: return
        setBook(book, clock)
    }

    private fun setBook(book: Book, clock: VerseClock) {
        this.clock = clock
        hub.clock = clock
        lastTitleKey = -1
        _state.update { it.copy(book = book, clock = clock, error = null) }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromPlayer()
            if (wantsToPlay(player)) startTicker()
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && !player.isPlaying) saveResume(force = true)
            // fin du livre : un minuteur « fin du chapitre » n'a plus rien à attendre
            if (player.playbackState == Player.STATE_ENDED && _state.value.sleep is SleepTimer.EndOfChapter) {
                _state.update { it.copy(sleep = null) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            pendingRetry = PlaybackLogic.isRetryable(error.errorCode)
            _state.update {
                it.copy(error = PlaybackLogic.errorMessage(error.errorCode, error.errorCodeName, online.value))
            }
        }
    }

    /** Le lecteur veut jouer (même s'il charge encore, ou s'il est suspendu par un appel). */
    private fun wantsToPlay(p: Player): Boolean =
        p.playWhenReady && p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED

    private fun syncFromPlayer() {
        val c = controller ?: return
        val clock = clock
        val pos = clock?.toGlobal(c.currentMediaItemIndex, c.currentPosition) ?: 0.0
        val ref = clock?.verseAt(pos)

        val waiting = c.playWhenReady && c.playbackState == Player.STATE_BUFFERING
        val now = SystemClock.elapsedRealtime()
        if (!waiting) waitingSince = 0L else if (waitingSince == 0L) waitingSince = now
        val notice = PlaybackLogic.networkNotice(waiting, online.value, if (waiting) now - waitingSince else 0L)
        if (c.isPlaying) pendingRetry = false

        _state.update {
            it.copy(
                position = pos,
                ref = ref ?: it.ref,
                isPlaying = c.isPlaying,
                buffering = c.playbackState == Player.STATE_BUFFERING,
                speed = c.playbackParameters.speed,
                error = if (c.isPlaying) null else it.error,
                notice = notice,
            )
        }
        if (ref != null) {
            updateTitle(c, ref.chapter)
            checkSleepEndOfChapter(ref)
        }
    }

    /** Tourne tant que le lecteur veut jouer : position, sauvegarde, minuteur, message d'attente du réseau. */
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val c = controller ?: break
                if (!wantsToPlay(c)) break
                syncFromPlayer()
                if (c.isPlaying) saveResume()
                checkSleepTimer()
                delay(TICK_MS)
            }
        }
    }

    /** Une erreur réseau avait arrêté la lecture : on la relance (au retour du réseau ou de l'écran). */
    private fun retryIfPending() {
        val c = controller ?: return
        if (!pendingRetry || c.playbackState != Player.STATE_IDLE) return
        pendingRetry = false
        if (!c.playWhenReady || c.mediaItemCount == 0) return // mis en pause entre-temps : on ne force rien
        _state.update { it.copy(error = null) }
        c.prepare()
    }

    // ---------------- Commandes ----------------

    /** Ouvre un livre et lit à partir d'un verset (verset 0 = annonce du chapitre). */
    fun play(code: String, chapter: Int, verse: Int = 0) = scope.launch {
        val book = loadBook(code) ?: return@launch
        val clock = clockFor(book) ?: return@launch
        // verset 0 = écouter le chapitre depuis son début ; verset inconnu des timings = idem
        val target = (if (verse > 0) clock.verseStart(chapter, verse) else null) ?: clock.listenStart(chapter) ?: run {
            _state.update { it.copy(error = "Pas d'enregistrement audio pour ce chapitre.") }
            return@launch
        }
        startAt(book, clock, target)
    }

    /** Reprend la dernière écoute. */
    fun resumeLast() = scope.launch {
        val r = _resume.value ?: return@launch
        // Ce livre est déjà dans le lecteur : on relance simplement, sans sauter en arrière.
        controller?.takeIf { it.isConnected && it.mediaItemCount > 0 && _state.value.book?.code == r.code }?.let { c ->
            if (!wantsToPlay(c)) togglePlay()
            return@launch
        }
        val book = loadBook(r.code) ?: return@launch
        val clock = clockFor(book) ?: return@launch
        startAt(book, clock, PlaybackLogic.resumeTarget(r.position, clock.duration))
    }

    private suspend fun loadBook(code: String): Book? = runCatching { repo.book(code) }.getOrElse {
        _state.update { s -> s.copy(error = it.message ?: "Livre indisponible.") }
        null
    }

    private fun clockFor(book: Book): VerseClock? {
        book.timings?.let { return VerseClock(it) }
        _state.update {
            it.copy(
                error = if (book.info.timings != null) "Pas de connexion : l'audio de ce livre n'a pas pu être préparé."
                else "L'audio de ce livre n'est pas encore prêt.",
            )
        }
        return null
    }

    private suspend fun startAt(book: Book, clock: VerseClock, global: Double) {
        val c = runCatching { ensureController() }.getOrElse {
            _state.update { s -> s.copy(error = "Le lecteur n'a pas démarré.") }
            return
        }
        expireSleepIfDue()
        val loaded = _state.value.book?.code == book.code && c.mediaItemCount == book.info.audio.size &&
            c.currentMediaItem?.mediaId?.startsWith(book.code + "#") == true
        if (loaded) {
            seek(global, fromUser = true)
            if (c.playbackState == Player.STATE_IDLE) c.prepare() // après une erreur, il faut recharger
        } else {
            // Nouveau livre : on charge la liste directement au bon endroit (pas de passage par 0:00).
            setBook(book, clock)
            retargetSleep(clock.verseAt(global).chapter)
            val p = clock.toFile(global)
            c.setMediaItems(mediaItems(book), p.index, p.positionMs)
            c.prepare()
            syncFromPlayer()
            saveResume(force = true)
        }
        c.play()
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
        if (chapter <= 0) return
        val index = c.currentMediaItemIndex
        val key = index * 100_000L + chapter // le titre est à refaire aussi quand on change de fichier
        if (key == lastTitleKey) return
        val book = _state.value.book ?: return
        val item = c.currentMediaItem ?: return
        lastTitleKey = key
        c.replaceMediaItem(index, item.buildUpon().setMediaMetadata(metadata(book, chapter)).build())
    }

    fun togglePlay() {
        val c = controller?.takeIf { it.isConnected }
        if (c == null || c.mediaItemCount == 0) {
            resumeLast()
            return
        }
        // « Pause » doit aussi marcher pendant un chargement (réseau lent), pas seulement quand ça joue.
        val loading = c.playWhenReady && c.playbackState == Player.STATE_BUFFERING
        if (c.isPlaying || loading) {
            c.pause()
            return
        }
        expireSleepIfDue()
        _state.update { it.copy(error = null) }
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) seek(0.0, fromUser = true)
        c.play()
    }

    fun pause() { controller?.pause() }

    /** Saut demandé par l'auditeur (barre, verset, chapitre, ±10 s). */
    fun seekGlobal(seconds: Double) = seek(seconds, fromUser = true)

    private fun seek(seconds: Double, fromUser: Boolean) {
        val c = controller ?: return
        val clock = clock ?: return
        if (fromUser) retargetSleep(clock.verseAt(seconds.coerceIn(0.0, clock.duration)).chapter)
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
        PlaybackLogic.chapterTarget(clock, _state.value.position, direction)?.let { seekGlobal(it) }
    }

    fun setSpeed(speed: Float) {
        prefs.edit().putFloat(KEY_SPEED, speed).apply()
        controller?.setPlaybackParameters(PlaybackParameters(speed))
        _state.update { it.copy(speed = speed) }
    }

    // ---------------- Minuteur de sommeil ----------------

    fun setSleepMinutes(minutes: Int?) {
        sleepJob?.cancel()
        val timer = minutes?.let { SleepTimer.At(System.currentTimeMillis() + it * 60_000L, it) }
        _state.update { it.copy(sleep = timer) }
        if (timer != null) {
            // Échéance même si la lecture est en pause à ce moment-là (le minuteur ne reste pas armé).
            sleepJob = scope.launch {
                delay(timer.minutes * 60_000L)
                fireSleep(timer)
            }
        }
    }

    fun setSleepEndOfChapter() {
        val ch = _state.value.ref?.chapter ?: return
        sleepJob?.cancel()
        _state.update { it.copy(sleep = SleepTimer.EndOfChapter(ch.coerceAtLeast(1))) }
    }

    private fun checkSleepTimer() {
        val s = _state.value.sleep as? SleepTimer.At ?: return
        if (PlaybackLogic.isDue(s, System.currentTimeMillis())) fireSleep(s)
    }

    private fun fireSleep(timer: SleepTimer.At) {
        if (_state.value.sleep != timer) return
        _state.update { it.copy(sleep = null) }
        controller?.pause()
    }

    /** Un minuteur échu pendant une pause ne doit pas recouper la lecture qu'on relance. */
    private fun expireSleepIfDue() {
        if (PlaybackLogic.isDue(_state.value.sleep, System.currentTimeMillis())) {
            sleepJob?.cancel()
            _state.update { it.copy(sleep = null) }
        }
    }

    private fun retargetSleep(chapter: Int) {
        _state.update { it.copy(sleep = PlaybackLogic.retarget(it.sleep, chapter)) }
    }

    private fun checkSleepEndOfChapter(ref: VerseRef) {
        val c = controller ?: return
        if (!c.isPlaying || !PlaybackLogic.chapterEnded(_state.value.sleep, ref)) return
        _state.update { it.copy(sleep = null) }
        c.pause()
        // on recule au tout début du passage qui commence, pour reprendre proprement
        clock?.let { seek(it.sectionAt(_state.value.position).start, fromUser = false) }
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

    init {
        // En dernier : toutes les propriétés ci-dessus existent déjà quand ce bloc démarre.
        scope.launch {
            online.collect { isOnline ->
                syncFromPlayer()
                if (isOnline) retryIfPending()
            }
        }
    }
}
