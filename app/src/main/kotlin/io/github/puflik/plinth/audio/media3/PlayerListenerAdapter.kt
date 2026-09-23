package io.github.puflik.plinth.audio.media3

import android.os.Handler
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import io.github.puflik.plinth.audio.engine.PlaybackError
import io.github.puflik.plinth.audio.engine.PlaybackEvent
import io.github.puflik.plinth.audio.engine.PlaybackState
import kotlin.time.Duration.Companion.milliseconds

/**
 * События ExoPlayer → [PlaybackState] и [PlaybackEvent] (B2.2).
 *
 * Живёт в потоке плеера: все обратные вызовы ExoPlayer приходят туда же,
 * куда и команды. Сам ничего не публикует — отдаёт наверх через [onState]
 * и [onEvent], а `Media3Engine` решает, доходит ли это до подписчиков.
 *
 * Позицию ExoPlayer сам не сообщает, её приходится спрашивать. Адаптер
 * сообщает её в трёх случаях: источник готов (стартовая позиция), перемотка,
 * и раз в [TICK] пока звук идёт — на этом живёт полоса перемотки.
 */
internal class PlayerListenerAdapter(
    private val player: Player,
    private val onState: (PlaybackState) -> Unit,
    private val onEvent: (PlaybackEvent) -> Unit,
) : Player.Listener {
    private val ticker = Handler(player.applicationLooper)
    private val tick =
        object : Runnable {
            override fun run() {
                reportPosition()
                ticker.postDelayed(this, TICK.inWholeMilliseconds)
            }
        }

    private var buffering = false
    private var startReported = false

    /** Новый источник: его стартовую позицию надо сообщить, когда он будет готов. */
    fun onPrepare() {
        startReported = false
    }

    /** Плеер освобождается — тикер больше не нужен. */
    fun stop() {
        ticker.removeCallbacks(tick)
    }

    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        onState(player.toPlaybackState())
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val nowBuffering = playbackState == Player.STATE_BUFFERING
        if (nowBuffering != buffering) {
            buffering = nowBuffering
            onEvent(PlaybackEvent.BufferingChanged(nowBuffering))
        }
        when (playbackState) {
            Player.STATE_READY ->
                if (!startReported) {
                    startReported = true
                    reportPosition()
                }
            Player.STATE_ENDED -> onEvent(PlaybackEvent.TrackEnded)
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        ticker.removeCallbacks(tick)
        if (isPlaying) ticker.postDelayed(tick, TICK.inWholeMilliseconds)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            onEvent(PlaybackEvent.PositionChanged(newPosition.positionMs.milliseconds))
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        onEvent(PlaybackEvent.Failed(error.toPlaybackError()))
    }

    private fun reportPosition() {
        onEvent(PlaybackEvent.PositionChanged(player.currentPosition.milliseconds))
    }

    private companion object {
        val TICK = 500.milliseconds
    }
}

/**
 * Что происходит с плеером, в терминах движка.
 *
 * `Playing` — только когда звук действительно идёт ([Player.isPlaying]):
 * плеер, который хочет играть, но подавлен (например, потерял аудиофокус),
 * для экрана стоит на паузе.
 */
internal fun Player.toPlaybackState(): PlaybackState {
    val error = playerError
    return when {
        error != null -> PlaybackState.Error(error.toPlaybackError())
        playbackState == Player.STATE_BUFFERING -> PlaybackState.Buffering
        playbackState == Player.STATE_READY -> if (isPlaying) PlaybackState.Playing else PlaybackState.Paused
        playbackState == Player.STATE_ENDED -> PlaybackState.Ended
        else -> PlaybackState.Idle
    }
}

/**
 * Код ошибки ExoPlayer → тип [PlaybackError].
 *
 * Битый файл (`PARSING_CONTAINER_MALFORMED`) — не «неподдержанный формат»:
 * формат мы знаем, файл повреждён. Отдельного типа для этого пока нет,
 * поэтому он уходит в [PlaybackError.Unknown] с кодом в подробностях.
 */
internal fun PlaybackException.toPlaybackError(): PlaybackError {
    val detail = listOfNotNull(errorCodeName, message).joinToString(": ")
    return when (errorCode) {
        in SOURCE_UNAVAILABLE -> PlaybackError.SourceUnavailable(detail)
        in NETWORK -> PlaybackError.Network(detail)
        in UNSUPPORTED_FORMAT -> PlaybackError.UnsupportedFormat(detail)
        else -> PlaybackError.Unknown(detail)
    }
}

private val SOURCE_UNAVAILABLE =
    setOf(
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    )

private val NETWORK =
    setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
    )

private val UNSUPPORTED_FORMAT =
    setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    )
