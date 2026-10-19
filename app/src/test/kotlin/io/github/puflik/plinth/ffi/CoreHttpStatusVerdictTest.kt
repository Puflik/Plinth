package io.github.puflik.plinth.ffi

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.generated.CoreException
import io.github.puflik.plinth.online.youtube.HttpVerdict
import org.junit.Test

/**
 * Ответ общей таблицы ядра на стороне Kotlin: ядро показывает ошибку с категорией («network: http 503»), загрузчику
 * нужен текст таблицы без неё — он уйдёт обратно в `CoreError` как есть. Сама таблица — в ядре; её настоящий ответ
 * сверяет инструментальный `CoreHttpStatusTableTest`.
 */
class CoreHttpStatusVerdictTest {
    @Test
    fun `the category that the core puts before the text is taken off`() {
        assertThat(verdictOf(CoreException.Network("network: http 503, retry after 7 s")))
            .isEqualTo(HttpVerdict.Network("http 503, retry after 7 s"))
        assertThat(verdictOf(CoreException.Network("network: http 429"))).isEqualTo(HttpVerdict.Network("http 429"))
        assertThat(
            verdictOf(CoreException.Unavailable("unavailable: http 404")),
        ).isEqualTo(HttpVerdict.Unavailable("http 404"))
    }

    @Test
    fun `a text without a category stays as it is`() {
        assertThat(verdictOf(CoreException.Network("http 503"))).isEqualTo(HttpVerdict.Network("http 503"))
        assertThat(verdictOf(CoreException.Unavailable("http 410"))).isEqualTo(HttpVerdict.Unavailable("http 410"))
    }

    @Test
    fun `any other failure of the table is a refusal too - an answer that the table did not clear is not passed on`() {
        assertThat(verdictOf(CoreException.Internal("internal: boom"))).isEqualTo(HttpVerdict.Network("boom"))
        assertThat(verdictOf(CoreException.Parse("parse: bad"))).isEqualTo(HttpVerdict.Network("bad"))
    }
}
