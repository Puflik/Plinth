package io.github.puflik.plinth.audio.media3

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.audio.engine.StreamLookup
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Ссылка на поток живёт часы (Ю3в): загрузка держит адрес, пока тот годится, и не дольше срока, который назвал
 * провайдер, — иначе перемотка через несколько часов пойдёт по мёртвой ссылке. Адрес без срока (Internet Archive)
 * не стареет. Отказ сервера (403) отнимает у загрузки один повторный вопрос; срок его не отнимает.
 *
 * `OnlineResolver(clock, streams)` и `refused` — новый API (сигнатуры в `docs/work/yu3v.md`).
 */
class OnlineResolverExpiryTest {
    private var now = 0L
    private val asked = mutableListOf<String>()
    private val answers = ArrayDeque<StreamLookup>()

    private fun resolver(vararg lookups: StreamLookup): OnlineResolver {
        answers.addAll(lookups)
        return OnlineResolver(clock = { now }) { track ->
            asked += track
            answers.removeFirst()
        }
    }

    private fun found(
        path: String,
        validFor: Duration? = null,
    ) = StreamLookup.Found("https://rr1.test/$path", validFor = validFor)

    private fun resolve(
        resolver: OnlineResolver,
        link: Uri,
    ): String = resolver.resolveDataSpec(DataSpec(link)).uri.toString()

    @Test
    fun an_address_lives_its_validity_and_not_a_nanosecond_longer() {
        val resolver = resolver(found("a", 10.minutes), found("b", 10.minutes))
        val load = link()

        val first = resolve(resolver, load)
        now = 10.minutes.inWholeNanoseconds - 1
        val justBefore = resolve(resolver, load)
        now = 10.minutes.inWholeNanoseconds
        val atTheEnd = resolve(resolver, load)

        assertThat(listOf(first, justBefore, atTheEnd))
            .containsExactly("https://rr1.test/a", "https://rr1.test/a", "https://rr1.test/b")
            .inOrder()
        assertThat(asked).hasSize(2)
    }

    @Test
    fun a_new_address_starts_its_validity_from_the_moment_it_was_given() {
        val resolver = resolver(found("a", 10.minutes), found("b", 10.minutes))
        val load = link()
        resolve(resolver, load)
        now = 11.minutes.inWholeNanoseconds
        resolve(resolver, load)

        now = 20.minutes.inWholeNanoseconds
        val stillTheSecond = resolve(resolver, load)

        assertThat(stillTheSecond).isEqualTo("https://rr1.test/b")
        assertThat(asked).hasSize(2)
    }

    @Test
    fun an_address_without_a_validity_never_ages() {
        val resolver = resolver(found("a"))
        val load = link()
        resolve(resolver, load)

        now = 365.days.inWholeNanoseconds
        val later = resolve(resolver, load)

        assertThat(later).isEqualTo("https://rr1.test/a")
        assertThat(asked).hasSize(1)
    }

    @Test
    fun an_address_with_no_time_left_is_asked_again_by_the_next_open() {
        val resolver = resolver(found("a", Duration.ZERO), found("b", Duration.ZERO))
        val load = link()

        val first = resolve(resolver, load)
        val second = resolve(resolver, load)

        assertThat(listOf(first, second)).containsExactly("https://rr1.test/a", "https://rr1.test/b").inOrder()
    }

    @Test
    fun every_load_ages_by_itself() {
        val resolver = resolver(found("a1", 10.minutes), found("b1", 10.minutes), found("a2", 10.minutes))
        val first = link()
        val second = link()
        resolve(resolver, first)
        now = 6.minutes.inWholeNanoseconds
        resolve(resolver, second)

        now = 11.minutes.inWholeNanoseconds
        val firstAgain = resolve(resolver, first)
        val secondAgain = resolve(resolver, second)

        assertThat(firstAgain).isEqualTo("https://rr1.test/a2")
        assertThat(secondAgain).isEqualTo("https://rr1.test/b1")
    }

    @Test
    fun the_first_refusal_of_a_load_forgets_its_address_and_the_next_open_asks_again() {
        val resolver = resolver(found("a"), found("b"))
        val load = link()
        resolve(resolver, load)

        val allowed = resolver.refused(load)
        val next = resolve(resolver, load)

        assertThat(allowed).isTrue()
        assertThat(next).isEqualTo("https://rr1.test/b")
        assertThat(asked).hasSize(2)
    }

    @Test
    fun the_second_refusal_of_the_same_load_is_not_answered_and_the_address_stays() {
        val resolver = resolver(found("a"), found("b"))
        val load = link()
        resolve(resolver, load)
        resolver.refused(load)
        resolve(resolver, load)

        val allowed = resolver.refused(load)
        val next = resolve(resolver, load)

        assertThat(allowed).isFalse()
        assertThat(next).isEqualTo("https://rr1.test/b")
        assertThat(asked).hasSize(2)
    }

    @Test
    fun a_refusal_asks_nothing_by_itself() {
        val resolver = resolver(found("a"))
        val load = link()
        resolve(resolver, load)

        resolver.refused(load)

        assertThat(asked).hasSize(1)
    }

    @Test
    fun every_load_has_its_own_retry() {
        val resolver = resolver(found("a1"), found("a2"), found("b1"), found("b2"))
        val first = link()
        val second = link()
        resolve(resolver, first)
        resolver.refused(first)
        resolve(resolver, first)
        resolve(resolver, second)

        assertThat(resolver.refused(second)).isTrue()
        assertThat(resolve(resolver, second)).isEqualTo("https://rr1.test/b2")
        assertThat(resolver.refused(first)).isFalse()
    }

    @Test
    fun running_out_of_time_does_not_use_up_the_retry() {
        val resolver = resolver(found("a", 10.minutes), found("b", 10.minutes), found("c", 10.minutes))
        val load = link()
        resolve(resolver, load)
        now = 11.minutes.inWholeNanoseconds
        resolve(resolver, load)

        val allowed = resolver.refused(load)

        assertThat(allowed).isTrue()
        assertThat(resolve(resolver, load)).isEqualTo("https://rr1.test/c")
    }

    @Test
    fun a_failed_lookup_after_a_refusal_is_not_remembered_and_the_retry_stays_used() {
        val resolver = resolver(found("a"), StreamLookup.NoNetwork, found("c"))
        val load = link()
        resolve(resolver, load)
        resolver.refused(load)

        val failure = assertThrows(DataSourceException::class.java) { resolve(resolver, load) }
        val recovered = resolve(resolver, load)

        assertThat(failure.reason).isEqualTo(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

        assertThat(recovered).isEqualTo("https://rr1.test/c")
        assertThat(resolver.refused(load)).isFalse()
    }

    @Test
    fun a_file_or_an_address_is_not_the_resolvers_to_retry() {
        val resolver = resolver()

        assertThat(resolver.refused(Uri.parse("https://example.org/stream.opus"))).isFalse()
        assertThat(resolver.refused(Uri.parse("file:///storage/emulated/0/Music/a.mp3"))).isFalse()
        assertThat(asked).isEmpty()
    }

    private fun link(): Uri = checkNotNull(MediaItemMapper.map(AudioSource.Online(TRACK)).localConfiguration).uri

    private companion object {
        const val TRACK = "0192f7c4-0000-7000-8000-00000000000a"
    }
}
