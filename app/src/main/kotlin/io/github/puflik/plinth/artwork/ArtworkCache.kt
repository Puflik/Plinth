package io.github.puflik.plinth.artwork

/**
 * Обложки в памяти (E2): давно не нужные уходят первыми, когда картинки
 * перестают помещаться в [maxBytes].
 *
 * Помнит и то, что обложки у файла нет, — иначе каждая прокрутка списка
 * заново разбирала бы файлы без картинок. Такая запись места не занимает.
 * Картинка больше всего бюджета не хранится вовсе: ради неё пришлось бы
 * выбросить все остальные.
 *
 * `android.util.LruCache` не подходит: ядро обложек — чистый Kotlin и
 * проверяется на JVM.
 */
class ArtworkCache<T : Any>(
    private val maxBytes: Long,
    private val sizeOf: (T) -> Int,
) {
    // accessOrder = true: чтение переносит запись в конец, начало — самое давнее.
    private val entries = LinkedHashMap<ArtworkKey, Artwork<T>>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var bytes = 0L

    /** `null` — об обложке ещё ничего не известно. */
    @Synchronized
    operator fun get(key: ArtworkKey): Artwork<T>? = entries[key]

    /** Запоминает картинку или, если [image] — `null`, что её нет. */
    @Synchronized
    fun put(
        key: ArtworkKey,
        image: T?,
    ) {
        entries.remove(key)?.let { bytes -= it.bytes }
        val artwork = if (image == null) Artwork.Missing else Artwork.Found(image)
        if (artwork.bytes > maxBytes) return
        entries[key] = artwork
        bytes += artwork.bytes
        trim()
    }

    private fun trim() {
        val eldest = entries.iterator()
        while (bytes > maxBytes && eldest.hasNext()) {
            bytes -= eldest.next().value.bytes
            eldest.remove()
        }
    }

    private val Artwork<T>.bytes: Long
        get() =
            when (this) {
                is Artwork.Found -> sizeOf(image).toLong()
                Artwork.Missing -> 0L
            }

    private companion object {
        const val INITIAL_CAPACITY = 64
        const val LOAD_FACTOR = 0.75f
    }
}
