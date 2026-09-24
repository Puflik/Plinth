package io.github.puflik.plinth.audio.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Управляемая реализация [AudioEngine] для тестов (B1.3).
 *
 * Не воспроизводит звук и не знает об Android: на ней тестируются
 * `PlaybackController` и экраны, которым нужен движок, а не декодер.
 * Всё происходит синхронно в потоке вызывающего — тесты остаются
 * детерминированными, без ожиданий и задержек.
 *
 * Фейк обязан проходить тот же контракт, что и настоящий движок
 * (`FakeAudioEngineTest`): расхождение означало бы, что тесты верхних слоёв
 * доказывают не то, что происходит в приложении.
 */
class FakeAudioEngine : AudioEngine {
    private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private val mutableProgress = MutableStateFlow(PlaybackProgress.NONE)
    override val progress: StateFlow<PlaybackProgress> = mutableProgress.asStateFlow()

    private val mutableEvents = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val events: Flow<PlaybackEvent> = mutableEvents.asSharedFlow()

    private val failing = mutableMapOf<String, PlaybackError>()
    private val prepared = mutableListOf<AudioSource>()
    private val states = mutableListOf<PlaybackState>(PlaybackState.Idle)
    private var hasSource = false

    /** Что и в каком порядке просили подготовить. */
    val preparedSources: List<AudioSource> get() = prepared.toList()

    /**
     * История состояний, включая промежуточные.
     * `StateFlow` конфлейтит, а фейк переключается синхронно — через поток
     * промежуточную [PlaybackState.Buffering] не увидеть.
     */
    val stateHistory: List<PlaybackState> get() = states.toList()

    /** Параметры последнего [prepare]. */
    var lastParams: PlaybackParams? = null
        private set

    var volume: Float = 1f
        private set

    var position: Duration = Duration.ZERO
        private set

    var isReleased: Boolean = false
        private set

    /** Длительность, которую фейк сообщает для любого подготовленного источника. */
    var trackDuration: Duration = DEFAULT_TRACK_DURATION

    /** Объявляет источник недоступным: [prepare] на нём закончится ошибкой. */
    fun markUnavailable(source: AudioSource) =
        markBroken(source, PlaybackError.SourceUnavailable("источник недоступен: ${source.key}"))

    /** [prepare] на [source] закончится ошибкой [error]: битый файл, чужой формат, обрыв сети. */
    fun markBroken(
        source: AudioSource,
        error: PlaybackError,
    ) {
        failing[source.key] = error
    }

    /** Доигрывает текущий трек до конца. */
    fun completeTrack() {
        checkAlive()
        checkSource()
        moveTo(PlaybackState.Ended)
        position = trackDuration
        mutableProgress.value = PlaybackProgress(position, trackDuration)
        emit(PlaybackEvent.TrackEnded)
    }

    /** Прерывает воспроизведение заданной ошибкой. */
    fun failWith(error: PlaybackError) {
        checkAlive()
        fail(error)
    }

    override fun prepare(
        source: AudioSource,
        params: PlaybackParams,
    ) {
        checkAlive()
        prepared += source
        lastParams = params
        position = params.startPosition
        hasSource = false
        moveTo(PlaybackState.Buffering)
        emit(PlaybackEvent.BufferingChanged(buffering = true))
        failing[source.key]?.let {
            fail(it)
            return
        }
        hasSource = true
        mutableProgress.value = PlaybackProgress(position, trackDuration)
        emit(PlaybackEvent.BufferingChanged(buffering = false))
        emit(PlaybackEvent.PositionChanged(position))
        moveTo(if (params.autoPlay) PlaybackState.Playing else PlaybackState.Paused)
    }

    override fun play() {
        checkAlive()
        checkSource()
        moveTo(PlaybackState.Playing)
    }

    override fun pause() {
        checkAlive()
        checkSource()
        moveTo(PlaybackState.Paused)
    }

    override fun seekTo(position: Duration) {
        checkAlive()
        checkSource()
        require(!position.isNegative()) { "позиция не может быть отрицательной: $position" }
        this.position = position
        mutableProgress.value = mutableProgress.value.copy(position = position)
        emit(PlaybackEvent.PositionChanged(position))
    }

    override fun setVolume(volume: Float) {
        checkAlive()
        require(volume in 0f..1f) { "громкость вне диапазона 0..1: $volume" }
        this.volume = volume
    }

    override fun release() {
        if (isReleased) return
        isReleased = true
        hasSource = false
        mutableProgress.value = PlaybackProgress.NONE
        moveTo(PlaybackState.Idle)
    }

    private fun fail(error: PlaybackError) {
        hasSource = false
        mutableProgress.value = PlaybackProgress.NONE
        moveTo(PlaybackState.Error(error))
        emit(PlaybackEvent.Failed(error))
    }

    private fun moveTo(next: PlaybackState) {
        states += next
        mutableState.value = next
    }

    private fun emit(event: PlaybackEvent) {
        check(mutableEvents.tryEmit(event)) { "буфер событий переполнен: $event" }
    }

    private fun checkAlive() = check(!isReleased) { "движок уже освобождён" }

    private fun checkSource() = check(hasSource) { "источник не подготовлен" }

    private companion object {
        const val EVENT_BUFFER = 64
        val DEFAULT_TRACK_DURATION = 3.minutes
    }
}
