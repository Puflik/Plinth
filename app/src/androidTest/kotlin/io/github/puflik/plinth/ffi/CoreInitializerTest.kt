package io.github.puflik.plinth.ffi

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.core.AppError
import io.github.puflik.plinth.diagnostics.log.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Запуск ядра при старте (A3.2, D3): если базу пришлось собрать заново из
 * журнала, об этом узнают экраны — «Библиотека восстановлена».
 */
class CoreInitializerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "core-init-" + UUID.randomUUID())

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun a_library_rebuilt_from_the_journal_is_reported() =
        runBlocking {
            PlinthCore(
                LogLevel.INFO,
                dir,
                CoreErrors(),
            ).apply { journal.like(TrackId(UUID.randomUUID().toString())) }.close()
            dir.listFiles { file -> file.name.startsWith("library.db") }!!.forEach(File::delete)
            val errors = CoreErrors()
            val core = PlinthCore(LogLevel.INFO, dir, errors)
            try {
                CoreInitializer(core, this, Dispatchers.IO, errors).start()

                assertThat(withTimeout(5.seconds) { errors.events.first() }).isEqualTo(AppError.LibraryRestored)
            } finally {
                core.close()
            }
        }
}
