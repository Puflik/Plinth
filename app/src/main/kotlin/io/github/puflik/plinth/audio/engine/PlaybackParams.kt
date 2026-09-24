package io.github.puflik.plinth.audio.engine

import kotlin.time.Duration

/**
 * Параметры подготовки источника (B1.1).
 *
 * Значения по умолчанию описывают обычный случай: встать в начало трека и
 * ждать команды `play`.
 *
 * @property startPosition откуда начать — восстановление позиции после
 *   перезапуска приложения приходит сюда же.
 * @property autoPlay начать воспроизведение сразу, как только источник готов;
 *   иначе движок останавливается в [PlaybackState.Paused].
 * @property gaplessNext следующий трек очереди. Движок вправе подготовить его
 *   заранее, чтобы переход прошёл без паузы. Предыдущий сосед не нужен:
 *   воспроизведение идёт вперёд, а переход назад всегда начинается заново.
 * @property info подписи трека для системы; `null` — всё из тегов файла.
 */
data class PlaybackParams(
    val startPosition: Duration = Duration.ZERO,
    val autoPlay: Boolean = false,
    val gaplessNext: AudioSource? = null,
    val info: TrackInfo? = null,
) {
    init {
        require(!startPosition.isNegative()) { "стартовая позиция не может быть отрицательной: $startPosition" }
    }
}
