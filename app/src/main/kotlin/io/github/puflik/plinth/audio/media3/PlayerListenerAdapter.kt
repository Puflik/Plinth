package io.github.puflik.plinth.audio.media3

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import io.github.puflik.plinth.audio.engine.PlaybackError
import io.github.puflik.plinth.audio.engine.PlaybackEvent
import io.github.puflik.plinth.audio.engine.PlaybackProgress
import io.github.puflik.plinth.audio.engine.PlaybackState
import io.github.puflik.plinth.diagnostics.log.AppLog
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
 * и раз в [TICK] пока звук идёт — на этом живёт полоса перемотки. Снимок
 * позиции и длительности ([onProgress]) обновляется при каждом событии
 * плеера и на каждом тике.
 */
internal class PlayerListenerAdapter(
    private val player: Player,
    private val onState: (PlaybackState) -> Unit,
    private val onEvent: (PlaybackEvent) -> Unit,
    private val onProgress: (PlaybackProgress) -> Unit,
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
    private var undecodable: PlaybackError? = null

    /** Новый источник: его стартовую позицию надо сообщить, когда он будет готов. */
    fun onPrepare() {
        startReported = false
        undecodable = null
    }

    /** Плеер освобождается — тикер больше не нужен. */
    fun stop() {
        ticker.removeCallbacks(tick)
    }

    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        onState(undecodable?.let(PlaybackState::Error) ?: player.toPlaybackState())
        onProgress(player.toPlaybackProgress())
    }

    /**
     * Звук в файле есть, а декодировать его системе нечем (FLAC на Android
     * 8.0, ALAC до 12-го — Н1–Н2 прогона на старых Android). ExoPlayer такую
     * дорожку просто не выбирает и «играет» трек по своим часам — время идёт,
     * звука нет, ошибки нет. Останавливаем его и говорим честно: формат не
     * поддерживается. Ошибка держится до следующего источника.
     */
    override fun onTracksChanged(tracks: Tracks) {
        if (undecodable != null || !tracks.containsType(C.TRACK_TYPE_AUDIO)) return
        if (tracks.isTypeSupported(C.TRACK_TYPE_AUDIO, true)) return
        val formats =
            tracks.groups
                .filter { it.type == C.TRACK_TYPE_AUDIO }
                .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it).sampleMimeType } }
                .distinct()
        val error = PlaybackError.UnsupportedFormat("no decoder for $formats")
        AppLog.w("Media3", "Playback error: no decoder for $formats")
        undecodable = error
        player.stop()
        onEvent(PlaybackEvent.Failed(error))
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
        // Адрес источника в тексте ошибки вырежет LogRedactor.
        AppLog.w("Media3", "Playback error ${error.errorCodeName}", error)
        onEvent(PlaybackEvent.Failed(error.toPlaybackError()))
    }

    private fun reportPosition() {
        onEvent(PlaybackEvent.PositionChanged(player.currentPosition.milliseconds))
        onProgress(player.toPlaybackProgress())
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

/** Позиция и длительность; `C.TIME_UNSET` — длительность ещё неизвестна. */
internal fun Player.toPlaybackProgress(): PlaybackProgress =
    PlaybackProgress(
        position = currentPosition.milliseconds,
        duration = duration.takeIf { it != C.TIME_UNSET }?.milliseconds,
    )

/**
 * Код ошибки ExoPlayer → тип [PlaybackError].
 *
 * Битый файл (`PARSING_CONTAINER_MALFORMED`) — не «неподдержанный формат»:
 * формат мы знаем, файл повреждён — [PlaybackError.Malformed].
 */
internal fun PlaybackException.toPlaybackError(): PlaybackError {
    val detail = listOfNotNull(errorCodeName, message).joinToString(": ")
    return when (errorCode) {
        in SOURCE_UNAVAILABLE -> PlaybackError.SourceUnavailable(detail)
        in NETWORK -> PlaybackError.Network(detail)
        in UNSUPPORTED_FORMAT -> PlaybackError.UnsupportedFormat(detail)
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> PlaybackError.Malformed(detail)
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
