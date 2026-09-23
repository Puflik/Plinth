package io.github.puflik.plinth.audio

import io.github.puflik.plinth.audio.engine.AudioEngine
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.PlaybackEvent
import io.github.puflik.plinth.audio.engine.PlaybackParams
import io.github.puflik.plinth.audio.engine.PlaybackProgress
import io.github.puflik.plinth.audio.engine.PlaybackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * Фасад воспроизведения для UI (B2–B4) — единственная точка, через которую
 * экраны управляют звуком.
 *
 * Движок строг: команда без источника — исключение. Экрану такая строгость
 * ни к чему — кнопка, нажатая до выбора файла, не должна ронять приложение.
 * Фасад переводит намерения пользователя («переключить», «перемотать») в
 * команды, допустимые в текущем состоянии, а недопустимые молча пропускает.
 * Очередь (эпик D) встанет сюда же.
 */
@Singleton
class PlaybackController
    @Inject
    constructor(
        private val engine: AudioEngine,
    ) {
        val state: StateFlow<PlaybackState> get() = engine.state
        val progress: StateFlow<PlaybackProgress> get() = engine.progress
        val events: Flow<PlaybackEvent> get() = engine.events

        /** Открывает источник и сразу играет: файл выбирают, чтобы слушать. */
        fun open(source: AudioSource) {
            engine.prepare(source, PlaybackParams(autoPlay = true))
        }

        /** Кнопка play/pause; после конца трека играет его заново. */
        fun togglePlayPause() {
            when (engine.state.value) {
                PlaybackState.Playing, PlaybackState.Buffering -> engine.pause()
                PlaybackState.Paused, PlaybackState.Ended -> engine.play()
                PlaybackState.Idle, is PlaybackState.Error -> Unit
            }
        }

        /** Перемотка; без открытого трека ничего не делает. */
        fun seekTo(position: Duration) {
            if (engine.state.value.hasSource) engine.seekTo(position.coerceAtLeast(Duration.ZERO))
        }

        private val PlaybackState.hasSource: Boolean
            get() = this != PlaybackState.Idle && this !is PlaybackState.Error
    }
