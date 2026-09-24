package io.github.puflik.plinth.ffi

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.SourceTree
import org.junit.Test
import java.io.File

/**
 * Сгенерированные биндинги ядра видит только пакет `ffi` (ADR 0010): типы
 * UniFFI и JNA не расходятся по приложению, и замена генератора или смена
 * API ядра правится в одном месте.
 */
class FfiBoundaryTest {
    @Test
    fun `bridge package has sources to check`() {
        val files = SourceTree.kotlinFiles(FFI_PACKAGE).map(File::getName)

        assertThat(files).containsAtLeast("PlinthCore.kt", "CoreLogBridge.kt")
    }

    @Test
    fun `generated bindings are imported only inside the bridge package`() {
        val ffiDir = SourceTree.mainFile(FFI_PACKAGE)
        val outside = SourceTree.kotlinFiles("").filterNot { it.startsWith(ffiDir) }

        val offenders = SourceTree.importsStartingWith(outside, listOf(GENERATED_PACKAGE, "com.sun.jna"))

        assertThat(offenders).isEmpty()
    }

    private companion object {
        const val FFI_PACKAGE = "ffi"
        const val GENERATED_PACKAGE = "io.github.puflik.plinth.ffi.generated"
    }
}
