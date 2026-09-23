package io.github.puflik.plinth.library.scan

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Файлы с тегами из `assets/tags` (рецепт — `tools/make_tag_fixtures.py`),
 * положенные в [FOLDER] через `MediaStore` — так же, как их положил бы любой
 * другой плеер. Находит и читает их системный сканер.
 *
 * Без разрешения на чтение приложение видит в `MediaStore` только свои файлы,
 * поэтому тестам не мешает то, что ещё лежит на устройстве.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class TagFixtures(
    private val resolver: ContentResolver,
) {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val inserted = mutableMapOf<String, Uri>()

    /** Файл и теги, которые в нём записаны. */
    data class Fixture(
        val file: String,
        val title: String,
        val artist: String?,
        val album: String?,
        val albumArtist: String?,
        val discNumber: Int?,
        val trackNumber: Int?,
    )

    fun put(fixture: Fixture) {
        val pending =
            ContentValues().apply {
                put(MediaColumns.DISPLAY_NAME, fixture.file)
                put(MediaColumns.MIME_TYPE, MimeTypeMap.getSingleton().getMimeTypeFromExtension(fixture.extension))
                put(MediaColumns.RELATIVE_PATH, FOLDER)
                put(MediaColumns.IS_PENDING, 1)
            }
        val uri = checkNotNull(resolver.insert(COLLECTION, pending)) { "MediaStore не принял ${fixture.file}" }
        checkNotNull(resolver.openOutputStream(uri)).use { out ->
            assets.open("tags/${fixture.file}").use { it.copyTo(out) }
        }
        // Снятие IS_PENDING публикует файл — и отдаёт его системному сканеру.
        resolver.update(uri, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null)
        inserted[fixture.file] = uri
    }

    fun remove(fixture: Fixture) {
        inserted.remove(fixture.file)?.let { resolver.delete(it, null, null) }
    }

    /** Убирает из папки свои файлы, в том числе оставшиеся от прерванного прогона. */
    fun removeAll() {
        inserted.clear()
        resolver.delete(COLLECTION, "${MediaColumns.RELATIVE_PATH} = ?", arrayOf(FOLDER))
    }

    /**
     * Строки папки, когда сканер прочитал все [expected] файлы: длительность
     * известна только после разбора файла. По истечении срока — что есть,
     * чтобы проверки показали, чего не хватило.
     */
    suspend fun awaitScanned(
        source: ScanSource,
        expected: Int = ALL.size,
    ): List<MediaStoreRow> {
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            val rows = source.rows().filter { it.folder == FOLDER }
            val scanned = rows.size == expected && rows.all { (it.durationMs ?: 0) > 0 }
            if (scanned || start.elapsedNow() > SCAN_TIMEOUT) {
                Log.i(TAG, "Сканер: ${rows.size} из $expected файлов за ${start.elapsedNow()}")
                return rows
            }
            delay(POLL)
        }
    }

    private val Fixture.extension get() = file.substringAfterLast('.')

    companion object {
        const val FOLDER = "Music/PlinthTest/"
        private const val TAG = "PlinthTest"
        private val SCAN_TIMEOUT = 20.seconds
        private val POLL = 200.milliseconds
        private val COLLECTION: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        private const val ALBUM = "Fixtures"
        private const val ALBUM_ARTIST = "Plinth Various"

        val MP3 = Fixture("plinth-mp3.mp3", "Тишина", "Plinth", ALBUM, ALBUM_ARTIST, discNumber = 2, trackNumber = 3)
        val FLAC = Fixture("plinth-flac.flac", "FLAC Silence", "Plinth", ALBUM, ALBUM_ARTIST, 1, 1)
        val M4A = Fixture("plinth-m4a.m4a", "M4A Silence", "The Plinth", ALBUM, ALBUM_ARTIST, 1, 2)

        /**
         * Без тегов. Альбомом `MediaStore` ставит имя папки: отличить это от
         * настоящего тега нельзя — у `Music/Artist/Album/` альбом обычно
         * так и называется, — поэтому v0.1 принимает альбом по папке.
         */
        val UNTAGGED = Fixture("plinth-untagged.mp3", "plinth-untagged", null, "PlinthTest", null, null, null)

        val ALL = listOf(MP3, FLAC, M4A, UNTAGGED)
    }
}
