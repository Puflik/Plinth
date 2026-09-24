package io.github.puflik.plinth.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Перечень экранов (A2.1) и порядок вкладок нижней навигации (A2.3).
 */
class DestinationsTest {
    @Test
    fun `tabs are library then search then settings`() {
        assertThat(Destination.tabs)
            .containsExactly(
                Destination.Library,
                Destination.Search,
                Destination.Settings,
            ).inOrder()
    }

    @Test
    fun `routes are unique`() {
        val routes = Destination.entries.map { it.route }
        assertThat(routes).containsNoDuplicates()
    }

    @Test
    fun `routes are not blank`() {
        Destination.entries.forEach { destination ->
            assertThat(destination.route).isNotEmpty()
        }
    }

    @Test
    fun `every tab carries a label and an icon`() {
        Destination.tabs.forEach { destination ->
            assertThat(destination.labelRes).isNotEqualTo(0)
            assertThat(destination.iconRes).isNotEqualTo(0)
        }
    }

    @Test
    fun `player, album and artist open over the tabs`() {
        assertThat(Destination.tabs).containsNoneOf(Destination.Player, Destination.Album, Destination.Artist)
    }

    @Test
    fun `start destination is the library`() {
        assertThat(Destination.START).isEqualTo(Destination.Library)
    }
}
