package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.ALBUM
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.ALBUM_TRACK_2
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.QUIET_TRACK
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.RADIO_1
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.RADIO_2
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.RADIO_3
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.RADIO_4
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.RADIO_5
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.RADIO_6
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.RADIO_7
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.RADIO_KEY_2
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.RADIO_KEY_3
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.SONG
import io.github.puflik.plinth.online.FakeYoutubeSource.Companion.SONG_NO_OPUS
import io.github.puflik.plinth.online.youtube.YoutubeException
import io.github.puflik.plinth.online.youtube.YoutubeFormat
import io.github.puflik.plinth.online.youtube.YoutubeRadioPage
import io.github.puflik.plinth.online.youtube.YoutubeTrack
import io.github.puflik.plinth.online.youtube.YoutubeVariant
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Радио в [FakeYoutubeSource] (Ю4б): на нём стоят тесты очереди (Ю4в, `RadioFeeder`), поэтому оно ведёт себя как
 * настоящий источник — те же затравки, те же ключи, те же беды — и держится этим тестом. Mix по умолчанию: три страницы
 * по три, три и один трек, у последней ключа нет; тест подменяет его целиком через `radioPages`.
 */
class FakeYoutubeSourceRadioTest {
    private val source = FakeYoutubeSource()

    @Test
    fun `the Mix is three pages with the keys of the next ones and an end`() {
        val first = source.radio(SONG, null)
        val second = source.radio(SONG, first.next)
        val third = source.radio(SONG, second.next)

        assertThat(first.tracks.map(YoutubeTrack::id)).containsExactly(RADIO_1, RADIO_2, RADIO_3).inOrder()
        assertThat(first.next).isEqualTo(RADIO_KEY_2)
        assertThat(second.tracks.map(YoutubeTrack::id)).containsExactly(RADIO_4, RADIO_5, RADIO_6).inOrder()
        assertThat(second.next).isEqualTo(RADIO_KEY_3)
        assertThat(third.tracks.map(YoutubeTrack::id)).containsExactly(RADIO_7)
        assertThat(third.next).isNull()
    }

    @Test
    fun `a radio track is a plain video with both nominal variants and a length of its own`() {
        val track = source.radio(SONG, null).tracks[1]

        assertThat(track)
            .isEqualTo(
                YoutubeTrack(
                    id = RADIO_2,
                    title = "Radio 2",
                    artist = "Radio Artist",
                    album = null,
                    number = null,
                    duration = 210.seconds,
                    variants =
                        listOf(
                            YoutubeVariant("$RADIO_2/251", YoutubeFormat.OPUS, 160),
                            YoutubeVariant("$RADIO_2/140", YoutubeFormat.AAC, 128),
                        ),
                ),
            )
        assertThat(source.stream("$RADIO_2/251").format).isEqualTo(YoutubeFormat.OPUS)
        assertThat(source.stream("$RADIO_2/140").format).isEqualTo(YoutubeFormat.AAC)
    }

    @Test
    fun `any video of the catalog and any radio track is a seed, in either form`() {
        val seeds = listOf(SONG, SONG_NO_OPUS, ALBUM_TRACK_2, QUIET_TRACK, RADIO_1, RADIO_7, "$SONG/251", "$RADIO_3/140")

        for (seed in seeds) {
            assertThat(source.radio(seed, null).tracks).isNotEmpty()
        }
    }

    @Test
    fun `a seed that is not a video or not known, and a foreign itag, are unavailable id`() {
        val bad = listOf("", "nobody-here", ALBUM, "$SONG/137", "${SONG}x", "$SONG/251/140", "zzzzzzzzzzz")

        for (seed in bad) {
            val failure = assertThrows(seed, YoutubeException.Unavailable::class.java) { source.radio(seed, null) }
            assertThat(failure.text).isEqualTo("id")
        }
    }

    @Test
    fun `a key that the Mix does not have is unavailable page`() {
        val failure = assertThrows(YoutubeException.Unavailable::class.java) { source.radio(SONG, "no-such-key") }

        assertThat(failure.text).isEqualTo("page")
    }

    @Test
    fun `the seed is not on the page, whatever the Mix says`() {
        val everything = source.radio(SONG, null).tracks + source.resolve(SONG)
        source.radioPages = mapOf(null to YoutubeRadioPage(everything, "more"), "more" to YoutubeRadioPage(everything, null))

        val byVideo = source.radio(SONG, null)
        val byVariant = source.radio("$SONG/251", "more")
        val other = source.radio(ALBUM_TRACK_2, null)

        assertThat(byVideo.tracks.map(YoutubeTrack::id)).containsExactly(RADIO_1, RADIO_2, RADIO_3).inOrder()
        assertThat(byVideo.next).isEqualTo("more")
        assertThat(byVariant.tracks.map(YoutubeTrack::id)).containsExactly(RADIO_1, RADIO_2, RADIO_3).inOrder()
        assertThat(other.tracks.map(YoutubeTrack::id)).contains(SONG)
    }

    @Test
    fun `every call is on record with its seed and key, and a failure is thrown after it`() {
        source.radio(SONG, null)
        source.radio("$SONG/251", RADIO_KEY_2)
        source.failure = YoutubeException.Network("timed out")

        val failure = assertThrows(YoutubeException.Network::class.java) { source.radio(SONG, RADIO_KEY_3) }

        assertThat(failure.text).isEqualTo("timed out")
        assertThat(source.calls)
            .containsExactly("radio:$SONG:null", "radio:$SONG/251:$RADIO_KEY_2", "radio:$SONG:$RADIO_KEY_3")
            .inOrder()
    }

    @Test
    fun `radio leaves search resolve and stream as they were`() {
        source.radio(SONG, null)

        assertThat(source.search("piano", 20).map { it.id }).containsExactly(SONG, ALBUM).inOrder()
        assertThat(source.resolve(SONG).single().id).isEqualTo(SONG)
        assertThat(source.stream("$SONG/251").format).isEqualTo(YoutubeFormat.OPUS)
    }
}
