package io.github.puflik.plinth.library

import io.github.puflik.plinth.library.model.LibraryTrack

/**
 * Файлы плейлистов для `FakePlaylistRepository` (D4c) — столько, сколько
 * требует общий контракт: M3U и M3U8 в UTF-8 с `#EXTINF`. Порядок
 * сопоставления тот же, что у ядра: абсолютный путь, путь от папки
 * плейлиста, «исполнитель - название». Кодировки, PLS и `file://` — дело
 * ядра, их проверяют тесты на Rust.
 */
internal object FakeM3u {
    /** Строка файла: путь как записан и название из `#EXTINF`. */
    data class Line(
        val location: String,
        val title: String?,
    )

    fun read(content: ByteArray): List<Line> {
        val text = content.decodeToString().trimStart(Char(BOM))
        val lines = mutableListOf<Line>()
        var title: String? = null
        for (line in text.split('\n', '\r').map(String::trim)) {
            when {
                line.startsWith(EXTINF) -> title = line.substringAfter(',', "").trim().ifEmpty { null }
                line.isEmpty() || line.startsWith('#') -> Unit
                else -> lines += Line(line, title).also { title = null }
            }
        }
        return lines
    }

    /** Трек строки [line] среди [tracks]; не нашёлся — `null`. */
    fun find(
        line: Line,
        tracks: List<LibraryTrack>,
        folder: String?,
    ): LibraryTrack? {
        val path =
            line.location.replace('\\', '/').let { location ->
                if (location.startsWith('/')) location else folder?.let { "${it.trimEnd('/')}/$location" }
            }
        return path?.let { wanted -> tracks.firstOrNull { it.uri.equals(clean(wanted), ignoreCase = true) } }
            ?: line.title?.let { byTitle(it, tracks) }
    }

    fun write(tracks: List<LibraryTrack>): String =
        buildString {
            append("#EXTM3U\n")
            for (track in tracks) {
                val name = track.artist?.takeIf(String::isNotEmpty)?.let { "$it - ${track.title}" } ?: track.title
                append("$EXTINF${track.duration?.inWholeSeconds ?: -1},$name\n${track.uri}\n")
            }
        }

    private fun byTitle(
        title: String,
        tracks: List<LibraryTrack>,
    ): LibraryTrack? {
        val cuts = Regex(" - ").findAll(title).map { it.range }
        val splits = cuts.map { title.substring(0, it.first) to title.substring(it.last + 1) } + ("" to title)
        return splits.firstNotNullOfOrNull { (artist, name) ->
            tracks.firstOrNull { fold(it.artist.orEmpty()) == fold(artist) && fold(it.title) == fold(name) }
        }
    }

    /** Путь без `.`, `..` и пустых частей. */
    private fun clean(path: String): String =
        path
            .split('/')
            .fold(listOf<String>()) { parts, part ->
                when (part) {
                    "", "." -> parts
                    ".." -> parts.dropLast(1)
                    else -> parts + part
                }
            }.joinToString("/", prefix = "/")

    private fun fold(text: String): String =
        text
            .lowercase()
            .filter { it.isLetterOrDigit() || it.isWhitespace() }
            .split(' ')
            .filter(String::isNotEmpty)
            .joinToString(" ")

    private const val EXTINF = "#EXTINF:"

    /** Метка порядка байтов UTF-8 в начале файла. */
    private const val BOM = 0xFEFF
}
