package fr.niakimbo.trust.playback

import androidx.media3.common.Player

/**
 * Pont entre le service audio (notification, écran verrouillé, casque) et le contrôleur de l'app :
 * il connaît le livre en cours pour que « suivant / précédent » sautent de verset en verset.
 */
class PlaybackHub {
    @Volatile var clock: VerseClock? = null

    /** Navigation au verset depuis les boutons système. Retourne false si rien à faire. */
    fun navigate(player: Player, direction: Int, seek: (Int, Long) -> Unit): Boolean {
        val clock = clock ?: return false
        val now = clock.toGlobal(player.currentMediaItemIndex, player.currentPosition)
        val target = targetForStep(clock, now, direction) ?: return false
        val pos = clock.toFile(target)
        seek(pos.index, pos.positionMs)
        return true
    }

    companion object {
        /** Verset suivant / précédent. « Précédent » revient d'abord au début du verset en cours. */
        fun targetForStep(clock: VerseClock, now: Double, direction: Int): Double? {
            val cur = clock.verseAt(now)
            return if (direction > 0) {
                clock.next(cur)?.let { clock.verseStart(it.chapter, it.verse) }
            } else {
                val curStart = clock.verseStart(cur.chapter, cur.verse) ?: return null
                if (now - curStart > RESTART_THRESHOLD_S) curStart
                else clock.previous(cur)?.let { clock.verseStart(it.chapter, it.verse) } ?: curStart
            }
        }

        private const val RESTART_THRESHOLD_S = 2.0
    }
}
