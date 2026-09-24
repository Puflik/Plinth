package io.github.puflik.plinth.ui.player

import kotlin.math.absoluteValue

/**
 * Направление свайпа (E3, E5): по нему мини-плеер и плеер находят жест в
 * таблице ([PlayerGesture]).
 */
enum class Swipe {
    LEFT,
    RIGHT,
    UP,
    DOWN,
    ;

    /** Вперёд — влево и вверх: следующая карточка приходит справа, панель — снизу. */
    val forward: Boolean get() = this == LEFT || this == UP

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
        ): Swipe? =
            when {
                maxOf(dx.absoluteValue, dy.absoluteValue) < threshold -> null
                dx.absoluteValue >= dy.absoluteValue -> if (dx < 0) LEFT else RIGHT
                else -> if (dy < 0) UP else DOWN
            }
    }
}
