package io.github.puflik.plinth.radio

import kotlin.time.Duration.Companion.seconds

/** Меры радио (Ю4в). */
internal object RadioPolicy {
    /** Сколько треков контекста остаётся впереди, когда просят следующую страницу. */
    const val AHEAD = 5

    /** Предел контекста радио: больше файл очереди и каталог не растут. */
    const val LIMIT = 300

    /** Столько страниц подряд из одних повторов — и радио кончилось. */
    const val REPEAT_PAGES = 3

    /** Не чаще раза в это время просить после неудачи. */
    val RETRY_AFTER = 30.seconds
}
