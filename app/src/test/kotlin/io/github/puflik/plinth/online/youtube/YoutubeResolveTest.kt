package io.github.puflik.plinth.online.youtube

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.online.youtube.Recorded.KINO_ALBUM
import io.github.puflik.plinth.online.youtube.Recorded.KROVI
import io.github.puflik.plinth.online.youtube.Recorded.LUCKY
import io.github.puflik.plinth.online.youtube.Recorded.RAM_ALBUM
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * `resolve` на записанных ответах: трек — одно извлечение с вариантами, которые у видео есть (Opus 251 раньше AAC 140),
 * альбом — первая страница плейлиста с номерами и номинальными вариантами, чужие и испорченные id — без сети.
 */
class YoutubeResolveTest {
    private val replay = ReplayDownloader()
    private val table = FakeStatusTable()
    private val source = NewPipeYoutubeSource(replay, table)

    @Test
    fun `a track is one track with Opus before AAC and the artist without the Topic suffix`() {
        val tracks = source.resolve(LUCKY)

        assertThat(tracks)
            .containsExactly(
                YoutubeTrack(
                    id = LUCKY,
                    title = "Get Lucky (feat. Pharrell Williams and Nile Rodgers)",
                    artist = "Daft Punk",
                    album = null,
                    number = null,
                    duration = 370.seconds,
                    variants =
                        listOf(
                            YoutubeVariant("$LUCKY/251", YoutubeFormat.OPUS, 160),
                            YoutubeVariant("$LUCKY/140", YoutubeFormat.AAC, 128),
                        ),
                ),
            )
        // Один трек — одно извлечение: поток из player, а не два прохода.
        assertThat(replay.count("player.lucky")).isEqualTo(1)
    }

    @Test
    fun `a Cyrillic track keeps its title and artist`() {
        val track = source.resolve(KROVI).single()

        assertThat(track.title).isEqualTo("Группа крови")
        assertThat(track.artist).isEqualTo("Кино")
        assertThat(track.duration).isEqualTo(284.seconds)
    }

    @Test
    fun `only the variants that the video has are offered`() {
        val noOpus =
            NewPipeYoutubeSource(ReplayDownloader().serve("player.lucky", "stream/player-lucky-no-opus.json"), table)
        val noAac =
            NewPipeYoutubeSource(ReplayDownloader().serve("player.lucky", "stream/player-lucky-no-aac.json"), table)

        assertThat(
            noOpus
                .resolve(LUCKY)
                .single()
                .variants
                .map(YoutubeVariant::id),
        ).containsExactly("$LUCKY/140")
        assertThat(
            noAac
                .resolve(LUCKY)
                .single()
                .variants
                .map(YoutubeVariant::id),
        ).containsExactly("$LUCKY/251")
    }

    @Test
    fun `a video with no audio that is offered, 141 included, cannot be played`() {
        for (file in listOf("stream/player-lucky-141.json", "stream/player-lucky-no-audio.json")) {
            val broken = NewPipeYoutubeSource(ReplayDownloader().serve("player.lucky", file), table)

            val failure = assertThrows(YoutubeException.Parse::class.java) { broken.resolve(LUCKY) }

            assertThat(failure.text).startsWith("no ")
        }
    }

    @Test
    fun `an album is its tracks by number, each with both variants and the album title without the prefix`() {
        val tracks = source.resolve(RAM_ALBUM)

        assertThat(tracks).hasSize(13)
        assertThat(tracks.map(YoutubeTrack::number)).containsExactlyElementsIn((1..13).toList()).inOrder()
        assertThat(tracks.map(YoutubeTrack::album).toSet()).containsExactly("Random Access Memories")
        assertThat(tracks.first())
            .isEqualTo(
                YoutubeTrack(
                    id = "zKSsP2084nU",
                    title = "Give Life Back to Music",
                    artist = "Daft Punk",
                    album = "Random Access Memories",
                    number = 1,
                    duration = 276.seconds,
                    variants =
                        listOf(
                            YoutubeVariant("zKSsP2084nU/251", YoutubeFormat.OPUS, 160),
                            YoutubeVariant("zKSsP2084nU/140", YoutubeFormat.AAC, 128),
                        ),
                ),
            )
        assertThat(tracks[7].id).isEqualTo(LUCKY)
        assertThat(tracks[7].title).isEqualTo("Get Lucky (feat. Pharrell Williams and Nile Rodgers)")
        // Для альбома не извлекается ни один трек: формат узнаётся только при игре.
        assertThat(
            replay.seen.filter { it.startsWith("player") || it.startsWith("reel") || it.startsWith("next") },
        ).isEmpty()
    }

    @Test
    fun `a Cyrillic album`() {
        val tracks = source.resolve(KINO_ALBUM)

        assertThat(tracks).hasSize(11)
        assertThat(tracks.first().title).isEqualTo("Группа крови")
        assertThat(tracks.first().artist).isEqualTo("Группа КИНО")
        assertThat(tracks.first().album).isEqualTo("Группа крови")
        assertThat(tracks.last().title).isEqualTo("Легенда")
        assertThat(tracks.last().number).isEqualTo(11)
    }

    @Test
    fun `an album without tracks is unavailable`() {
        val empty = NewPipeYoutubeSource(ReplayDownloader().serve("album.items.ram", "album/items-empty.json"), table)

        val failure = assertThrows(YoutubeException.Unavailable::class.java) { empty.resolve(RAM_ALBUM) }

        assertThat(failure.text).isEqualTo("empty album")
    }

    @Test
    fun `foreign and broken ids are unavailable without asking the network`() {
        val bad =
            listOf(
                "",
                " ",
                "abc",
                "${LUCKY}x",
                LUCKY.dropLast(1),
                "$LUCKY&list=RD$LUCKY",
                "../../etc/passwd",
                "https://www.youtube.com/watch?v=$LUCKY",
                "OLAK5uy_",
                "OLAK5uy_ x",
                "PLabcdefghijklmnopqrstuvwxyz0123456",
                "$LUCKY/251",
            )

        for (id in bad) {
            assertThrows(id, YoutubeException.Unavailable::class.java) { source.resolve(id) }
        }

        assertThat(replay.seen).isEmpty()
    }

    @Test
    fun `what the extractor says about the video decides between unavailable, wait and broken`() {
        fun failure(file: String): YoutubeException {
            val table = FakeStatusTable()
            val failing = NewPipeYoutubeSource(ReplayDownloader().serve("reel.lucky", file), table)
            return assertThrows(YoutubeException::class.java) { failing.resolve(LUCKY) }.also { error ->
                // Таблицу спрашивают только там, где беда — «подождите»; в остальных случаях она молчит.
                if (error is YoutubeException.Network) {
                    assertThat(
                        table.calls,
                    ).containsExactly(429 to null)
                } else {
                    assertThat(table.calls).isEmpty()
                }
            }
        }

        // Нет такого видео, закрытое — это про видео, а не про YouTube; класс исключения экстрактора — в тексте.
        val unavailable = failure("errors/reel-unavailable.json")
        val privateVideo = failure("errors/reel-private.json")
        // «Подтвердите, что вы не бот» — YouTube просит подождать: 429 по общей таблице, а не «видео нет».
        val bot = failure("errors/reel-bot.json")
        // Вместо JSON пришёл HTML — формат сломан.
        val html = failure("errors/reel-html.json")

        assertThat(unavailable).isInstanceOf(YoutubeException.Unavailable::class.java)
        assertThat(unavailable.text).isEqualTo("ContentNotAvailableException")
        assertThat(privateVideo).isInstanceOf(YoutubeException.Unavailable::class.java)
        assertThat(privateVideo.text).isEqualTo("PrivateContentException")
        assertThat(bot).isInstanceOf(YoutubeException.Network::class.java)
        assertThat(bot.text).isEqualTo("http 429")
        assertThat(html).isInstanceOf(YoutubeException.Parse::class.java)
        assertThat(html.text).isEqualTo("ParsingException")
    }
}
