package io.github.puflik.plinth.library.scan

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Файлы с тегами из `assets/tags` (рецепт — `tools/make_tag_fixtures.py`),
 * положенные в [FOLDER] через `MediaStore` — так же, как их положил бы любой
 * другой плеер. Находит и читает их скан ядра — по прямым путям (D3c).
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

    private val Fixture.extension get() = file.substringAfterLast('.')

    companion object {
        const val FOLDER = "Music/PlinthTest/"
        private val COLLECTION: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        private const val ALBUM = "Fixtures"
        private const val ALBUM_ARTIST = "Plinth Various"

        val MP3 = Fixture("plinth-mp3.mp3", "Тишина", "Plinth", ALBUM, ALBUM_ARTIST, discNumber = 2, trackNumber = 3)
        val FLAC = Fixture("plinth-flac.flac", "FLAC Silence", "Plinth", ALBUM, ALBUM_ARTIST, 1, 1)
        val M4A = Fixture("plinth-m4a.m4a", "M4A Silence", "The Plinth", ALBUM, ALBUM_ARTIST, 1, 2)

        /** Без тегов: название — имя файла, альбома нет — имя папки альбомом не становится (ADR 0005). */
        val UNTAGGED = Fixture("plinth-untagged.mp3", "plinth-untagged", null, null, null, null, null)

        val ALL = listOf(MP3, FLAC, M4A, UNTAGGED)
    }
}
