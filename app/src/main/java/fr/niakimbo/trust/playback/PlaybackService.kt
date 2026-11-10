package fr.niakimbo.trust.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import fr.niakimbo.trust.MainActivity
import fr.niakimbo.trust.TrustApp

/**
 * Service de lecture : garde l'audio en vie écran éteint, gère le focus audio,
 * la notification et l'écran verrouillé (MediaSession).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as TrustApp).container
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(container.audioCache.dataSourceFactory)
                    // Coupure réseau (tunnel, ascenseur) : le lecteur reste en attente et réessaie toutes
                    // les 5 s pendant ~5 min au lieu d'abandonner au bout de quelques secondes.
                    // Tant qu'il attend, le service reste au premier plan : la reprise marche écran éteint.
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(NETWORK_RETRIES)),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause si on débranche le casque
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, VerseAwarePlayer(exo, container.playbackHub))
            .setSessionActivity(open)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** App balayée des récentes : on s'arrête seulement si rien ne joue. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private companion object {
        /** 5 premiers essais rapprochés (0 à 4 s), puis un toutes les 5 s. */
        const val NETWORK_RETRIES = 60
    }
}
