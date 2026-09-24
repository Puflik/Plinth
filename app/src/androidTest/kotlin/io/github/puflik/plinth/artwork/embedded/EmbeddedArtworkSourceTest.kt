package io.github.puflik.plinth.artwork.embedded

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Встроенная обложка доходит до картинки (E2): файл из `MediaStore` — через
 * миниатюру системы, файл без обложки — `null`, а путь Android 8–9
 * (`MediaMetadataRetriever`) проверяется напрямую на копии в кэше.
 *
 * Обложка фикстуры — красный квадрат 1400×1400 (`tools/make_artwork_fixtures.py`).
 */
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class EmbeddedArtworkSourceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val source = EmbeddedArtworkSource(context, Dispatchers.IO)

    @Before
    fun clean() = removeAll()

    @After
    fun cleanUp() = removeAll()

    @Test
    fun media_store_file_gives_its_embedded_cover() =
        runBlocking {
            val uri = put(COVER)

            val image = checkNotNull(source.load(uri.toString(), SIZE)) { "обложка не найдена" }.asAndroidBitmap()

            Log.i(TAG, "Миниатюра системы: ${image.width}×${image.height} на запрос $SIZE")
            assertThat(image.longestSide).isLessThan(SIZE * 2)
            assertThat(image.isRed).isTrue()
        }

    /** Миниатюра системы — не больше половины экрана; плееру нужна картинка из тегов. */
    @Test
    fun large_request_reads_the_picture_from_tags() =
        runBlocking {
            val uri = put(COVER)

            val image = checkNotNull(source.load(uri.toString(), LARGE)) { "обложка не найдена" }.asAndroidBitmap()

            assertWithMessage("${image.width}×${image.height}").that(image.longestSide).isAtLeast(LARGE)
            assertThat(image.isRed).isTrue()
        }

    @Test
    fun file_without_cover_gives_nothing() =
        runBlocking {
            val uri = put(UNTAGGED)

            assertThat(source.load(uri.toString(), SIZE)).isNull()
        }

    @Test
    fun embedded_picture_is_scaled_down_but_never_below_the_request() {
        val file = copyToCache(COVER)

        val image = checkNotNull(source.embedded(Uri.fromFile(file), SIZE)) { "картинка из тегов не прочитана" }

        assertWithMessage("${image.width}×${image.height}").that(image.longestSide).isAtLeast(SIZE)
        assertThat(image.longestSide).isLessThan(SIZE * 2)
        assertThat(image.isRed).isTrue()
    }

    @Test
    fun small_embedded_picture_is_not_enlarged() {
        val file = copyToCache(COVER)

        val image = checkNotNull(source.embedded(Uri.fromFile(file), COVER_SIDE * 2))

        assertThat(image.longestSide).isEqualTo(COVER_SIDE)
    }

    @Test
    fun embedded_path_finds_nothing_in_a_file_without_cover() {
        val file = copyToCache(UNTAGGED)

        assertThat(source.embedded(Uri.fromFile(file), SIZE)).isNull()
    }

    /** Кладёт фикстуру в [FOLDER] через `MediaStore` — как любой другой плеер. */
    private fun put(asset: String): Uri {
        val values =
            ContentValues().apply {
                put(MediaColumns.DISPLAY_NAME, asset.substringAfterLast('/'))
                put(MediaColumns.MIME_TYPE, "audio/mpeg")
                put(MediaColumns.RELATIVE_PATH, FOLDER)
                put(MediaColumns.IS_PENDING, 1)
            }
        val uri = checkNotNull(resolver.insert(COLLECTION, values)) { "MediaStore не принял $asset" }
        checkNotNull(resolver.openOutputStream(uri)).use { out ->
            instrumentation.context.assets
                .open(asset)
                .use { it.copyTo(out) }
        }
        resolver.update(uri, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun copyToCache(asset: String): File =
        File(context.cacheDir, asset.substringAfterLast('/')).apply {
            outputStream().use { out ->
                instrumentation.context.assets
                    .open(asset)
                    .use { it.copyTo(out) }
            }
        }

    private fun removeAll() {
        resolver.delete(COLLECTION, "${MediaColumns.RELATIVE_PATH} = ?", arrayOf(FOLDER))
    }

    private val Bitmap.longestSide: Int get() = maxOf(width, height)

    /** Середина картинки красная — с допуском на сжатие JPEG. */
    private val Bitmap.isRed: Boolean
        get() {
            val pixel = getPixel(width / 2, height / 2)
            return Color.red(pixel) > BRIGHT && Color.green(pixel) < DARK && Color.blue(pixel) < DARK
        }

    private companion object {
        const val TAG = "PlinthTest"
        const val FOLDER = "Music/PlinthArtworkTest/"
        const val COVER = "artwork/plinth-cover.mp3"
        const val UNTAGGED = "tags/plinth-untagged.mp3"
        const val COVER_SIDE = 1400
        const val SIZE = 256
        const val LARGE = 1024
        const val BRIGHT = 200
        const val DARK = 60
        val COLLECTION: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
}
