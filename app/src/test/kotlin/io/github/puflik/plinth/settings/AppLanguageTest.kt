package io.github.puflik.plinth.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

/** Язык приложения из настроек Android 13+ (план 12.12): системный или свой. */
class AppLanguageTest {
    @Test
    fun `no language of its own means the system one`() {
        assertThat(AppLanguage.of("")).isEqualTo(AppLanguage.System)
        assertThat(AppLanguage.of("  ")).isEqualTo(AppLanguage.System)
    }

    @Test
    fun `chosen language is named in itself with a capital letter`() {
        val russian = AppLanguage.of("ru-RU")

        assertThat(russian).isEqualTo(AppLanguage.Chosen(Locale.forLanguageTag("ru-RU")))
        assertThat((russian as AppLanguage.Chosen).nativeName).isEqualTo("Русский")
        assertThat((AppLanguage.of("en") as AppLanguage.Chosen).nativeName).isEqualTo("English")
    }

    @Test
    fun `first language of the list is the one in use`() {
        assertThat(AppLanguage.of("ru, en-US")).isEqualTo(AppLanguage.Chosen(Locale.forLanguageTag("ru")))
    }
}
