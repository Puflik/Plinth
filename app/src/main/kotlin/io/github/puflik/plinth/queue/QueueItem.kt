package io.github.puflik.plinth.queue

import io.github.puflik.plinth.audio.engine.AudioSource
import kotlin.time.Duration

/**
 * Элемент очереди (D1.1): что играть и что об этом показать.
 *
 * Библиотеку очередь не знает — трек из неё и файл из SAF становятся
 * одинаковыми элементами. Ручное это добавление или трек контекста, решает
 * место в [PlaybackQueue], а не сам элемент.
 *
 * @property albumOwner чей альбом (исполнитель альбома, иначе трека): вместе
 *   с [album] он узнаёт альбом, к которому ведёт плеер (E5). У сборника
 *   исполнитель трека и владелец альбома — разные люди.
 */
data class QueueItem(
    val source: AudioSource,
    val title: String?,
    val artist: String? = null,
    val album: String? = null,
    val duration: Duration? = null,
    val albumOwner: String? = null,
)
