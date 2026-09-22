package io.github.puflik.plinth.audio.engine

/**
 * Текущее состояние движка (B1.2).
 *
 * Состояние отвечает на вопрос «что происходит сейчас» и живёт в
 * [AudioEngine.state]: подписчик всегда получает актуальное значение, даже
 * если подключился позже. Разовые происшествия — в [PlaybackEvent].
 */
sealed interface PlaybackState {
    /** Источник не задан или движок освобождён. */
    data object Idle : PlaybackState

    /** Источник готовится: чтение файла, буферизация потока. */
    data object Buffering : PlaybackState

    /** Звук идёт. */
    data object Playing : PlaybackState

    /** Источник готов, воспроизведение остановлено. */
    data object Paused : PlaybackState

    /** Трек доигран до конца. */
    data object Ended : PlaybackState

    /** Воспроизведение прервано; источник больше не готов. */
    data class Error(
        val error: PlaybackError,
    ) : PlaybackState
}
