package io.github.puflik.plinth.ui.library

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.puflik.plinth.ffi.TrackId
import io.github.puflik.plinth.library.ScanProgress
import io.github.puflik.plinth.library.model.LibraryTrack
import io.github.puflik.plinth.library.permission.PermissionState
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Н4 приёмки v0.2: без разрешения на музыку библиотека была одной просьбой о
 * доступе, а в пустой — одним проводником, и «Любимое» с плейлистами сетевых
 * треков не было видно. Теперь ряд вкладок стоит всегда, просьба — карточкой
 * над файловыми вкладками, проводник — в них же.
 */
class LibraryContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun without_access_every_tab_is_there_and_only_file_tabs_ask_for_it() {
        show(LibraryUiState(PermissionState.Denied, ScanProgress.Idle, loaded = true))

        LibraryTab.entries.forEach { compose.onNodeWithText(context.getString(it.labelRes)).assertExists() }
        compose.onNodeWithText(ACCESS).assertIsDisplayed()
        compose.onNodeWithText(LISTS).assertDoesNotExist()

        compose.onNodeWithText(context.getString(LibraryTab.PLAYLISTS.labelRes)).performClick()

        compose.onNodeWithText(PLAYLISTS).assertIsDisplayed()
        compose.onNodeWithText(ACCESS).assertDoesNotExist()
    }

    @Test
    fun revoked_access_keeps_the_lists_under_the_request() {
        show(
            LibraryUiState(PermissionState.PermanentlyDenied, ScanProgress.Idle, tracks = listOf(track), loaded = true),
        )

        compose.onNodeWithText(ACCESS).assertIsDisplayed()
        compose.onNodeWithText(LISTS).assertIsDisplayed()
    }

    @Test
    fun empty_library_leaves_the_playlists_in_reach() {
        show(LibraryUiState(PermissionState.Granted, ScanProgress.Done(found = 0), loaded = true))

        compose.onNodeWithText(EMPTY).assertIsDisplayed()
        compose.onNodeWithText(ACCESS).assertDoesNotExist()

        compose.onNodeWithText(context.getString(LibraryTab.PLAYLISTS.labelRes)).performClick()

        compose.onNodeWithText(PLAYLISTS).assertIsDisplayed()
        compose.onNodeWithText(EMPTY).assertDoesNotExist()
    }

    private fun show(state: LibraryUiState) {
        compose.setContent {
            var tab by remember { mutableStateOf(LibraryTab.TRACKS) }
            LibraryContent(
                state = state,
                tab = tab,
                onTab = { tab = it },
                access = { Text(ACCESS) },
                empty = { Text(EMPTY) },
                lists = { Text(LISTS) },
                playlists = { Text(PLAYLISTS) },
            )
        }
    }

    private val track =
        LibraryTrack(
            id = TrackId("01a0da9e-761f-7073-bc9b-e5437326adc1"),
            uri = "/storage/emulated/0/Music/track-1.mp3",
            title = "Mustapha",
            artist = "Queen",
            duration = 3.minutes,
            folder = "Music/",
        )

    private companion object {
        const val ACCESS = "access card"
        const val EMPTY = "empty library"
        const val LISTS = "file lists"
        const val PLAYLISTS = "playlists tab"
    }
}
