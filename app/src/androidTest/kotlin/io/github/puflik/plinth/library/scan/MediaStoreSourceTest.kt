package io.github.puflik.plinth.library.scan

import android.os.Build
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * `MediaStoreSource` видит файлы, найденные системным сканером, с тегами
 * каждого формата (C2.1, C2.2). Проверяется связка «сканер Android →
 * `MediaStoreSource` → `TagReader`»: что из тегов доходит до трека.
 */
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class MediaStoreSourceTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val fixtures = TagFixtures(resolver)
    private val source = MediaStoreSource(resolver, Dispatchers.IO)

    @Before
    fun putFixtures() {
        fixtures.removeAll()
        TagFixtures.ALL.forEach(fixtures::put)
    }

    @After
    fun removeFixtures() {
        fixtures.removeAll()
    }

    @Test
    fun system_scanner_finds_files_in_their_folder() =
        runBlocking {
            val rows = fixtures.awaitScanned(source)

            assertThat(rows.map(MediaStoreRow::displayName)).containsExactlyElementsIn(TagFixtures.ALL.map { it.file })
            assertThat(rows.map(MediaStoreRow::uri)).containsNoDuplicates()
        }

    @Test
    fun tags_of_every_format_reach_the_track() =
        runBlocking {
            val tracks = fixtures.awaitScanned(source).associateBy(MediaStoreRow::displayName, TagReader::read)

            for (expected in TagFixtures.ALL) {
                val track = checkNotNull(tracks[expected.file]) { "сканер не нашёл ${expected.file}" }
                val actual =
                    TagFixtures.Fixture(
                        file = expected.file,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        albumArtist = track.albumArtist,
                        discNumber = track.discNumber,
                        trackNumber = track.trackNumber,
                    )
                assertThat(actual).isEqualTo(expected)
            }
        }

    @Test
    fun duration_and_modification_time_are_read() =
        runBlocking {
            val now = System.currentTimeMillis() / MILLIS_PER_SECOND

            for (track in fixtures.awaitScanned(source).map(TagReader::read)) {
                val length = "${track.title}: ${track.duration}"
                assertWithMessage(length).that(track.duration in SECOND_OF_SILENCE).isTrue()
                assertWithMessage(track.title).that(track.modifiedAt).isAtLeast(now - RECENT)
                assertWithMessage(track.title).that(track.modifiedAt).isAtMost(now + RECENT)
            }
        }

    private companion object {
        const val MILLIS_PER_SECOND = 1000
        const val RECENT = 120L

        /** Секунда тишины; кодеры с задержкой (AAC, MP3) добавляют или съедают несколько кадров. */
        val SECOND_OF_SILENCE = 900.milliseconds..1_200.milliseconds
    }
}
