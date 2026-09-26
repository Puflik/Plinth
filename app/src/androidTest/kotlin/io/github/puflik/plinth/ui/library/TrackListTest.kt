package io.github.puflik.plinth.ui.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.ui.library.tabs.TracksTab
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Найдено в D4b2: ключом строк списков был `TrackId` — value class, который
 * Compose не может сохранить в Bundle, и вкладка «Треки» падала при первом же
 * треке. Ключ строки трека — строка идентификатора.
 */
class TrackListTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun a_track_list_shows_its_tracks() {
        compose.setContent { TracksTab(listOf(track(1, "Mustapha"), track(2, "Jealousy")), onAction = { _, _ -> }) }

        compose.onNodeWithText("Mustapha").assertIsDisplayed()
        compose.onNodeWithText("Jealousy").assertIsDisplayed()
    }

    private fun track(
        n: Int,
        title: String,
    ) = LibraryTrack(
        id = TrackId("01a0da9e-761f-7073-bc9b-e5437326adc$n"),
        uri = "/storage/emulated/0/Music/track-$n.mp3",
        title = title,
        artist = "Queen",
        duration = 3.minutes,
        folder = "Music/",
    )
}
