package io.github.puflik.plinth.artwork

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Обложка по виду адреса (D3b): путь к файлу — ядру, адрес `content://` — системе. */
class RoutedArtworkSourceTest {
    private val source =
        RoutedArtworkSource(
            paths = { uri, size -> "core $uri $size" },
            uris = { uri, size -> "system $uri $size" },
        )

    @Test
    fun `a file path goes to the core`() =
        runTest {
            assertThat(source.load("/storage/emulated/0/Music/a.mp3", 512))
                .isEqualTo("core /storage/emulated/0/Music/a.mp3 512")
        }

    @Test
    fun `a content uri goes to the system`() =
        runTest {
            assertThat(source.load("content://media/external/audio/media/7", 1024))
                .isEqualTo("system content://media/external/audio/media/7 1024")
        }

    /** `file://` ядро не разбирает: такой адрес приходит от системы, ей и отвечать. */
    @Test
    fun `a file uri goes to the system`() =
        runTest {
            assertThat(source.load("file:///sdcard/a.mp3", 512)).isEqualTo("system file:///sdcard/a.mp3 512")
        }
}
