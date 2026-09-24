package io.github.puflik.plinth.startup

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Когда последний раз что-то играло (F3): по этому «Авто» решает, продолжать
 * ли с того же места. Пишет время `QueueKeeper`; в v0.2 история уйдёт в ядро.
 */
interface PlayHistory {
    /**
     * Время последней игры; `null` — ещё ни разу. Молчит, пока сохранённое не
     * прочитано: иначе запуск принял бы «ещё не знаю» за «никогда».
     */
    val lastPlayed: Flow<Instant?>
}
