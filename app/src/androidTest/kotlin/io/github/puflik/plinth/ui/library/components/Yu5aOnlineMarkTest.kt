package io.github.puflik.plinth.ui.library.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.platform.app.InstrumentationRegistry
import io.github.puflik.plinth.R
import org.junit.Rule
import org.junit.Test

/**
 * Значок источника у сетевого трека (Ю5а, находка Ю3г): облако озвучивалось «From Internet Archive» и у треков
 * YouTube. Теперь строка, что знает провайдера, называет его, а что не знает, — только говорит «сетевой». Тексты
 * берутся из ресурсов приложения: язык эмулятора не важен.
 */
class Yu5aOnlineMarkTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(
        online: Boolean = true,
        provider: String? = null,
    ) = compose.setContent {
        TrackRow(
            label = TrackLabel("Title", "Artist", null, null, liked = false, online = online, provider = provider),
            onAction = {},
        ) { _ -> }
    }

    @Test
    fun a_track_of_an_unknown_source_is_marked_only_as_online() {
        show()

        compose.onNodeWithContentDescription(context.getString(R.string.track_online)).assertIsDisplayed()
        compose
            .onAllNodesWithContentDescription(context.getString(R.string.track_online_from, "YouTube"))
            .assertCountEquals(0)
    }

    @Test
    fun a_youtube_track_is_marked_as_from_youtube() {
        show(provider = "youtube")

        compose
            .onNodeWithContentDescription(context.getString(R.string.track_online_from, "YouTube"))
            .assertIsDisplayed()
        compose.onAllNodesWithContentDescription(context.getString(R.string.track_online)).assertCountEquals(0)
    }

    @Test
    fun an_internet_archive_track_is_marked_as_from_internet_archive() {
        show(provider = "archive.org")

        compose
            .onNodeWithContentDescription(context.getString(R.string.track_online_from, "Internet Archive"))
            .assertIsDisplayed()
    }

    @Test
    fun a_provider_without_a_name_is_marked_by_its_id() {
        show(provider = "bandcamp")

        compose
            .onNodeWithContentDescription(context.getString(R.string.track_online_from, "bandcamp"))
            .assertIsDisplayed()
    }

    @Test
    fun a_local_track_has_no_mark_whatever_the_provider() {
        show(online = false, provider = "youtube")

        compose.onAllNodesWithContentDescription(context.getString(R.string.track_online)).assertCountEquals(0)
        compose
            .onAllNodesWithContentDescription(context.getString(R.string.track_online_from, "YouTube"))
            .assertCountEquals(0)
    }
}
