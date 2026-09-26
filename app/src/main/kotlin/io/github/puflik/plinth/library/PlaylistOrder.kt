package io.github.puflik.plinth.library

import io.github.puflik.plinth.ffi.PlaylistEntryId

/**
 * Перестановка на экране — в индекс ядра (D4b). Экран видит только треки,
 * которые можно сыграть, а ядро (`move_in_playlist`) ставит запись на место
 * среди всех записей плейлиста, скрытых тоже. Взять индекс экрана как есть —
 * запись встанет не туда, если выше неё скрыт пропавший файл.
 */
object PlaylistOrder {
    /**
     * Индекс среди всех записей [all], с которым запись [entry] встанет на
     * место [to] среди видимых [visible]. Запись встаёт рядом с той, что
     * сейчас стоит на месте [to]: вниз — сразу после неё, вверх — сразу перед
     * ней. Скрытые записи остаются между прежними соседями.
     *
     * `null` — переставлять нечего: записи не видно или она уже на месте.
     */
    fun indexAmongAll(
        visible: List<PlaylistEntryId>,
        all: List<PlaylistEntryId>,
        entry: PlaylistEntryId,
        to: Int,
    ): Int? {
        val from = visible.indexOf(entry)
        if (from < 0) return null
        val target = to.coerceIn(0, visible.lastIndex)
        val neighbour = (all - entry).indexOf(visible[target])
        return when {
            target == from || neighbour < 0 || entry !in all -> null
            target > from -> neighbour + 1
            else -> neighbour
        }
    }
}
