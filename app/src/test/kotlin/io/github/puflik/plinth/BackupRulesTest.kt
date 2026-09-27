package io.github.puflik.plinth

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Auto Backup и перенос на новый телефон (C4, план 17.5, уровень 4, решение
 * автора): из приложения уходит только журнал пользовательских данных —
 * `files/core/journal` (`CoreModule`: ядро живёт в `files/core`, журнал — в
 * его `journal/`). База, идентификатор установки, замок, настройки и кэш не
 * уходят: ссылки базы и очереди на новом телефоне указывали бы на чужие
 * файлы (ревью №7), а восстановленный журнал не делает новую установку старой.
 */
class BackupRulesTest {
    @Test
    fun `backup is on in the manifest and both rule files are set`() {
        val application = parse(SourceTree.resourceFile("../AndroidManifest.xml")).single("application")

        assertThat(application.getAttribute("android:allowBackup")).isEqualTo("true")
        assertThat(application.getAttribute("android:fullBackupContent")).isEqualTo("@xml/backup_rules")
        assertThat(application.getAttribute("android:dataExtractionRules")).isEqualTo("@xml/data_extraction_rules")
    }

    @Test
    fun `cloud backup and device transfer take only the journal`() {
        val rules = parse(SourceTree.resourceFile("xml/data_extraction_rules.xml"))

        for (section in listOf("cloud-backup", "device-transfer")) {
            assertThat(included(rules.single(section))).containsExactly(JOURNAL)
        }
    }

    @Test
    fun `old full backup takes only the journal too`() {
        val rules = parse(SourceTree.resourceFile("xml/backup_rules.xml"))

        assertThat(included(rules)).containsExactly(JOURNAL)
    }

    /** История прослушиваний — в облако только зашифрованной на устройстве. */
    @Test
    fun `the cloud copy needs encryption on the device`() {
        val cloud = parse(SourceTree.resourceFile("xml/data_extraction_rules.xml")).single("cloud-backup")
        val old = parse(SourceTree.resourceFile("xml/backup_rules.xml")).single("include")

        assertThat(cloud.getAttribute("disableIfNoEncryptionCapabilities")).isEqualTo("true")
        assertThat(old.getAttribute("requireFlags")).isEqualTo("clientSideEncryption")
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

    /** Что берётся: `домен:путь` каждого `<include>`. */
    private fun included(section: Element): List<String> {
        val includes = section.getElementsByTagName("include")
        return (0 until includes.length)
            .map { includes.item(it) as Element }
            .map { "${it.getAttribute("domain")}:${it.getAttribute("path")}" }
    }

    private companion object {
        const val JOURNAL = "file:core/journal/"
    }
}
