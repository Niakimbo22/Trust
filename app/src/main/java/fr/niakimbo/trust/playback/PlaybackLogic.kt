package fr.niakimbo.trust.playback

/**
 * Règles du lecteur qui ne dépendent pas d'Android : faciles à tester (tests JVM).
 * [PlayerController] les applique au vrai lecteur.
 */
object PlaybackLogic {
    const val MSG_OFFLINE = "Pas de connexion. La lecture reprendra dès le retour du réseau."
    const val MSG_SLOW = "Connexion lente : nouvelle tentative en cours…"
    const val MSG_SERVER = "Le serveur audio ne répond pas. Réessaie dans un moment."

    /** Sans réseau : on prévient vite. Avec réseau : on laisse d'abord le chargement se faire. */
    const val OFFLINE_NOTICE_AFTER_MS = 1_500L
    const val SLOW_NOTICE_AFTER_MS = 10_000L

    /**
     * Message affiché pendant que le lecteur attend des données (null = rien à dire).
     * @param waiting le lecteur veut jouer mais charge encore
     * @param waitingForMs depuis combien de temps il attend
     */
    fun networkNotice(waiting: Boolean, online: Boolean, waitingForMs: Long): String? = when {
        !waiting -> null
        !online && waitingForMs >= OFFLINE_NOTICE_AFTER_MS -> MSG_OFFLINE
        online && waitingForMs >= SLOW_NOTICE_AFTER_MS -> MSG_SLOW
        else -> null
    }

    // Codes de androidx.media3.common.PlaybackException (recopiés pour rester sans Android).
    private const val IO_UNSPECIFIED = 2000
    private const val IO_NETWORK_CONNECTION_FAILED = 2001
    private const val IO_NETWORK_CONNECTION_TIMEOUT = 2002
    private const val IO_INVALID_HTTP_CONTENT_TYPE = 2003
    private const val IO_BAD_HTTP_STATUS = 2004

    /** Erreur de réseau ou de serveur : la lecture peut repartir toute seule au retour du réseau. */
    fun isRetryable(errorCode: Int): Boolean = errorCode in IO_UNSPECIFIED..IO_BAD_HTTP_STATUS

    /** Message montré quand le lecteur s'arrête sur une erreur. */
    fun errorMessage(errorCode: Int, errorCodeName: String, online: Boolean): String = when {
        !isRetryable(errorCode) -> "Lecture impossible ($errorCodeName)."
        !online || errorCode == IO_NETWORK_CONNECTION_FAILED || errorCode == IO_NETWORK_CONNECTION_TIMEOUT -> MSG_OFFLINE
        else -> MSG_SERVER
    }

    /** Reprise : on recule un peu pour retrouver le fil, sans sortir du livre. */
    fun resumeTarget(savedPosition: Double, duration: Double): Double =
        (savedPosition - RESUME_REWIND_S).coerceIn(0.0, duration.coerceAtLeast(0.0))

    /**
     * Cible de « chapitre précédent / suivant », dans l'ordre de l'audio (de passage en passage).
     * « Précédent » revient d'abord au début du passage en cours. Null = rien à faire.
     */
    fun chapterTarget(clock: VerseClock, position: Double, direction: Int): Double? {
        val sections = clock.sections
        val i = clock.sectionIndexAt(position)
        val cur = sections.getOrNull(i) ?: return null
        return if (direction < 0 && position - cur.start > CHAPTER_RESTART_THRESHOLD_S) cur.start
        else sections.getOrNull(i + direction)?.start
    }

    /** Minuteur « à telle heure » arrivé à échéance. */
    fun isDue(timer: SleepTimer?, nowMillis: Long): Boolean =
        timer is SleepTimer.At && nowMillis >= timer.endAtMillis

    /** Minuteur « fin du chapitre » : la lecture vient d'entrer dans un autre chapitre. */
    fun chapterEnded(timer: SleepTimer?, current: VerseRef): Boolean =
        timer is SleepTimer.EndOfChapter && current.chapter != timer.chapter

    /**
     * L'auditeur saute lui-même ailleurs : « fin du chapitre » vise alors le chapitre d'arrivée
     * (sinon la lecture se couperait tout de suite). Les autres minuteurs ne bougent pas.
     */
    fun retarget(timer: SleepTimer?, chapter: Int): SleepTimer? =
        if (timer is SleepTimer.EndOfChapter && chapter > 0 && timer.chapter != chapter) SleepTimer.EndOfChapter(chapter) else timer

    private const val RESUME_REWIND_S = 2.0
    private const val CHAPTER_RESTART_THRESHOLD_S = 3.0
}
