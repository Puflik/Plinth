package io.github.puflik.plinth.audio.media3

import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.puflik.plinth.audio.engine.AudioEngine
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.PlaybackEvent
import io.github.puflik.plinth.audio.engine.PlaybackParams
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
 * Плеер живёт в собственном потоке: ExoPlayer разрешает обращаться к себе
 * только из одного потока, а команды движка приходят откуда угодно — из UI,
 * из службы, из тестов. Поэтому команда проверяет аргументы и состояние
 * сразу, в потоке вызывающего (так контракт требует бросать исключения),
 * а до плеера доходит сообщением в его поток. Ответ — как у любого движка,
 * через [state] и [events].
 *
 * Поля под [lock] — общие для вызывающих и потока плеера; `player` и
 * `adapter` трогает только поток плеера.
 *
 * `PlaybackParams.gaplessNext` пока не используется — контракт это
 * разрешает («движок вправе»), склейка треков придёт отдельной задачей.
 */
class Media3Engine(
    playerFactory: ExoPlayerFactory,
) : AudioEngine {
    private val thread = HandlerThread(THREAD_NAME).apply { start() }
    private val handler = Handler(thread.looper)

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

    private val mutableEvents =
        MutableSharedFlow<PlaybackEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val events: Flow<PlaybackEvent> = mutableEvents.asSharedFlow()

    private lateinit var player: ExoPlayer
    private lateinit var adapter: PlayerListenerAdapter

    init {
        onPlayer {
            player = playerFactory.create(thread.looper)
            adapter = PlayerListenerAdapter(player, ::publishState, ::publishEvent)
            player.addListener(adapter)
        }
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
            onPlayer {
                adapter.stop()
                player.release()
            }
        }
        // Сообщения, отправленные до этого места, включая освобождение
        // плеера, поток ещё выполнит — и только потом остановится.
        thread.quitSafely()
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
            if (next is PlaybackState.Error) hasSource = false
            mutableState.value = next
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
        const val THREAD_NAME = "Media3Engine"
        const val EVENT_BUFFER = 64
    }
}
