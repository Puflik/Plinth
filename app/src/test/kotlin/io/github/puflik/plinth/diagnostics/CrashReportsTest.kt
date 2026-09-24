package io.github.puflik.plinth.diagnostics

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.diagnostics.log.LogRedactor
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant

/** Сбои и обратная связь (G1.3): отчёт на диске, выгрузка, шаблон issue. */
class CrashReportsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val now = Instant.parse("2026-09-24T10:00:00Z")
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private val info = AppInfo("0.1.0", "github", "16", 36, "Google Pixel 9")
    private val store by lazy { CrashStore(temp.root.resolve("crash")) }

    @Test
    fun `crash is saved redacted and still reaches the system handler`() {
        var passedOn: Throwable? = null
        val handler = CrashHandler(store, LogRedactor(), clock, info) { _, error -> passedOn = error }
        val error = IllegalStateException("cannot open /storage/emulated/0/Music/secret.mp3")

        handler.uncaughtException(Thread("main"), error)

        val report = store.pending()!!
        assertThat(report).startsWith("Crash at 2026-09-24T10:00:00Z on thread main\n")
        assertThat(report).contains("Plinth 0.1.0 (github), Android 16 (API 36), Google Pixel 9")
        assertThat(report).contains("IllegalStateException: cannot open <path>.mp3")
        assertThat(report).doesNotContain("secret")
        assertThat(passedOn).isSameInstanceAs(error)
    }

    @Test
    fun `failed save does not swallow the crash`() {
        temp.root.resolve("crash").writeText("a file where the folder should be")
        var passedOn = false
        val handler = CrashHandler(store, LogRedactor(), clock, info) { _, _ -> passedOn = true }

        handler.uncaughtException(Thread("main"), IllegalStateException("boom"))

        assertThat(passedOn).isTrue()
    }

    @Test
    fun `report is pending until cleared`() {
        assertThat(store.pending()).isNull()

        store.save("report")
        assertThat(store.pending()).isEqualTo("report")

        store.clear()
        assertThat(store.pending()).isNull()
    }

    @Test
    fun `export has the header, the crash and the log from old to new`() {
        val old = temp.newFile("plinth.1.log").apply { writeText("old line\n") }
        val new = temp.newFile("plinth.log").apply { writeText("new line\n") }
        val out = ByteArrayOutputStream()

        LogExporter(info, clock).export(crash = "Crash at …\ntrace", logs = listOf(old, new), out = out)

        assertThat(out.toString(Charsets.UTF_8.name()))
            .isEqualTo(
                "Plinth report, 2026-09-24T10:00:00Z\n" +
                    "Plinth 0.1.0 (github), Android 16 (API 36), Google Pixel 9\n\n" +
                    "== Crash ==\nCrash at …\ntrace\n\n" +
                    "== Log ==\nold line\nnew line\n",
            )
    }

    @Test
    fun `export without a crash has no crash section`() {
        val out = ByteArrayOutputStream()

        LogExporter(info, clock).export(crash = null, logs = emptyList<File>(), out = out)

        assertThat(out.toString(Charsets.UTF_8.name())).doesNotContain("== Crash ==")
    }

    @Test
    fun `issue template carries the environment and nothing from the log`() {
        val url = IssueTemplate.url(info)

        assertThat(url).startsWith("https://github.com/Puflik/Plinth/issues/new?body=")
        val body = java.net.URLDecoder.decode(url.substringAfter("body="), Charsets.UTF_8.name())
        assertThat(body).contains("Plinth: 0.1.0 (github)")
        assertThat(body).contains("Android: 16 (API 36)")
        assertThat(body).contains("Device: Google Pixel 9")
        assertThat(url).doesNotContain(" ")
        assertThat(url).doesNotContain("+")
    }
}
