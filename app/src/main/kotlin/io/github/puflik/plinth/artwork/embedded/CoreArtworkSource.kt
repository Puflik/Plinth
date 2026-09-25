package io.github.puflik.plinth.artwork.embedded

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.puflik.plinth.artwork.ArtworkSource
import io.github.puflik.plinth.ffi.PlinthCore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Обложка трека фонотеки ядра (D3b) — по пути к файлу. Картинку находит
 * ядро: из тегов, а без неё `cover.jpg` и подобные в папке файла (ответ
 * автора на D3). Здесь она уменьшается при декодировании, как у
 * [EmbeddedArtworkSource]. Миниатюр системы по путям нет: файл мог и не
 * попасть в `MediaStore`.
 *
 * Файл не прочитался — исключение: загрузчик его не запомнит.
 */
class CoreArtworkSource(
    private val core: PlinthCore,
    private val io: CoroutineDispatcher,
) : ArtworkSource<ImageBitmap> {
    override suspend fun load(
        uri: String,
        size: Int,
    ): ImageBitmap? =
        withContext(io) {
            core.library
                .artwork(uri)
                ?.let { EmbeddedArtworkSource.decode(it.data, size) }
                ?.asImageBitmap()
        }
}
