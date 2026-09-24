package io.github.puflik.plinth.ui.player

import kotlin.math.absoluteValue

/**
 * Свайп по мини-плееру (E3; 12.10, 12.11): вбок — смена трека, как у
 * обложки в плеере, вверх — сам плеер. Вниз мини-плееру уходить некуда.
 */
enum class MiniPlayerSwipe {
    /** Влево: следующая карточка приходит справа. */
    NEXT,

    /** Вправо. */
    PREVIOUS,

    /** Вверх — открыть плеер. */
    EXPAND,
    ;

    companion object {
        /**
         * Свайп по сдвигу пальца [dx], [dy] за всё касание. Направление решает
         * большая составляющая: чуть косой свайп вбок остаётся свайпом вбок.
         * Сдвиг короче [threshold] — не свайп, а неточное касание.
         */
        fun of(
            dx: Float,
            dy: Float,
            threshold: Float,
        ): MiniPlayerSwipe? =
            when {
                dx.absoluteValue >= dy.absoluteValue && dx.absoluteValue >= threshold ->
                    if (dx < 0) NEXT else PREVIOUS
                dy <= -threshold -> EXPAND
                else -> null
            }
    }
}
