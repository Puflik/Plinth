package io.github.puflik.plinth.artwork.embedded

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import io.github.puflik.plinth.artwork.ArtworkSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Встроенная обложка аудиофайла (E2) — средствами системы, без библиотек
 * картинок.
 *
 * Путей два:
 * - [thumbnail] (Android 10+) — миниатюра системы: для файла из `MediaStore`
 *   система сама достаёт картинку из тегов, а без неё ищет `cover.jpg` и
 *   подобные рядом с файлом, и держит готовую миниатюру у себя. Но не больше
 *   половины экрана: на 1080×2400 обложка 1400 px приходит как 700 px и на
 *   запрос 512, и на запрос 1024. У документов SAF миниатюры обычно нет;
 * - [embedded] — картинка из тегов через `MediaMetadataRetriever`,
 *   уменьшенная при декодировании. Единственный путь на Android 8–9.
 *
 * Маленькую картинку быстрее взять у системы, большую — честнее из тегов:
 * до [SYSTEM_THUMBNAIL_MAX] пикселей первой идёт миниатюра, больше — теги.
 * Второй путь — запасной: теги найдут обложку документа SAF, миниатюра —
 * `cover.jpg` рядом с файлом без картинки в тегах.
 */
class EmbeddedArtworkSource(
    private val context: Context,
    private val io: CoroutineDispatcher,
) : ArtworkSource<ImageBitmap> {
    override suspend fun load(
        uri: String,
        size: Int,
    ): ImageBitmap? =
        withContext(io) {
            val file = uri.toUri()
            val bitmap =
                when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> embedded(file, size)
                    size <= SYSTEM_THUMBNAIL_MAX -> thumbnail(file, size) ?: embedded(file, size)
                    else -> embedded(file, size) ?: thumbnail(file, size)
                }
            bitmap?.asImageBitmap()
        }

    /** Миниатюра от системы; `null` — у файла её нет. Отмена корутины прерывает чтение. */
    @RequiresApi(Build.VERSION_CODES.Q)
    internal suspend fun thumbnail(
        uri: Uri,
        size: Int,
    ): Bitmap? {
        val signal = CancellationSignal()
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { signal.cancel() }
        return try {
            context.contentResolver.loadThumbnail(uri, Size(size, size), signal)
        } catch (_: IOException) {
            // Так система отвечает «миниатюры нет»: FileNotFoundException.
            null
        } catch (_: UnsupportedOperationException) {
            // Провайдер документов, который миниатюр не умеет вовсе.
            null
        } finally {
            cancellation.dispose()
        }
    }

    /** Картинка из тегов файла; `null` — её там нет. */
    internal fun embedded(
        uri: Uri,
        size: Int,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        val picture =
            try {
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture
            } finally {
                retriever.release()
            }
        return picture?.let { decode(it, size) }
    }

    companion object {
        /** До какого размера миниатюра системы не хуже картинки из тегов: половина узкого экрана. */
        const val SYSTEM_THUMBNAIL_MAX = 512

        /**
         * Байты картинки → `Bitmap`, уменьшенный степенью двойки так, чтобы
         * большая сторона осталась не меньше [size] (если картинка не меньше)
         * и была меньше вдвое большей. Так же уменьшает и система.
         */
        internal fun decode(
            bytes: ByteArray,
            size: Int,
        ): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null
            var sample = 1
            while (longest / (sample * 2) >= size) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }

        /** Сколько памяти занимает картинка — для бюджета кэша. */
        fun bytesOf(image: ImageBitmap): Int = image.asAndroidBitmap().allocationByteCount
    }
}
