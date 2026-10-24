package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.online.FakeOnlineRepository.Applied
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Выбор провайдеров ведёт ядро вместе с главным переключателем (Ю3г): применяется целиком, по порядку. */
class OnlineSwitchProvidersTest {
    private val online = FakeOnlineRepository()
    private val settings = FakeOnlineSettings()

    @Test
    fun `nothing switched off is applied as before`() =
        runTest {
            OnlineSwitch(settings, online, backgroundScope).start()
            runCurrent()

            assertThat(online.applied).containsExactly(Applied(true, emptySet()))
        }

    @Test
    fun `every change of the choice reaches the repository with the whole set`() =
        runTest {
            OnlineSwitch(settings, online, backgroundScope).start()
            runCurrent()

            settings.setProviderEnabled(OnlineProviderIds.YOUTUBE, false)
            runCurrent()
            settings.setProviderEnabled(OnlineProviderIds.ARCHIVE, false)
            runCurrent()
            settings.setProviderEnabled(OnlineProviderIds.YOUTUBE, true)
            runCurrent()

            assertThat(online.applied.map { it.disabled })
                .containsExactly(
                    emptySet<String>(),
                    setOf(OnlineProviderIds.YOUTUBE),
                    setOf(OnlineProviderIds.ARCHIVE, OnlineProviderIds.YOUTUBE),
                    setOf(OnlineProviderIds.ARCHIVE),
                ).inOrder()
            assertThat(online.applied.map { it.enabled }.distinct()).containsExactly(true)
        }

    @Test
    fun `the same choice twice is not applied twice`() =
        runTest {
            OnlineSwitch(settings, online, backgroundScope).start()
            runCurrent()

            settings.setProviderEnabled(OnlineProviderIds.YOUTUBE, true)
            runCurrent()
            settings.setEnabled(true)
            runCurrent()

            assertThat(online.applied).hasSize(1)
        }

    @Test
    fun `while the main switch is off the choice does not reach the repository and on again it is applied`() =
        runTest {
            OnlineSwitch(settings, online, backgroundScope).start()
            runCurrent()

            settings.setEnabled(false)
            runCurrent()
            settings.setProviderEnabled(OnlineProviderIds.YOUTUBE, false)
            runCurrent()
            settings.setProviderEnabled(OnlineProviderIds.ARCHIVE, false)
            runCurrent()

            assertThat(online.applied).containsExactly(Applied(true, emptySet()), Applied(false, emptySet())).inOrder()

            settings.setEnabled(true)
            runCurrent()

            assertThat(online.applied)
                .containsExactly(
                    Applied(true, emptySet()),
                    Applied(false, emptySet()),
                    Applied(true, setOf(OnlineProviderIds.ARCHIVE, OnlineProviderIds.YOUTUBE)),
                ).inOrder()
        }

    @Test
    fun `the repository follows the choice`() =
        runTest {
            OnlineSwitch(settings, online, backgroundScope).start()
            runCurrent()
            val before = online.search("concert")

            settings.setProviderEnabled(OnlineProviderIds.ARCHIVE, false)
            runCurrent()
            val off = online.search("concert")
            settings.setProviderEnabled(OnlineProviderIds.ARCHIVE, true)
            runCurrent()
            val on = online.search("concert")

            assertThat(listOf(before.size, off.size, on.size)).containsExactly(1, 0, 1).inOrder()
        }
}
