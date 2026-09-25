package io.github.puflik.plinth

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Ревью №7, решение автора: из приложения не уходит ничего — ни в облачную
 * копию, ни при переезде на новый телефон. Ссылки очереди и базы там указывают
 * на чужие файлы, а мастер обещает «Nothing leaves the device».
 *
 * `allowBackup="false"` на Android 12+ не выключает перенос «телефон →
 * телефон» — поэтому правила переноса тоже исключают всё.
 */
class BackupRulesTest {
    @Test
    fun `backup is switched off in the manifest`() {
        val application = parse(SourceTree.resourceFile("../AndroidManifest.xml")).single("application")

        assertThat(application.getAttribute("android:allowBackup")).isEqualTo("false")
    }

    @Test
    fun `cloud backup and device transfer take nothing`() {
        val rules = parse(SourceTree.resourceFile("xml/data_extraction_rules.xml"))

        for (section in listOf("cloud-backup", "device-transfer")) {
            assertThat(excludedDomains(rules.single(section))).containsAtLeastElementsIn(DOMAINS)
        }
    }

    @Test
    fun `old full backup takes nothing either`() {
        val rules = parse(SourceTree.resourceFile("xml/backup_rules.xml"))

        assertThat(excludedDomains(rules)).containsAtLeastElementsIn(DOMAINS)
    }

    private fun parse(file: File): Element =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(file)
            .documentElement

    private fun Element.single(tag: String): Element {
        val found = getElementsByTagName(tag)
        assertThat(found.length).isEqualTo(1)
        return found.item(0) as Element
    }

    /** Домены, исключённые целиком: `<exclude domain="…" path="."/>`. */
    private fun excludedDomains(section: Element): List<String> {
        val excludes = section.getElementsByTagName("exclude")
        return (0 until excludes.length)
            .map { excludes.item(it) as Element }
            .filter { it.getAttribute("path") == "." }
            .map { it.getAttribute("domain") }
    }

    private companion object {
        /** Все домены правил: хранилище, защищённое до разблокировки (device_*), — тоже. */
        val DOMAINS =
            listOf(
                "root",
                "file",
                "database",
                "sharedpref",
                "external",
                "device_root",
                "device_file",
                "device_database",
                "device_sharedpref",
            )
    }
}
