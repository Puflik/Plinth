package io.github.puflik.plinth.artwork.embedded

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.puflik.plinth.core.AppError
import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.ffi.CoreErrors
import io.github.puflik.plinth.ffi.CoreFailure
import io.github.puflik.plinth.ffi.PlinthCore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Обложка трека фонотеки ядра (D3b): по пути к файлу картинку отдаёт ядро —
 * из тегов, а без неё `cover.jpg` рядом; Kotlin уменьшает её при
 * декодировании. Миниатюр системы по путям нет.
 *
 * Обложка фикстуры — красный квадрат 1400×1400 (`tools/make_artwork_fixtures.py`).
 */
class CoreArtworkSourceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val root = File(context.cacheDir, "core-artwork-" + UUID.randomUUID())
    private val errors = CoreErrors()
    private val core = PlinthCore(LogLevel.INFO, File(root, "core"), errors)
    private val source = CoreArtworkSource(core, Dispatchers.IO)

    @After
    fun tearDown() {
        core.close()
        root.deleteRecursively()
    }

    @Test
    fun the_picture_from_tags_is_scaled_down_but_never_below_the_request() =
        runBlocking {
            val file = copy(COVER, "Album/cover-in-tags.mp3")

            val image = checkNotNull(source.load(file.path, SIZE)) { "обложка не найдена" }.asAndroidBitmap()

            assertWithMessage("${image.width}×${image.height}").that(image.longestSide).isAtLeast(SIZE)
            assertThat(image.longestSide).isLessThan(SIZE * 2)
            assertThat(image.pixelAtCentre).isEqualTo(Color.RED)
        }

    @Test
    fun a_small_picture_is_not_enlarged() =
        runBlocking {
            val file = copy(COVER, "Album/cover-in-tags.mp3")

            val image = checkNotNull(source.load(file.path, COVER_SIDE * 2)).asAndroidBitmap()

            assertThat(image.longestSide).isEqualTo(COVER_SIDE)
        }

    /** Ответ автора на D3: у файла без картинки в тегах — `cover.jpg` из его папки. */
    @Test
    fun without_a_picture_in_tags_the_folder_image_is_used() =
        runBlocking {
            val file = copy(UNTAGGED, "Album/untagged.mp3")
            File(file.parentFile, "cover.jpg").outputStream().use { out ->
                Bitmap
                    .createBitmap(FOLDER_SIDE, FOLDER_SIDE, Bitmap.Config.ARGB_8888)
                    .apply { eraseColor(Color.BLUE) }
                    .compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            }

            val image = checkNotNull(source.load(file.path, SIZE)) { "картинка папки не найдена" }.asAndroidBitmap()

            assertThat(image.longestSide).isEqualTo(FOLDER_SIDE)
            assertThat(image.pixelAtCentre).isEqualTo(Color.BLUE)
        }

    @Test
    fun a_file_without_any_picture_gives_nothing() =
        runBlocking {
            val file = copy(UNTAGGED, "Bare/untagged.mp3")

            assertThat(source.load(file.path, SIZE)).isNull()
        }

    /**
     * Файл мог пропасть после скана: это ошибка чтения (загрузчик её не
     * запомнит), но не сбой ядра — человеку о ней не говорят.
     */
    @Test
    fun a_vanished_file_fails_quietly() =
        runBlocking {
            val reported = mutableListOf<AppError>()
            val listening =
                launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { errors.errors.collect(reported::add) }

            assertThrows(CoreFailure::class.java) {
                runBlocking { source.load(File(root, "Gone/gone.mp3").path, SIZE) }
            }

            listening.cancel()
            assertThat(reported).isEmpty()
        }

    private fun copy(
        asset: String,
        path: String,
    ): File =
        File(root, path).apply {
            parentFile?.mkdirs()
            outputStream().use { out ->
                instrumentation.context.assets
                    .open(asset)
                    .use { it.copyTo(out) }
            }
        }

    private val Bitmap.longestSide: Int get() = maxOf(width, height)

    /** Середина картинки — основной цвет с допуском на сжатие JPEG. */
    private val Bitmap.pixelAtCentre: Int
        get() {
            val pixel = getPixel(width / 2, height / 2)
            val channels = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)).map { it > BRIGHT }
            val (red, green, blue) = channels.map { bright -> if (bright) FULL else 0 }
            return Color.rgb(red, green, blue)
        }

    private companion object {
        const val COVER = "artwork/plinth-cover.mp3"
        const val UNTAGGED = "tags/plinth-untagged.mp3"
        const val COVER_SIDE = 1400
        const val FOLDER_SIDE = 300
        const val SIZE = 256
        const val QUALITY = 90
        const val BRIGHT = 128
        const val FULL = 255
    }
}
