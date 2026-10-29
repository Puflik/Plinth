package io.github.puflik.plinth.ui

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.puflik.plinth.RepoFiles
import org.junit.Test

/**
 * Страж слов значка источника (Ю5а, находка Ю3г): облако у сетевого трека озвучивалось «From Internet Archive» и у
 * треков YouTube. Экран называет провайдера там, где знает его (`track_online_from`), а где нет — не называет
 * никого (`track_online`). Слова лежат в ресурсах, поэтому страж читает их как текст: Compose здесь не запускается
 * (озвучку в строках проверяют `Yu5aOnlineMarkTest` и `Yu5aSearchSongsScreenTest` на эмуляторе).
 */
class Yu5aSourceMarkTextTest {
    @Test
    fun `the mark of an unknown source names no provider`() {
        for (locale in LOCALES) {
            val text = string(locale, "track_online")

            assertWithMessage("track_online в $locale: «$text»").that(text).isNotNull()
            assertWithMessage("track_online в $locale называет провайдера: «$text»")
                .that(text.orEmpty().contains("archive", ignoreCase = true))
                .isFalse()
            assertWithMessage("track_online в $locale с подстановкой: «$text»")
                .that(text.orEmpty().contains("%"))
                .isFalse()
        }
    }

    @Test
    fun `the mark of a known source takes the name of the provider`() {
        for (locale in LOCALES) {
            val text = string(locale, "track_online_from")

            assertWithMessage("В $locale нет track_online_from").that(text).isNotNull()
            assertThat(text.orEmpty().split("%1\$s")).hasSize(2)
        }
    }

    @Test
    fun `a song being fetched has words`() {
        for (locale in LOCALES) {
            val text = string(locale, "online_song_getting")

            assertWithMessage("В $locale нет online_song_getting").that(text).isNotNull()
        }
    }

    private fun string(
        locale: String,
        name: String,
    ): String? {
        val file = RepoFiles.text("app/src/main/res/$locale/strings.xml") ?: error("Нет strings.xml в $locale")
        return Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(file)
            ?.groupValues
            ?.get(1)
    }

    private companion object {
        val LOCALES = listOf("values", "values-ru")
    }
}
