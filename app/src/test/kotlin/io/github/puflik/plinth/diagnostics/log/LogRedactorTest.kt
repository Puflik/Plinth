package io.github.puflik.plinth.diagnostics.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Тесты на утечки (G1.2 🔒): выгруженный лог уходит в публичный issue, и в нём
 * не должно быть ни путей, ни адресов, ни токенов, ни того, что человек искал.
 */
class LogRedactorTest {
    private val redactor = LogRedactor()

    @Test
    fun `file path in an exception message keeps only the extension`() {
        val message =
            "java.io.FileNotFoundException: /storage/emulated/0/Music/Diary/voice-memo.mp3: open failed: ENOENT"

        val redacted = redactor.redact(message)

        assertThat(redacted).doesNotContain("Diary")
        assertThat(redacted).doesNotContain("voice-memo")
        assertThat(redacted).contains("<path>.mp3")
        assertThat(redacted).contains("open failed: ENOENT")
    }

    @Test
    fun `document uri with an encoded path keeps only its scheme`() {
        val message =
            "Source error: content://com.android.externalstorage.documents/document/primary%3AMusic%2FSecret%2Fsong.flac"

        val redacted = redactor.redact(message)

        assertThat(redacted).isEqualTo("Source error: content://<redacted>")
    }

    @Test
    fun `web address with a query and a token keeps only its scheme`() {
        val redacted = redactor.redact("GET https://api.example.org/search?q=beatles&access_token=abc123 failed")

        assertThat(redacted).isEqualTo("GET https://<redacted> failed")
    }

    @Test
    fun `tokens and keys are cut out wherever they stand`() {
        val redacted =
            redactor.redact("Authorization: Bearer eyJhbGciOi.payload.sig; api_key=K3y-v4lue password: hunter2")

        assertThat(redacted).doesNotContain("eyJhbGciOi")
        assertThat(redacted).doesNotContain("K3y-v4lue")
        assertThat(redacted).doesNotContain("hunter2")
        assertThat(redacted).contains("Bearer <token>")
    }

    @Test
    fun `long opaque strings are treated as tokens`() {
        val hash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

        val redacted = redactor.redact("session $hash expired")

        assertThat(redacted).isEqualTo("session <token> expired")
    }

    @Test
    fun `search text inside sql literals is cut out`() {
        val message =
            "SQLiteException: near \"LIKE\": syntax error, " +
                "while compiling: SELECT * FROM tracks WHERE title LIKE '%yesterday%'"

        val redacted = redactor.redact(message)

        assertThat(redacted).doesNotContain("yesterday")
        assertThat(redacted).contains("LIKE '<redacted>'")
    }

    @Test
    fun `email addresses are cut out`() {
        assertThat(redactor.redact("sync for someone@example.com failed")).isEqualTo("sync for <email> failed")
    }

    @Test
    fun `stack trace stays readable`() {
        val trace =
            """
            java.lang.IllegalStateException: boom
            	at io.github.puflik.plinth.audio.PlaybackController.play(PlaybackController.kt:63)
            	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)
            """.trimIndent()

        assertThat(redactor.redact(trace)).isEqualTo(trace)
    }

    @Test
    fun `plain diagnostics are left alone`() {
        val message = "Scan done: 5000 tracks in 1843 ms, API 36, flavor github"

        assertThat(redactor.redact(message)).isEqualTo(message)
    }
}
