package io.github.puflik.plinth.audio.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * Воспроизведение звука (B1.1 📌).
 *
 * Это граница, за которой начинается конкретный движок. Ни один тип из
 * `androidx.media3` не пересекает её: `Media3Engine` знает об интерфейсе,
 * интерфейс о Media3 — никогда. Правило сторожит `EngineBoundaryTest`, а
 * держится ради двух вещей: десктопного клиента, где Media3 нет, и движка на
 * Rust, который в v0.2+ встанет на то же место.
 *
 * Команды не приостанавливаются: движок принимает их и отвечает изменением
 * [state] и потоком [events]. Ответ асинхронный, потому что настоящий плеер
 * живёт в своём потоке.
 *
 * Требования к любой реализации описаны в `AudioEngineContractTest` (B1.4) —
 * контракт в коде, а не в комментарии. Если реализация его не проходит,
 * она не реализует `AudioEngine`.
 */
interface AudioEngine {
    /** Что происходит сейчас; новый подписчик сразу получает текущее значение. */
    val state: StateFlow<PlaybackState>

    /** Происшествия; приходят только тем, кто подписан в момент события. */
    val events: Flow<PlaybackEvent>

    /**
     * Берёт источник в работу. Прежний источник заменяется.
     *
     * @throws IllegalStateException если движок освобождён.
     */
    fun prepare(
        source: AudioSource,
        params: PlaybackParams = PlaybackParams(),
    )

    /**
     * Начинает или продолжает воспроизведение.
     *
     * @throws IllegalStateException если источник не подготовлен.
     */
    fun play()

    /**
     * Останавливает воспроизведение, сохраняя позицию.
     *
     * @throws IllegalStateException если источник не подготовлен.
     */
    fun pause()

    /**
     * Перематывает к указанной позиции.
     *
     * @throws IllegalStateException если источник не подготовлен.
     * @throws IllegalArgumentException если позиция отрицательная.
     */
    fun seekTo(position: Duration)

    /**
     * Задаёт громкость самого движка (не системную), `0f`..`1f`.
     *
     * @throws IllegalArgumentException если значение вне диапазона.
     * @throws IllegalStateException если движок освобождён.
     */
    fun setVolume(volume: Float)

    /**
     * Освобождает ресурсы. После этого движок возвращается в
     * [PlaybackState.Idle] и отказывается выполнять команды.
     * Повторный вызов ничего не делает.
     */
    fun release()
}
