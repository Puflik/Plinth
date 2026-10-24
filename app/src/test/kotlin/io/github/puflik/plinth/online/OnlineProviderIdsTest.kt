package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ui.online.providerName
import org.junit.Test

/**
 * ID провайдеров (Ю3г): те же, что уже лежат в каталоге пользователя. Список настроек согласован с тем,
 * что приложение отдаёт ядру: новый провайдер попадает в оба места или тест падает.
 */
class OnlineProviderIdsTest {
    @Test
    fun `the ids are the ones the catalog already holds`() {
        assertThat(OnlineProviderIds.ARCHIVE).isEqualTo("archive.org")
        assertThat(OnlineProviderIds.YOUTUBE).isEqualTo("youtube")
    }

    @Test
    fun `the list is the archive and then every provider of the app in their order`() {
        val ofTheApp = onlineProviders().map { it.id }

        assertThat(OnlineProviderIds.ALL).isEqualTo(listOf(OnlineProviderIds.ARCHIVE) + ofTheApp)
    }

    @Test
    fun `every provider has a name for the screen and not its id`() {
        OnlineProviderIds.ALL.forEach { id -> assertThat(providerName(id)).isNotEqualTo(id) }
        assertThat(providerName(OnlineProviderIds.ARCHIVE)).isEqualTo("Internet Archive")
        assertThat(providerName(OnlineProviderIds.YOUTUBE)).isEqualTo("YouTube")
    }
}
