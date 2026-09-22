package io.github.puflik.plinth.audio.engine

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Контракт `AudioEngine` (B1.4) — набор требований, который обязана проходить
 * любая реализация движка.
 *
 * Смысл базового класса в том, что тесты пишутся один раз и переиспользуются:
 * `FakeAudioEngine` проходит их на JVM, `Media3Engine` — на эмуляторе (B2),
 * а движок на Rust в v0.2+ подставляется тем же способом. Если реализацию
 * нельзя провести через этот файл, она не реализует `AudioEngine`.
 *
 * Тесты написаны на `runBlocking`, а не на `runTest`: виртуальное время
 * `runTest` не двигает настоящий плеер, живущий в своём потоке. Ожидание
 * идёт через `state`/`events` и ограничено [timeout].
 */
abstract class AudioEngineContractTest {
    /** Новый движок в начальном состоянии; освобождает его сам тест. */
    protected abstract fun createEngine(): AudioEngine

    /**
     * Источник, который движок обязан воспроизвести.
     * Длительность — не меньше двух секунд: этого требуют проверки позиции.
     */
    protected abstract fun playableSource(): AudioSource

    /** Источник, которого нет: удалённый файл, битый URI. */
    protected abstract fun unavailableSource(): AudioSource

    /**
     * Доводит текущий трек до конца.
     * Фейк делает это мгновенно, настоящий движок — ожиданием конца файла.
     */
    protected abstract suspend fun playToEnd(engine: AudioEngine)

    /**
     * Допуск при проверке позиции: настоящий декодер встаёт на ближайший
     * кадр, а не точно в запрошенную миллисекунду.
     */
    protected open val positionTolerance: Duration = Duration.ZERO

    /** Предел ожидания состояния или события. */
    protected open val timeout: Duration = 5.seconds

    @Test
    fun `new engine is idle`() =
        contract { engine ->
            assertThat(engine.state.value).isEqualTo(PlaybackState.Idle)
        }

    @Test
    fun `prepared engine waits in paused`() =
        contract { engine ->
            engine.prepare(playableSource())

            assertThat(engine.awaitState { it is PlaybackState.Paused }).isEqualTo(PlaybackState.Paused)
        }

    @Test
    fun `prepare with auto play starts playback`() =
        contract { engine ->
            engine.prepare(playableSource(), PlaybackParams(autoPlay = true))

            assertThat(engine.awaitState { it is PlaybackState.Playing }).isEqualTo(PlaybackState.Playing)
        }

    @Test
    fun `play resumes prepared engine`() =
        contract { engine ->
            engine.prepare(playableSource())
            engine.awaitState { it is PlaybackState.Paused }

            engine.play()

            assertThat(engine.awaitState { it is PlaybackState.Playing }).isEqualTo(PlaybackState.Playing)
        }

    @Test
    fun `pause suspends playback`() =
        contract { engine ->
            engine.prepare(playableSource(), PlaybackParams(autoPlay = true))
            engine.awaitState { it is PlaybackState.Playing }

            engine.pause()

            assertThat(engine.awaitState { it is PlaybackState.Paused }).isEqualTo(PlaybackState.Paused)
        }

    @Test
    fun `prepare honours start position`() =
        contract { engine ->
            val start = ONE_SECOND
            val reported = awaitEvent(engine) { it is PlaybackEvent.PositionChanged }

            engine.prepare(playableSource(), PlaybackParams(startPosition = start))

            val position = (reported.await() as PlaybackEvent.PositionChanged).position
            assertThat(position.inWholeMilliseconds)
                .isAtLeast((start - positionTolerance).inWholeMilliseconds)
        }

    @Test
    fun `seek reports new position`() =
        contract { engine ->
            engine.prepare(playableSource())
            engine.awaitState { it is PlaybackState.Paused }
            val target = ONE_SECOND
            val reported =
                awaitEvent(engine) {
                    it is PlaybackEvent.PositionChanged && it.position >= target - positionTolerance
                }

            engine.seekTo(target)

            assertThat(reported.await()).isInstanceOf(PlaybackEvent.PositionChanged::class.java)
        }

    @Test
    fun `finished track ends playback`() =
        contract { engine ->
            engine.prepare(playableSource(), PlaybackParams(autoPlay = true))
            engine.awaitState { it is PlaybackState.Playing }
            val ended = awaitEvent(engine) { it is PlaybackEvent.TrackEnded }

            playToEnd(engine)

            assertThat(ended.await()).isEqualTo(PlaybackEvent.TrackEnded)
            assertThat(engine.awaitState { it is PlaybackState.Ended }).isEqualTo(PlaybackState.Ended)
        }

    @Test
    fun `unavailable source reports typed error`() =
        contract { engine ->
            val failed = awaitEvent(engine) { it is PlaybackEvent.Failed }

            engine.prepare(unavailableSource())

            val error = (failed.await() as PlaybackEvent.Failed).error
            assertThat(error).isInstanceOf(PlaybackError.SourceUnavailable::class.java)
            val state = engine.awaitState { it is PlaybackState.Error }
            assertThat((state as PlaybackState.Error).error).isEqualTo(error)
        }

    @Test
    fun `play without source is rejected`() =
        contract { engine ->
            assertThrows(IllegalStateException::class.java) { engine.play() }
        }

    @Test
    fun `seek without source is rejected`() =
        contract { engine ->
            assertThrows(IllegalStateException::class.java) { engine.seekTo(ONE_SECOND) }
        }

    @Test
    fun `negative seek is rejected`() =
        contract { engine ->
            engine.prepare(playableSource())
            engine.awaitState { it is PlaybackState.Paused }

            assertThrows(IllegalArgumentException::class.java) { engine.seekTo(-ONE_SECOND) }
        }

    @Test
    fun `volume outside zero to one is rejected`() =
        contract { engine ->
            engine.setVolume(0f)
            engine.setVolume(HALF_VOLUME)
            engine.setVolume(1f)

            assertThrows(IllegalArgumentException::class.java) { engine.setVolume(-0.01f) }
            assertThrows(IllegalArgumentException::class.java) { engine.setVolume(1.01f) }
        }

    @Test
    fun `released engine returns to idle and rejects commands`() =
        contract { engine ->
            engine.prepare(playableSource())
            engine.awaitState { it is PlaybackState.Paused }

            engine.release()

            assertThat(engine.state.value).isEqualTo(PlaybackState.Idle)
            assertThrows(IllegalStateException::class.java) { engine.play() }
            assertThrows(IllegalStateException::class.java) { engine.pause() }
            assertThrows(IllegalStateException::class.java) { engine.seekTo(Duration.ZERO) }
            assertThrows(IllegalStateException::class.java) { engine.prepare(playableSource()) }
        }

    @Test
    fun `release is idempotent`() =
        contract { engine ->
            engine.release()
            engine.release()

            assertThat(engine.state.value).isEqualTo(PlaybackState.Idle)
        }

    /**
     * Обёртка теста: свой движок на каждый тест, общий предел ожидания и
     * гарантированное освобождение ресурсов.
     */
    private fun contract(body: suspend CoroutineScope.(AudioEngine) -> Unit) =
        runBlocking {
            val engine = createEngine()
            try {
                withTimeout(timeout) { this.body(engine) }
            } finally {
                runCatching { engine.release() }
            }
        }

    /** Ждёт состояние; `StateFlow` отдаёт текущее значение, гонки нет. */
    protected suspend fun AudioEngine.awaitState(predicate: (PlaybackState) -> Boolean): PlaybackState =
        state.first(predicate)

    /**
     * Подписывается на события до того, как вернёт управление: `UNDISPATCHED`
     * доводит корутину до первой точки приостановки в вызывающем потоке.
     * Иначе событие, отправленное сразу после вызова, потерялось бы.
     */
    protected fun CoroutineScope.awaitEvent(
        engine: AudioEngine,
        predicate: (PlaybackEvent) -> Boolean,
    ): Deferred<PlaybackEvent> = async(start = CoroutineStart.UNDISPATCHED) { engine.events.first(predicate) }

    private companion object {
        val ONE_SECOND = 1.seconds
        const val HALF_VOLUME = 0.5f
    }
}
