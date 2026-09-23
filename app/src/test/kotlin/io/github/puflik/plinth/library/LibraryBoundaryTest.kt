package io.github.puflik.plinth.library

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.SourceTree
import org.junit.Test

/**
 * Страж границы библиотеки (C3.3).
 *
 * Весь эпик C, кроме `LibraryRepository`, в v0.2 заменяется ядром на Rust.
 * Замена не заденет экраны, только если они ходят в библиотеку через фасад:
 * ни Room (`library/db`), ни сканер (`library/scan`) экранам не видны.
 * Фасад, модель и сортировка — чистый Kotlin, как абстракция движка:
 * их же будет звать десктопный клиент.
 */
class LibraryBoundaryTest {
    @Test
    fun `library facade has sources to check`() {
        for (file in FACADE) assertThat(SourceTree.mainFile(file).isFile).isTrue()
    }

    @Test
    fun `facade, model and sorting do not import android`() {
        val files =
            SourceTree.kotlinFiles("library/model") +
                SourceTree.kotlinFiles("library/sort") +
                FACADE.map(SourceTree::mainFile)

        assertThat(SourceTree.importsStartingWith(files, ANDROID_PACKAGES)).isEmpty()
    }

    @Test
    fun `screens reach the library only through the facade`() {
        val offenders = SourceTree.importsStartingWith(SourceTree.kotlinFiles("ui"), LIBRARY_INTERNALS)

        assertThat(offenders).isEmpty()
    }

    private companion object {
        /** Хранилище и управление сканом — всё, что экраны знают о библиотеке. */
        val FACADE = listOf("library/LibraryRepository.kt", "library/LibraryScan.kt", "library/FolderSettings.kt")
        val ANDROID_PACKAGES = listOf("android.", "androidx.", "com.google.android")
        val LIBRARY_INTERNALS = listOf("io.github.puflik.plinth.library.db", "io.github.puflik.plinth.library.scan")
    }
}
