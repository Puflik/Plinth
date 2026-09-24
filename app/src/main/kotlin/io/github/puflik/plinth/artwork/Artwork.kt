package io.github.puflik.plinth.artwork

/**
 * Что известно об обложке файла (E2): картинка есть — или её точно нет.
 * «Ещё не знаем» — отсутствие значения, а не третий случай.
 */
sealed interface Artwork<out T> {
    data class Found<T>(
        val image: T,
    ) : Artwork<T>

    data object Missing : Artwork<Nothing>
}

/**
 * Для чего нужна обложка — от этого её размер в пикселях по большей стороне.
 * Двух размеров хватает: картинку меньше экрана не растягивают, а больше — не
 * держат в памяти.
 */
enum class ArtworkSize(
    val pixels: Int,
) {
    /** Карточки альбомов, мини-плеер. */
    THUMBNAIL(pixels = 512),

    /** Полноэкранный плеер. */
    FULL(pixels = 1024),
}

/** Обложка файла [uri] в размере [size] — одна запись кэша. */
data class ArtworkKey(
    val uri: String,
    val size: ArtworkSize,
)

/**
 * Откуда берутся картинки. На Android — встроенная обложка файла
 * (`artwork/embedded`); в тестах — строки.
 */
fun interface ArtworkSource<T : Any> {
    /**
     * Обложка файла [uri] примерно в [size] пикселей по большей стороне:
     * не меньше, если сама картинка не меньше, и не больше чем вдвое.
     *
     * @return `null` — у файла нет обложки.
     * @throws Exception если файл не прочитан: ошибка, в отличие от
     *   отсутствия обложки, не запоминается.
     */
    suspend fun load(
        uri: String,
        size: Int,
    ): T?
}
