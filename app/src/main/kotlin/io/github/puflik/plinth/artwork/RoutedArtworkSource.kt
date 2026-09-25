package io.github.puflik.plinth.artwork

/**
 * Обложки по виду адреса (D3b). Путь к файлу (`/storage/…`) — у треков
 * фонотеки ядра: картинку из тегов или `cover.jpg` рядом отдаёт ядро.
 * Остальное — `content://` от SAF, «Открыть файл» и очереди, сохранённой
 * до 0.2, — разбирает система.
 */
class RoutedArtworkSource<T : Any>(
    private val paths: ArtworkSource<T>,
    private val uris: ArtworkSource<T>,
) : ArtworkSource<T> {
    override suspend fun load(
        uri: String,
        size: Int,
    ): T? = if (uri.startsWith('/')) paths.load(uri, size) else uris.load(uri, size)
}
