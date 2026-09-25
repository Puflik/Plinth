package io.github.puflik.plinth.ui.settings

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.diagnostics.AppInfo
import io.github.puflik.plinth.diagnostics.CrashStore
import io.github.puflik.plinth.diagnostics.LogExporter
import io.github.puflik.plinth.diagnostics.log.LogEntry
import io.github.puflik.plinth.diagnostics.log.LogFileWriter
import io.github.puflik.plinth.diagnostics.log.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import kotlin.time.Clock
import kotlin.time.Instant

/** Диагностика на экране (G1.3): предложение после сбоя, выгрузка, отказ. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val now = Instant.parse("2026-09-24T10:00:00Z")
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private val info = AppInfo("0.1.0", "github", "16", 36, "Google Pixel 9")
    private val crashes by lazy { CrashStore(temp.root.resolve("crash")) }
    private val logs by lazy { LogFileWriter(temp.root.resolve("logs"), limitBytes = 10_000) }
    private val viewModel by lazy {
        DiagnosticsViewModel(crashes, logs, LogExporter(info, clock), info, Dispatchers.Unconfined)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `crash from the last run is offered`() =
        runTest(UnconfinedTestDispatcher()) {
            crashes.save("Crash at …")

            assertThat(viewModel.hasCrash.value).isTrue()
        }

    @Test
    fun `saved report has the crash and the log, and the offer is gone`() =
        runTest(UnconfinedTestDispatcher()) {
            crashes.save("Crash at …")
            logs.write(LogEntry(now, LogLevel.INFO, "App", "started"))
            val out = ByteArrayOutputStream()

            viewModel.writeReport(out)

            val report = out.toString(Charsets.UTF_8.name())
            assertThat(report).contains("Crash at …")
            assertThat(report).contains("I App: started")
            assertThat(viewModel.hasCrash.value).isFalse()
            assertThat(crashes.pending()).isNull()
        }

    @Test
    fun `declined offer is not repeated`() =
        runTest(UnconfinedTestDispatcher()) {
            crashes.save("Crash at …")

            viewModel.dismissCrash()

            assertThat(viewModel.hasCrash.value).isFalse()
            assertThat(crashes.pending()).isNull()
        }

    /** Ревью №14: касание мимо диалога или «Назад» — не отказ, отчёт остаётся для «Сохранить лог». */
    @Test
    fun `offer closed by a tap outside keeps the report for the log`() =
        runTest(UnconfinedTestDispatcher()) {
            crashes.save("Crash at …")

            viewModel.closeCrashOffer()

            assertThat(viewModel.hasCrash.value).isFalse()
            assertThat(crashes.pending()).isEqualTo("Crash at …")
            val out = ByteArrayOutputStream()
            viewModel.writeReport(out)
            assertThat(out.toString(Charsets.UTF_8.name())).contains("Crash at …")
        }

    @Test
    fun `nothing crashed means no offer`() =
        runTest(UnconfinedTestDispatcher()) {
            assertThat(viewModel.hasCrash.value).isFalse()
        }
}
