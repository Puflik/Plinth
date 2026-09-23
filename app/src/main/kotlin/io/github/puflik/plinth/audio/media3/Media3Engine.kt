package io.github.puflik.plinth.audio.media3

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.puflik.plinth.audio.engine.AudioEngine
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.PlaybackEvent
import io.github.puflik.plinth.audio.engine.PlaybackParams
import io.github.puflik.plinth.audio.engine.PlaybackProgress
import io.github.puflik.plinth.audio.engine.PlaybackState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration

/**
 * [AudioEngine] поверх ExoPlayer (B2.2).
 *
 * Движок работает в потоке своего плеера. В приложении это главный поток:
 * плеер делят движок и `PlaybackService`, а `MediaSessionService` требует,
 * чтобы плеер сессии жил на главном looper. Команды приходят откуда угодно —
 * из UI, службы, тестов, — поэтому команда проверяет аргументы и состояние
 * сразу, в потоке вызывающего (так контракт требует бросать исключения), а
 * до плеера доходит сообщением в его поток. Ответ — как у любого движка,
 * через [state] и [events]. Команды, пришедшие в плеер мимо движка (из
 * уведомления, гарнитуры), движок видит: адаптер слушает сам плеер.
 *
 * Создаётся в потоке плеера — там же, где на него подписывается адаптер.
 * [release] освобождает и плеер; в приложении движок живёт столько же,
 * сколько процесс, и не освобождается.
 *
 * Поля под [lock] — общие для вызывающих и потока плеера; `player` и
 * `adapter` трогает только поток плеера.
 *
 * `PlaybackParams.gaplessNext` пока не используется — контракт это
 * разрешает («движок вправе»), склейка треков придёт отдельной задачей.
 */
class Media3Engine(
    private val player: ExoPlayer,
) : AudioEngine {
    init {
        check(Looper.myLooper() == player.applicationLooper) { "Media3Engine создаётся в потоке своего плеера" }
    }

    private val handler = Handler(player.applicationLooper)

    private val lock = Any()
    private var released = false
    private var hasSource = false

    // Номер последнего prepare: запрошенного вызывающим и дошедшего до плеера.
    // Пока они расходятся, всё, что сообщает плеер, относится к прошлому
    // источнику и наверх не идёт — иначе запоздавшая ошибка старого трека
    // пометила бы новый как неготовый.
    private var requested = 0L
    private var applied = 0L

    private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private val mutableProgress = MutableStateFlow(PlaybackProgress.NONE)
    override val progress: StateFlow<PlaybackProgress> = mutableProgress.asStateFlow()

    private val mutableEvents =
        MutableSharedFlow<PlaybackEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val events: Flow<PlaybackEvent> = mutableEvents.asSharedFlow()

    private val adapter = PlayerListenerAdapter(player, ::publishState, ::publishEvent, ::publishProgress)

    init {
        player.addListener(adapter)
    }

    override fun prepare(
        source: AudioSource,
        params: PlaybackParams,
    ): Unit =
        synchronized(lock) {
            checkAlive()
            val item = MediaItemMapper.map(source)
            val generation = ++requested
            hasSource = true
            mutableState.value = PlaybackState.Buffering
            mutableProgress.value = PlaybackProgress(params.startPosition, null)
            onPlayer {
                synchronized(lock) { applied = generation }
                adapter.onPrepare()
                player.setMediaItem(item, params.startPosition.inWholeMilliseconds)
                player.playWhenReady = params.autoPlay
                player.prepare()
            }
        }

    override fun play(): Unit =
        command {
            // ExoPlayer, доигравший трек, на play() не реагирует; контракт
            // требует начать заново — так же, как это делает кнопка плеера.
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
            player.play()
        }

    override fun pause(): Unit = command { player.pause() }

    override fun seekTo(position: Duration): Unit =
        synchronized(lock) {
            checkAlive()
            checkSource()
            require(!position.isNegative()) { "позиция не может быть отрицательной: $position" }
            onPlayer { player.seekTo(position.inWholeMilliseconds) }
        }

    override fun setVolume(volume: Float): Unit =
        synchronized(lock) {
            checkAlive()
            require(volume in 0f..1f) { "громкость вне диапазона 0..1: $volume" }
            onPlayer { player.volume = volume }
        }

    override fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            hasSource = false
            mutableState.value = PlaybackState.Idle
            mutableProgress.value = PlaybackProgress.NONE
            // Последнее сообщение плееру: всё, что прошло проверку раньше,
            // уже стоит в очереди перед ним.
            onPlayer {
                adapter.stop()
                player.release()
            }
        }
    }

    private fun command(action: () -> Unit) =
        synchronized(lock) {
            checkAlive()
            checkSource()
            onPlayer(action)
        }

    private fun publishState(next: PlaybackState) {
        synchronized(lock) {
            if (released || applied != requested) return
            if (next is PlaybackState.Error) {
                hasSource = false
                mutableProgress.value = PlaybackProgress.NONE
            }
            mutableState.value = next
        }
    }

    private fun publishProgress(next: PlaybackProgress) {
        synchronized(lock) {
            // После ошибки источника нет — и снимка тоже, как у фейка.
            if (released || applied != requested || mutableState.value is PlaybackState.Error) return
            mutableProgress.value = next
        }
    }

    private fun publishEvent(event: PlaybackEvent) {
        synchronized(lock) {
            if (released || applied != requested) return
            mutableEvents.tryEmit(event)
        }
    }

    private fun onPlayer(action: () -> Unit) {
        handler.post(action)
    }

    private fun checkAlive() = check(!released) { "движок уже освобождён" }

    private fun checkSource() = check(hasSource) { "источник не подготовлен" }

    private companion object {
        const val EVENT_BUFFER = 64
    }
}
