package io.github.puflik.plinth

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Р9е (`docs/work/r9f.md`): копии рядом с журналом (`*.damaged-*`, `tail.foreign-*`) лежат
 * в `files/core/journal/aside/` и из Auto Backup и переноса на новый телефон исключены:
 * снимок журнала в десятки мегабайт и его копия выбили бы квоту 25 МБ на приложение, а
 * перебор отключает копирование совсем. Что берётся — `BackupRulesTest`.
 */
class R9fBackupRulesTest {
    @Test
    fun `cloud backup and device transfer leave the aside copies out`() {
        val rules = parse(SourceTree.resourceFile("xml/data_extraction_rules.xml"))

        for (section in listOf("cloud-backup", "device-transfer")) {
            assertThat(excluded(rules.single(section))).containsExactly(ASIDE)
        }
    }

    @Test
    fun `old full backup leaves the aside copies out too`() {
        val rules = parse(SourceTree.resourceFile("xml/backup_rules.xml"))

        assertThat(excluded(rules)).containsExactly(ASIDE)
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

    /** Что исключено: `домен:путь` каждого `<exclude>`. */
    private fun excluded(section: Element): List<String> {
        val excludes = section.getElementsByTagName("exclude")
        return (0 until excludes.length)
            .map { excludes.item(it) as Element }
            .map { "${it.getAttribute("domain")}:${it.getAttribute("path")}" }
    }

    private companion object {
        const val ASIDE = "file:core/journal/aside/"
    }
}
