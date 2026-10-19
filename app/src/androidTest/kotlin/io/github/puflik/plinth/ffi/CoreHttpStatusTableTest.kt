package io.github.puflik.plinth.ffi

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.online.youtube.HttpVerdict
import org.junit.Test

/**
 * Общая таблица «статус HTTP → ошибка» через настоящее ядро (`check_http_status`, Ю2): Kotlin-загрузчик YouTube
 * берёт её решения, а не пишет свои. Тексты — грамматика `Network` ядра: «http N», «http N, retry after S s».
 */
class CoreHttpStatusTableTest {
    @Test
    fun success_is_fine() {
        for (status in listOf(200, 204, 206)) {
            assertThat(CoreHttpStatusTable.verdict(status, null)).isEqualTo(HttpVerdict.Fine)
        }
    }

    @Test
    fun a_missing_or_gone_item_is_unavailable_whatever_the_server_asks() {
        assertThat(CoreHttpStatusTable.verdict(404, null)).isEqualTo(HttpVerdict.Unavailable("http 404"))
        assertThat(CoreHttpStatusTable.verdict(410, "30")).isEqualTo(HttpVerdict.Unavailable("http 410"))
    }

    @Test
    fun a_server_that_can_recover_keeps_the_time_it_asked_for() {
        assertThat(CoreHttpStatusTable.verdict(503, "7")).isEqualTo(HttpVerdict.Network("http 503, retry after 7 s"))
        assertThat(
            CoreHttpStatusTable.verdict(429, " 30 "),
        ).isEqualTo(HttpVerdict.Network("http 429, retry after 30 s"))
        assertThat(CoreHttpStatusTable.verdict(429, null)).isEqualTo(HttpVerdict.Network("http 429"))
    }

    @Test
    fun the_time_is_whole_seconds_only_and_only_where_waiting_helps() {
        val date = "Wed, 21 Oct 2026 07:28:00 GMT"

        assertThat(CoreHttpStatusTable.verdict(503, date)).isEqualTo(HttpVerdict.Network("http 503"))
        assertThat(CoreHttpStatusTable.verdict(503, "1.5")).isEqualTo(HttpVerdict.Network("http 503"))
        assertThat(CoreHttpStatusTable.verdict(403, "7")).isEqualTo(HttpVerdict.Network("http 403"))
        assertThat(CoreHttpStatusTable.verdict(301, "7")).isEqualTo(HttpVerdict.Network("http 301"))
    }
}
