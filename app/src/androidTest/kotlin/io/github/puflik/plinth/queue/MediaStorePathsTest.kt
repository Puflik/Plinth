package io.github.puflik.plinth.queue

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Ревью v0.2, №26 (Р1.11в): путь файла по адресу `content://media/…`, как его назвала очередь 0.1.x.
 * Файл кладётся через `MediaStore`, как его положил бы любой другой плеер; своё приложение видит
 * его и без разрешения на музыку.
 */
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q) // MediaStore.RELATIVE_PATH — с Android 10.
class MediaStorePathsTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val paths = MediaStorePaths(resolver, Dispatchers.IO)
    private val name = "probe-${UUID.randomUUID()}.mp3"
    private lateinit var probe: Uri

    @Before
    fun setUp() {
        removeProbe()
        probe = putProbe()
    }

    @After
    fun tearDown() {
        removeProbe()
    }

    @Test
    fun the_path_of_a_file_comes_back_by_its_address_and_other_addresses_do_not() =
        runBlocking {
            val answer = paths.pathsOf(setOf(probe.toString(), UNKNOWN, DOCUMENT, "/data/x.mp3"))

            assertThat(answer.keys).containsExactly(probe.toString())
            assertThat(answer.getValue(probe.toString())).endsWith("/$FOLDER$name")
        }

    /** 0.1.x брала адреса у тома `external`, а файл мог быть положен в `external_primary`: номер строки один. */
    @Test
    fun the_volume_in_the_address_does_not_matter() =
        runBlocking {
            val id = ContentUris.parseId(probe)
            val addresses = listOf("external", "external_primary").map { "content://media/$it/audio/media/$id" }

            val answer = paths.pathsOf(addresses.toSet())

            assertThat(answer.keys).containsExactlyElementsIn(addresses)
            assertThat(answer.values.toSet()).hasSize(1)
        }

    @Test
    fun nothing_to_ask_is_an_empty_answer() =
        runBlocking {
            val unsuitable = setOf(DOCUMENT, "/data/x.mp3", "content://media/external/audio/media/x")

            assertThat(paths.pathsOf(emptySet())).isEmpty()
            assertThat(paths.pathsOf(unsuitable)).isEmpty()
        }

    private fun putProbe(): Uri {
        val pending =
            ContentValues().apply {
                put(MediaColumns.DISPLAY_NAME, name)
                put(MediaColumns.MIME_TYPE, "audio/mpeg")
                put(MediaColumns.RELATIVE_PATH, FOLDER)
                put(MediaColumns.IS_PENDING, 1)
            }
        val uri = checkNotNull(resolver.insert(collection(), pending)) { "MediaStore не принял файл" }
        checkNotNull(resolver.openOutputStream(uri)).use { it.write(ByteArray(BYTES)) }
        resolver.update(uri, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun removeProbe() {
        resolver.delete(collection(), "${MediaColumns.RELATIVE_PATH} = ?", arrayOf(FOLDER))
    }

    private fun collection() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private companion object {
        const val FOLDER = "Music/Plinth-r1-11c-test/"
        const val BYTES = 16

        /** Номер, которого в `MediaStore` нет. */
        const val UNKNOWN = "content://media/external/audio/media/2147483000"

        /** Адрес другого провайдера: SAF, не `MediaStore`. */
        const val DOCUMENT = "content://com.android.providers.media.documents/document/audio%3A1"
    }
}
