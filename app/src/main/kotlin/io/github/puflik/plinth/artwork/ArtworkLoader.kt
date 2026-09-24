package io.github.puflik.plinth.artwork

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Обложки для экранов (E2) — своими силами, без библиотек картинок: каждый
 * файл разбирается один раз, дальше картинка (или её отсутствие) берётся из
 * [cache].
 *
 * Разбирается не больше [parallelism] файлов сразу: пролистанная сетка
 * альбомов не должна разом поднять в память десяток больших картинок.
 * Запрос, отменённый в очереди (карточка ушла с экрана), файл не читает.
 * Ошибка чтения не запоминается — файл мог быть временно недоступен.
 */
class ArtworkLoader<T : Any>(
    private val source: ArtworkSource<T>,
    private val cache: ArtworkCache<T>,
    parallelism: Int = DEFAULT_PARALLELISM,
) {
    private val permits = Semaphore(parallelism)

    /** Уже загруженная картинка — для первого кадра, без ожидания; иначе `null`. */
    fun peek(
        uri: String,
        size: ArtworkSize,
    ): T? = (cache[ArtworkKey(uri, size)] as? Artwork.Found)?.image

    /** Обложка файла [uri]; `null` — её нет или файл не прочитался. */
    suspend fun load(
        uri: String,
        size: ArtworkSize,
    ): T? {
        val key = ArtworkKey(uri, size)
        cache[key]?.let { return it.imageOrNull }
        return permits.withPermit {
            // Пока ждали очереди, тот же файл мог прочитать другой запрос.
            cache[key]?.let { return@withPermit it.imageOrNull }
            val image =
                try {
                    source.load(uri, size.pixels)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (
                    @Suppress("TooGenericExceptionCaught") expected: Exception,
                ) {
                    // Источник на Android бросает что угодно — от SecurityException до
                    // RuntimeException разборщика; экрану это одинаково «нет обложки».
                    return@withPermit null
                }
            cache.put(key, image)
            image
        }
    }

    private val Artwork<T>.imageOrNull: T?
        get() = (this as? Artwork.Found)?.image

    companion object {
        /** Сколько файлов разбирается сразу. */
        const val DEFAULT_PARALLELISM = 3
    }
}
