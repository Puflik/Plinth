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
 *
 * Имена тестов — через подчёркивание, а не фразой в обратных кавычках:
 * класс собирается и в APK для эмулятора, а DEX до API 30 (у нас
 * `minSdk 26`) не допускает пробелов в именах методов и классов.
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
    fun new_engine_is_idle() =
        contract { engine ->
            assertThat(engine.state.value).isEqualTo(PlaybackState.Idle)
        }

    @Test
    fun prepared_engine_waits_in_paused() =
        contract { engine ->
            engine.prepare(playableSource())

            assertThat(engine.awaitState { it is PlaybackState.Paused }).isEqualTo(PlaybackState.Paused)
        }

    @Test
    fun prepare_with_auto_play_starts_playback() =
        contract { engine ->
            engine.prepare(playableSource(), PlaybackParams(autoPlay = true))

            assertThat(engine.awaitState { it is PlaybackState.Playing }).isEqualTo(PlaybackState.Playing)
        }

    @Test
    fun play_resumes_prepared_engine() =
        contract { engine ->
            engine.prepare(playableSource())
            engine.awaitState { it is PlaybackState.Paused }

            engine.play()

            assertThat(engine.awaitState { it is PlaybackState.Playing }).isEqualTo(PlaybackState.Playing)
        }

    @Test
    fun pause_suspends_playback() =
        contract { engine ->
            engine.prepare(playableSource(), PlaybackParams(autoPlay = true))
            engine.awaitState { it is PlaybackState.Playing }

            engine.pause()

            assertThat(engine.awaitState { it is PlaybackState.Paused }).isEqualTo(PlaybackState.Paused)
        }

    @Test
    fun prepare_honours_start_position() =
        contract { engine ->
            val start = ONE_SECOND
            val reported = awaitEvent(engine) { it is PlaybackEvent.PositionChanged }

            engine.prepare(playableSource(), PlaybackParams(startPosition = start))

            val position = (reported.await() as PlaybackEvent.PositionChanged).position
            assertThat(position.inWholeMilliseconds)
                .isAtLeast((start - positionTolerance).inWholeMilliseconds)
        }

    @Test
    fun seek_reports_new_position() =
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
    fun finished_track_ends_playback() =
        contract { engine ->
            engine.prepare(playableSource(), PlaybackParams(autoPlay = true))
            engine.awaitState { it is PlaybackState.Playing }
            val ended = awaitEvent(engine) { it is PlaybackEvent.TrackEnded }

            playToEnd(engine)

            assertThat(ended.await()).isEqualTo(PlaybackEvent.TrackEnded)
            assertThat(engine.awaitState { it is PlaybackState.Ended }).isEqualTo(PlaybackState.Ended)
        }

    @Test
    fun play_after_end_starts_track_again() =
        contract { engine ->
            engine.prepare(playableSource(), PlaybackParams(autoPlay = true))
            engine.awaitState { it is PlaybackState.Playing }
            playToEnd(engine)
            engine.awaitState { it is PlaybackState.Ended }

            engine.play()

            assertThat(engine.awaitState { it is PlaybackState.Playing }).isEqualTo(PlaybackState.Playing)
        }

    @Test
    fun unavailable_source_reports_typed_error() =
        contract { engine ->
            val failed = awaitEvent(engine) { it is PlaybackEvent.Failed }

            engine.prepare(unavailableSource())

            val error = (failed.await() as PlaybackEvent.Failed).error
            assertThat(error).isInstanceOf(PlaybackError.SourceUnavailable::class.java)
            val state = engine.awaitState { it is PlaybackState.Error }
            assertThat((state as PlaybackState.Error).error).isEqualTo(error)
        }

    @Test
    fun play_without_source_is_rejected() =
        contract { engine ->
            assertThrows(IllegalStateException::class.java) { engine.play() }
        }

    @Test
    fun seek_without_source_is_rejected() =
        contract { engine ->
            assertThrows(IllegalStateException::class.java) { engine.seekTo(ONE_SECOND) }
        }

    @Test
    fun negative_seek_is_rejected() =
        contract { engine ->
            engine.prepare(playableSource())
            engine.awaitState { it is PlaybackState.Paused }

            assertThrows(IllegalArgumentException::class.java) { engine.seekTo(-ONE_SECOND) }
        }

    @Test
    fun volume_outside_zero_to_one_is_rejected() =
        contract { engine ->
            engine.setVolume(0f)
            engine.setVolume(HALF_VOLUME)
            engine.setVolume(1f)

            assertThrows(IllegalArgumentException::class.java) { engine.setVolume(BELOW_SILENCE) }
            assertThrows(IllegalArgumentException::class.java) { engine.setVolume(ABOVE_FULL) }
        }

    @Test
    fun released_engine_returns_to_idle_and_rejects_commands() =
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
    fun release_is_idempotent() =
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
        const val BELOW_SILENCE = -0.01f
        const val ABOVE_FULL = 1.01f
    }
}
