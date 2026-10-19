package io.github.puflik.plinth.online.youtube

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.SourceTree
import org.junit.Test
import java.io.File

/**
 * Экстрактор YouTube виден только пакету `online/youtube` (Ю3б): остальное приложение и ядро видят `YoutubeSource` и
 * его простые типы; замена экстрактора, его версия и его сбои — правка в одном месте. Сам пакет от ядра не зависит:
 * `ffi` реализует его интерфейсы, а не наоборот.
 */
class YoutubeBoundaryTest {
    @Test
    fun `the package has the sources to check`() {
        val names = SourceTree.kotlinFiles(PACKAGE).map(File::getName)

        assertThat(names)
            .containsAtLeast(
                "YoutubeSource.kt",
                "HttpStatusTable.kt",
                "NewPipeYoutubeSource.kt",
                "YoutubeDownloader.kt",
            )
    }

    @Test
    fun `the extractor is imported only inside the youtube package`() {
        val youtubeDir = SourceTree.mainFile("online/youtube")
        val outside = SourceTree.kotlinFiles("").filterNot { it.startsWith(youtubeDir) }

        val offenders = SourceTree.importsStartingWith(outside, listOf("org.schabi"))

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the interface and its types do not show the extractor`() {
        val api =
            SourceTree.kotlinFiles(PACKAGE).filter {
                it.name == "YoutubeSource.kt" ||
                    it.name == "HttpStatusTable.kt"
            }

        assertThat(api).hasSize(2)
        assertThat(SourceTree.importsStartingWith(api, listOf("org.schabi"))).isEmpty()
        assertThat(api.flatMap { it.readLines() }.filter { "org.schabi" in it && !it.trim().startsWith("*") }).isEmpty()
    }

    @Test
    fun `the youtube package does not depend on the core bridge`() {
        val offenders =
            SourceTree.importsStartingWith(
                SourceTree.kotlinFiles(PACKAGE),
                listOf("io.github.puflik.plinth.ffi"),
            )

        assertThat(offenders).isEmpty()
    }

    private companion object {
        const val PACKAGE = "online/youtube"
    }
}
