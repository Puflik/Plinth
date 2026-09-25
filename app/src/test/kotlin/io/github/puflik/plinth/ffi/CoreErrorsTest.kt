package io.github.puflik.plinth.ffi

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.core.AppError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Отказы и события ядра для экранов (A3.3, D3). */
class CoreErrorsTest {
    /** Ядро открывается при старте, раньше экранов: о восстановлении экран узнает, когда начнёт слушать. */
    @Test
    fun `a restored library waits for the first listener`() =
        runTest {
            val errors = CoreErrors()

            errors.libraryRestored()

            assertThat(errors.events.first()).isEqualTo(AppError.LibraryRestored)
        }
}
