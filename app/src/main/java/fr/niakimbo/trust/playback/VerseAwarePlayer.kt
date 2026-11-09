package fr.niakimbo.trust.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** Lecteur dont « suivant / précédent » (notification, écran verrouillé, casque) = verset suivant / précédent. */
@OptIn(UnstableApi::class)
class VerseAwarePlayer(player: Player, private val hub: PlaybackHub) : ForwardingPlayer(player) {

    private val navCommands = listOf(
        COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_PREVIOUS,
        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(*navCommands.toIntArray()).build()

    override fun isCommandAvailable(command: Int): Boolean =
        command in navCommands || super.isCommandAvailable(command)

    private fun step(direction: Int, fallback: () -> Unit) {
        if (!hub.navigate(this, direction) { i, ms -> super.seekTo(i, ms) }) fallback()
    }

    override fun seekToNext() = step(+1) { super.seekToNext() }
    override fun seekToPrevious() = step(-1) { super.seekToPrevious() }
    override fun seekToNextMediaItem() = step(+1) { super.seekToNextMediaItem() }
    override fun seekToPreviousMediaItem() = step(-1) { super.seekToPreviousMediaItem() }
}
