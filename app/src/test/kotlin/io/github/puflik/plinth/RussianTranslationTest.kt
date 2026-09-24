package io.github.puflik.plinth

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Страж перевода (план 12.12, RU/EN): у каждой строки `values` есть русская
 * в `values-ru`, не копия английской, с теми же плейсхолдерами; у русских
 * plurals — все четыре формы. Новая строка без перевода роняет сборку.
 */
class RussianTranslationTest {
    private val english = readStrings(SourceTree.resourceFile("values/strings.xml"))
    private val russian = readStrings(SourceTree.resourceFile("values-ru/strings.xml"))

    @Test
    fun `every string has a russian translation and nothing extra`() {
        assertThat(english.keys - russian.keys).isEmpty()
        assertThat(russian.keys - english.keys).isEmpty()
    }

    @Test
    fun `translation is not a copy of the english text`() {
        val copied = english.keys.filter { it !in SAME_IN_BOTH && russian[it] != null && russian[it] == english[it] }

        assertThat(copied).isEmpty()
    }

    @Test
    fun `translations keep the placeholders`() {
        val broken =
            english.flatMap { (key, source) ->
                val expected = placeholders(source.getValue(OTHER))
                russian[key]
                    .orEmpty()
                    .filterValues { placeholders(it) != expected }
                    .keys
                    .map { "$key[$it]" }
            }

        assertThat(broken).isEmpty()
    }

    @Test
    fun `russian plurals have one, few, many and other`() {
        val incomplete =
            russian
                .filterKeys { key -> english[key]?.keys != setOf(OTHER) }
                .filterValues { it.keys != RUSSIAN_QUANTITIES }
                .keys

        assertThat(incomplete).isEmpty()
    }

    private companion object {
        /** Обычная строка лежит под этим ключом; у plurals это форма «прочее». */
        const val OTHER = "other"
        val RUSSIAN_QUANTITIES = setOf("one", "few", "many", OTHER)

        /** Одинаковы на обоих языках: название и строка из одних плейсхолдеров. */
        val SAME_IN_BOTH = setOf("app_name", "library_artist_summary")

        private val PLACEHOLDER = Regex("""%(\d+\$)?[sd]""")

        fun placeholders(text: String): List<String> =
            PLACEHOLDER
                .findAll(text)
                .map { it.value }
                .sorted()
                .toList()

        /**
         * Строки файла: имя → формы. У `<string>` одна форма, [OTHER]; у
         * `<plurals>` — по форме на `quantity`. Непереводимые пропускаются.
         */
        fun readStrings(file: File): Map<String, Map<String, String>> {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val strings =
                document
                    .getElementsByTagName("string")
                    .elements()
                    .filter { it.getAttribute("translatable") != "false" }
                    .associate { it.getAttribute("name") to mapOf(OTHER to it.textContent) }
            val plurals =
                document.getElementsByTagName("plurals").elements().associate { plural ->
                    plural.getAttribute("name") to
                        plural
                            .getElementsByTagName("item")
                            .elements()
                            .associate { it.getAttribute("quantity") to it.textContent }
                }
            return strings + plurals
        }

        fun NodeList.elements(): List<Element> = (0 until length).map { item(it) as Element }
    }
}
