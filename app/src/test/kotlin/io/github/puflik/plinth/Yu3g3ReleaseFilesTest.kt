package io.github.puflik.plinth

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Файлы выпуска YouTube (Ю3г, `docs/work/yu3g3.md`): откуда берётся версия экстрактора, заголовок кода,
 * выведенного из кода NewPipe, описание для магазина, раздел F-Droid в `docs/BUILD.md`, ссылки в README и
 * подключение «О приложении». Всё это — текст, который не ломает ни сборку, ни прогон; страж читает файлы и
 * требует нужное по словам. Экраны (Compose) здесь не проверяются: их исполнитель смотрит руками на эмуляторе.
 */
class Yu3g3ReleaseFilesTest {
    private val catalogTag: String? =
        RepoFiles
            .text("gradle/libs.versions.toml")
            ?.let { Regex("""(?m)^newpipeExtractor\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }

    @Test
    fun `the extractor tag is written once, in the version catalog`() {
        assertWithMessage("В gradle/libs.versions.toml нет newpipeExtractor").that(catalogTag).isNotNull()
        val build = RepoFiles.text("app/build.gradle.kts").orEmpty()

        assertWithMessage("В app/build.gradle.kts нет buildConfigField $TAG_FIELD из каталога версий")
            .that(listOf("buildConfigField", TAG_FIELD, "libs.versions.newpipeExtractor").filterNot(build::contains))
            .isEmpty()
        assertWithMessage("Тег $catalogTag записан в build.gradle.kts литералом — источник версии один")
            .that(build)
            .doesNotContain(catalogTag.orEmpty())
    }

    @Test
    fun `build config carries the tag of the version catalog`() {
        val field = runCatching { Class.forName("io.github.puflik.plinth.BuildConfig").getField(TAG_FIELD) }

        assertWithMessage("В BuildConfig нет поля $TAG_FIELD").that(field.getOrNull()).isNotNull()
        assertThat(field.getOrNull()?.get(null)).isEqualTo(catalogTag)
    }

    @Test
    fun `the youtube downloader names the code it follows`() {
        val header =
            SourceTree
                .mainFile("online/youtube/YoutubeDownloader.kt")
                .readLines()
                .takeWhile { !it.startsWith("package ") }
                .joinToString("\n")

        val missing = HEADER_WORDS.filterNot(header::contains)

        assertWithMessage("В заголовке над package у YoutubeDownloader.kt нет: $missing").that(missing).isEmpty()
    }

    @Test
    fun `the store description tells the truth about youtube`() {
        val text = stored("full_description.txt")

        val missing = DESCRIPTION_WORDS.filterNot { text.contains(it, ignoreCase = true) }

        assertWithMessage("В full_description.txt нет: $missing").that(missing).isEmpty()
        assertWithMessage("Осталась фраза «только archive.org»: о YouTube в ней ни слова")
            .that(text)
            .doesNotContain("While online sources are on, searches and streams go to archive.org.")
    }

    @Test
    fun `store texts fit the store limits`() {
        assertThat(stored("full_description.txt").length).isAtMost(4000)
        assertThat(stored("short_description.txt").length).isAtMost(80)
        assertThat(stored("title.txt").length).isAtMost(50)
    }

    @Test
    fun `build md has an f-droid section with a ready record for fdroiddata`() {
        val text = RepoFiles.text("docs/BUILD.md").orEmpty()

        assertWithMessage("В docs/BUILD.md нет раздела про F-Droid").that(text).containsMatch("(?m)^#{2,3} .*F-Droid")
        val missing = BUILD_RECORD.filterValues { !Regex(it).containsMatchIn(text) }.keys
        assertWithMessage("В записи для fdroiddata нет: $missing").that(missing).isEmpty()
    }

    @Test
    fun `both readmes point at the notice`() {
        val without =
            listOf("README.md", "README.ru.md")
                .filterNot { RepoFiles.text(it).orEmpty().contains("](NOTICE)") }

        assertWithMessage("Нет ссылки [..](NOTICE) в: $without").that(without).isEmpty()
    }

    @Test
    fun `about is the last section of the settings and licenses is a screen of the nav host`() {
        val settings = SourceTree.mainFile("ui/settings/SettingsScreen.kt").readText()
        val vendor = settings.indexOf("vendorGuideItem(onOpen")
        val about = settings.indexOf("aboutSection(")
        val host = SourceTree.mainFile("ui/navigation/PlinthNavHost.kt").readText()

        assertWithMessage("В SettingsScreen нет вызова aboutSection(").that(about).isAtLeast(0)
        assertWithMessage("«О приложении» идёт в конце настроек, после vendorGuideItem (Opus, 2026-10-24, п. 9)")
            .that(about)
            .isGreaterThan(vendor)
        assertWithMessage("PlinthNavHost не знает экран «Лицензии»")
            .that(listOf("composable(Destination.Licenses.route)", "LicensesScreen(").filterNot(host::contains))
            .isEmpty()
    }

    @Test
    fun `about strings exist in english and russian`() {
        for (file in listOf("values/strings.xml", "values-ru/strings.xml")) {
            val text = SourceTree.resourceFile(file).readText()

            val missing = ABOUT_STRINGS.filterNot { text.contains("name=\"$it\"") }

            assertWithMessage("В $file нет строк: $missing").that(missing).isEmpty()
        }
    }

    /** Файл fastlane без пробелов по краям. */
    private fun stored(name: String): String = RepoFiles.text("$FASTLANE/$name").orEmpty().trim()

    private companion object {
        const val TAG_FIELD = "NEWPIPE_EXTRACTOR_VERSION"
        const val FASTLANE = "fastlane/metadata/android/en-US"

        val HEADER_WORDS = listOf("https://github.com/TeamNewPipe/NewPipe", "GPL-3.0-or-later", "AGPL-3.0")

        val DESCRIPTION_WORDS =
            listOf(
                "YouTube",
                "youtube.com",
                "Google",
                "NewPipe",
                "non-free",
                "on by default",
                "not affiliated with",
                "Online sources",
            )

        /** Что должна содержать запись для `fdroiddata` в `docs/BUILD.md`: описание → регулярное выражение. */
        val BUILD_RECORD =
            mapOf(
                "блок yaml" to "(?m)^```yaml",
                "AntiFeatures с NonFreeNet" to "(?ms)^AntiFeatures:.{0,200}?NonFreeNet",
                "License: AGPL-3.0" to "(?m)^License:\\s*AGPL-3\\.0",
                "SourceCode" to "(?m)^SourceCode:\\s*https://github\\.com/Puflik/Plinth\\b",
                "subdir: app" to "(?m)^\\s*subdir:\\s*app\\b",
                "gradle: - fdroid" to "(?ms)^\\s*gradle:\\s*\\n\\s*-\\s*fdroid\\b",
                "UpdateCheckMode" to "(?m)^UpdateCheckMode:",
                "слово fdroiddata" to "fdroiddata",
                "слово NOTICE (правило обновления)" to "NOTICE",
            )

        val ABOUT_STRINGS =
            listOf(
                "settings_about_title",
                "settings_about_version",
                "settings_about_extractor",
                "settings_about_source",
                "settings_about_source_hint",
                "settings_about_licenses",
                "settings_about_licenses_hint",
                "licenses_title",
                "licenses_load_failed",
            )
    }
}
