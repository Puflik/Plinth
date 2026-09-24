package io.github.puflik.plinth.ffi

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import io.github.puflik.plinth.di.CoreEntryPoint
import io.github.puflik.plinth.ffi.generated.CoreException
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Пустое сквозное соединение Kotlin → Rust (DoD эпика A): настоящая
 * libplinth_ffi.so под ABI устройства, настоящий логгер приложения.
 */
class PlinthCoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val core = EntryPointAccessors.fromApplication<CoreEntryPoint>(context).plinthCore()

    @Test
    fun string_goes_to_rust_and_comes_back() {
        val greeting = core.hello("Котлин")

        assertThat(greeting).startsWith("Hello, Котлин! Plinth core ")
    }

    @Test
    fun panic_in_core_is_a_kotlin_exception() {
        val error = assertThrows(CoreException.Internal::class.java) { core.panicForTest("on purpose") }

        assertThat(error.message).startsWith("internal: panicked at ")
        assertThat(error.message).endsWith(": on purpose")
    }

    @Test
    fun core_keeps_working_after_a_panic() {
        assertThrows(CoreException.Internal::class.java) { core.panicForTest("first") }

        assertThat(core.hello("again")).startsWith("Hello, again!")
    }

    @Test
    fun rust_log_reaches_the_app_log_file() {
        val marker = "marker-" + UUID.randomUUID().toString().take(MARKER_LENGTH)

        assertThrows(CoreException.Internal::class.java) { core.panicForTest(marker) }

        // Файл пишется в своём потоке приложения — ждём, а не читаем сразу.
        val log = File(context.filesDir, "logs/plinth.log")
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        var line: String? = null
        while (line == null && System.currentTimeMillis() < deadline) {
            line = log.takeIf(File::exists)?.readLines()?.lastOrNull { marker in it }
            if (line == null) Thread.sleep(POLL_MILLIS)
        }
        assertThat(line).isNotNull()
        assertThat(line).contains(" E plinth_ffi::panic: panicked at ")
    }

    private companion object {
        // Короче 32 знаков: длинную смесь букв и цифр лог вырезает как токен.
        const val MARKER_LENGTH = 8
        const val WAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 50L
    }
}
