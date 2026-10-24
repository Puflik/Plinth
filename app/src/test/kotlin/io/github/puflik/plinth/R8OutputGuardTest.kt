package io.github.puflik.plinth

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * R8 сделал то, что обещают правила (Ю3г, `docs/work/yu3g2.md`). `R8RulesGuardTest` читает файл правил; этот тест —
 * вывод сборки: в `mapping.txt` классы Rhino и языков экстрактора остались под своими именами, а `seeds.txt`
 * показывает, что правила их задели. Так ловится правило, которое есть, но ничего не держит: экстрактор сменил
 * пакет, R8 вырезал класс, который до него доходит только по имени.
 *
 * Вывод пишет `assemble<Flavor>Release` в `app/build/outputs/mapping/<flavor>Release/`. Обычный прогон unit-тестов
 * release не собирает, и тогда тест пропускается. В CI после сборки выпуска задана `PLINTH_REQUIRE_R8_OUTPUT=1`:
 * отсутствие файлов — провал, а не пропуск.
 */
class R8OutputGuardTest {
    @Test
    fun `mapping txt tells a kept class from a renamed one`() {
        val mapping =
            sequenceOf(
                "# compiler: R8",
                "a.Kept -> a.Kept:",
                "# {\"id\":\"sourceFile\",\"fileName\":\"Kept.java\"}",
                "    0:3:void run():10:12 -> run",
                "b.Renamed -> x.y:",
                "    int count -> a",
            )

        assertThat(classMapping(mapping)).containsExactly("a.Kept", "a.Kept", "b.Renamed", "x.y")
    }

    @Test
    fun `seeds txt lists a class by name and a member after a colon`() {
        val seeds = seedList(listOf("a.Kept", "a.Kept: int count", "", "b.Other"))

        assertThat(seeds.classes).containsExactly("a.Kept", "b.Other")
        assertThat(seeds.members).containsExactly("a.Kept: int count")
    }

    @Test
    fun `release builds keep the extractor and rhino under their own names`() {
        val built = VARIANTS.filter { outputFile(it, MAPPING).isFile && outputFile(it, SEEDS).isFile }
        if (System.getenv(REQUIRE_ENV) == "1") {
            assertWithMessage("Нет вывода R8 (mapping.txt и seeds.txt): ${VARIANTS - built.toSet()}")
                .that(built)
                .containsExactlyElementsIn(VARIANTS)
        } else {
            assumeTrue("R8 не запускали: нужен assemble<Flavor>Release", built.isNotEmpty())
        }

        built.forEach(::checkVariant)
    }

    private fun checkVariant(variant: String) {
        val mapping = outputFile(variant, MAPPING).useLines { classMapping(it) }
        val seeds = seedList(outputFile(variant, SEEDS).readLines())

        val renamed =
            mapping
                .filter { (name, newName) -> name != newName && FAMILIES.any(name::startsWith) }
                .keys
                .filterNot { SYNTHETIC in it }
        val lost = KEPT_BY_NAME.filter { mapping[it] != it }
        val unmatched = KEPT_BY_NAME.filterNot(seeds.classes::contains)

        assertWithMessage("$variant: R8 переименовал классы под правилом keep: ${renamed.take(LIST_LIMIT)}")
            .that(renamed)
            .isEmpty()
        assertWithMessage("$variant: нет в mapping.txt под своим именем (вырезаны или переименованы): $lost")
            .that(lost)
            .isEmpty()
        assertWithMessage("$variant: правила не задели классы (seeds.txt): $unmatched")
            .that(unmatched)
            .isEmpty()
        assertWithMessage("$variant: члены $CONTEXT не держатся (seeds.txt)")
            .that(seeds.members.any { it.startsWith("$CONTEXT: ") })
            .isTrue()
    }

    /** Строки классов `mapping.txt`: «исходное имя -> имя в APK:». Члены (с отступом) и комментарии пропускаются. */
    private fun classMapping(lines: Sequence<String>): Map<String, String> =
        lines
            .mapNotNull { CLASS_LINE.matchEntire(it) }
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** `seeds.txt`: класс — строка с именем, член — «Класс: тип имя». */
    private fun seedList(lines: List<String>): Seeds {
        val (members, classes) = lines.filter(String::isNotBlank).partition { ": " in it }

        return Seeds(classes.toSet(), members.toSet())
    }

    private fun outputFile(
        variant: String,
        name: String,
    ): File = SourceTree.resourceFile("../../../build/outputs/mapping/$variant/$name")

    private class Seeds(
        val classes: Set<String>,
        val members: Set<String>,
    )

    private companion object {
        const val REQUIRE_ENV = "PLINTH_REQUIRE_R8_OUTPUT"
        const val MAPPING = "mapping.txt"
        const val SEEDS = "seeds.txt"
        const val CONTEXT = "org.mozilla.javascript.Context"
        const val SYNTHETIC = "ExternalSynthetic"
        const val LIST_LIMIT = 10

        val VARIANTS = listOf("githubRelease", "fdroidRelease")

        /** Первый символ строки класса — не пробел и не `#`. */
        val CLASS_LINE = Regex("""([^\s#]\S*) -> (\S+):""")

        /** Пакеты под `-keep ….** { *; }`: ни один их класс не должен быть переименован. */
        val FAMILIES =
            listOf(
                "org.mozilla.javascript.",
                "org.schabi.newpipe.extractor.timeago.patterns.",
            )

        /**
         * Классы, до которых код доходит только по имени; у каждого семейства — свой представитель. `ClassFileWriter`
         * держится правилом без `**`: только сам класс, его вложенные R8 вправе переименовать.
         */
        val KEPT_BY_NAME =
            listOf(
                CONTEXT,
                "org.mozilla.javascript.ScriptRuntime",
                "org.mozilla.javascript.engine.RhinoScriptEngineFactory",
                "org.mozilla.classfile.ClassFileWriter",
                "org.schabi.newpipe.extractor.timeago.patterns.en",
                "org.schabi.newpipe.extractor.timeago.patterns.ru",
            )
    }
}
