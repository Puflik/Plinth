package io.github.puflik.plinth.ffi

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import io.github.puflik.plinth.ffi.generated.OutputDevice as RustOutputDevice
import io.github.puflik.plinth.ffi.generated.PlayEvent as RustPlayEvent
import io.github.puflik.plinth.ffi.generated.TrackRow as RustTrackRow
import io.github.puflik.plinth.ffi.generated.TrackUserData as RustTrackUserData

/**
 * Записи ядра → типы приложения (ADR 0010): за пределами `ffi` сгенерированных
 * типов нет, поэтому перевод — здесь и без потерь.
 */
class CoreMappingTest {
    private val track = "0192f7c4-0000-7000-8000-000000000001"

    @Test
    fun `a track row keeps every field`() {
        val row =
            RustTrackRow(
                id = track,
                title = "Creep",
                artistCredit = "Radiohead",
                album = "0192f7c4-0000-7000-8000-000000000002",
                albumTitle = "Pablo Honey",
                duration = java.time.Duration.ofMillis(238_500),
                liked = true,
                playCount = 47u,
            )

        assertThat(row.toApp())
            .isEqualTo(
                CoreTrack(
                    id = TrackId(track),
                    title = "Creep",
                    artistCredit = "Radiohead",
                    album = AlbumId("0192f7c4-0000-7000-8000-000000000002"),
                    albumTitle = "Pablo Honey",
                    duration = 238_500.milliseconds,
                    liked = true,
                    playCount = 47,
                ),
            )
    }

    @Test
    fun `user data turns stars and milliseconds into kotlin types`() {
        val at = 1_790_307_000_000
        val data = RustTrackUserData(track, liked = false, rating = 4u, playCount = 2u, lastPlayedAt = at)

        assertThat(
            data.toApp(),
        ).isEqualTo(TrackUserData(TrackId(track), false, 4, 2, Instant.fromEpochMilliseconds(at)))
    }

    @Test
    fun `a new play goes to rust and comes back as history`() {
        val play =
            NewPlay(
                track = TrackId(track),
                startedAt = Instant.fromEpochMilliseconds(1_000),
                utcOffsetMinutes = 180,
                listened = 200.seconds,
                trackLength = 240.seconds,
                output = OutputDevice.BLUETOOTH,
            )

        val rust = play.toRust()
        val back =
            RustPlayEvent(
                "0192f7c4-0000-7000-8000-000000000009",
                rust.track,
                rust.version,
                rust.source,
                rust.startedAt,
                rust.utcOffsetMinutes,
                rust.listened,
                rust.trackLength,
                rust.skippedAt,
                rust.output,
                rust.previousTrack,
            ).toApp()

        assertThat(rust.output).isEqualTo(RustOutputDevice.BLUETOOTH)
        assertThat(back.track).isEqualTo(play.track)
        assertThat(back.startedAt).isEqualTo(play.startedAt)
        assertThat(back.listened).isEqualTo(play.listened)
        assertThat(back.trackLength).isEqualTo(play.trackLength)
        assertThat(back.output).isEqualTo(OutputDevice.BLUETOOTH)
    }
}
