package io.github.puflik.plinth.ui.player

import kotlin.math.roundToInt

/**
 * Перетаскивание в панели «Очередь» (E4) — счёт без Compose: строки одной
 * высоты, поэтому место считается по сдвигу пальца, а не по раскладке.
 */
object QueueReorder {
    /**
     * Место, над которым держат строку [from], сдвинутую на [offset] пикселей:
     * соседа она обгоняет, пройдя больше половины его высоты [rowHeight].
     */
    fun target(
        from: Int,
        offset: Float,
        rowHeight: Float,
        count: Int,
    ): Int = (from + (offset / rowHeight).roundToInt()).coerceIn(0, count - 1)

    /**
     * На сколько строк сдвинута строка [index], пока строку [from] держат над
     * местом [target]: строки между ними уступают место на одну.
     */
    fun shift(
        index: Int,
        from: Int,
        target: Int,
    ): Int =
        when {
            from < target && index in from + 1..target -> -1
            from > target && index in target until from -> 1
            else -> 0
        }
}
