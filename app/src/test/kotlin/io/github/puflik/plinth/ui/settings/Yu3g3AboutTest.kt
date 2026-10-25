package io.github.puflik.plinth.ui.settings

import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.BuildConfig
import io.github.puflik.plinth.core.config.ProjectLinks
import io.github.puflik.plinth.diagnostics.AppInfo
import io.github.puflik.plinth.ui.navigation.Destination
import org.junit.Test

/**
 * «О приложении» (Ю3г, `docs/work/yu3g3.md`, раздел Б): значения берутся из тех же сведений о сборке, что и
 * диагностика (`AppInfo`), и из тега экстрактора, записанного в каталоге версий; экран «Лицензии» — отдельный
 * маршрут поверх вкладок. Сами Compose-элементы тут не проверяются — их смотрят руками на эмуляторе.
 */
class Yu3g3AboutTest {
    private val github =
        AppInfo(version = "0.3.0", flavor = "github", androidRelease = "16", api = 36, device = "Google Pixel 9")
    private val fdroid = github.copy(version = "0.3.0-fdroid", flavor = "fdroid")

    @Test
    fun `details come from the build info and the extractor tag`() {
        assertThat(aboutDetails(github, extractorVersion = "v0.26.5"))
            .isEqualTo(
                AboutDetails(
                    version = "0.3.0",
                    flavor = "github",
                    extractorVersion = "v0.26.5",
                    sourceUrl = ProjectLinks.SOURCE,
                ),
            )
    }

    @Test
    fun `the version line shows the version and the flavor as the diagnostics line does`() {
        assertThat(aboutDetails(github, "v0.26.5").versionLine).isEqualTo("0.3.0 (github)")
        assertThat(aboutDetails(fdroid, "v0.26.5").versionLine).isEqualTo("0.3.0-fdroid (fdroid)")
        assertThat(github.line).contains(aboutDetails(github, "v0.26.5").versionLine)
    }

    @Test
    fun `the extractor line names the extractor and its tag`() {
        assertThat(aboutDetails(github, "v0.26.5").extractorLine).isEqualTo("NewPipeExtractor v0.26.5")
    }

    @Test
    fun `without an explicit tag the tag of this build is shown`() {
        assertThat(BuildConfig.NEWPIPE_EXTRACTOR_VERSION).isNotEmpty()
        assertThat(aboutDetails(github).extractorVersion).isEqualTo(BuildConfig.NEWPIPE_EXTRACTOR_VERSION)
    }

    @Test
    fun `the view model offers the details of the build it runs in`() {
        assertThat(AboutViewModel(github).details).isEqualTo(aboutDetails(github))
    }

    @Test
    fun `the source link is the project repository`() {
        assertThat(ProjectLinks.SOURCE).isEqualTo("https://github.com/Puflik/Plinth")
    }

    @Test
    fun `licenses is a screen over the tabs with a route of its own`() {
        assertThat(Destination.Licenses.route).isEqualTo("licenses")
        assertThat(Destination.Licenses.labelRes).isEqualTo(0)
        assertThat(Destination.tabs).doesNotContain(Destination.Licenses)
        assertThat(Destination.entries.map { it.route }).containsNoDuplicates()
    }
}
