package io.github.puflik.plinth

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Правила R8 (`app/proguard-rules.pro`) держат то, что ломается только в release (Ю3г, `docs/work/yu3g2.md`).
 * Отладочная сборка ничего не сжимает и не переименовывает, поэтому «лишнее» правило можно удалить, и ни один
 * тест, ни один прогон debug этого не заметят: YouTube перестанет играть у человека, в выпуске. Страж читает файл
 * правил и требует их по тексту; что R8 их выполнил, проверяет `R8OutputGuardTest` по выводу настоящей сборки.
 *
 * Набор — проверенный в спайке Ю3а (`spike/yu3a:app/proguard-rules.pro`, он повторяет правила приложения NewPipe).
 * Ослабить правило — сузить Rhino, снять `-keep` — можно только вместе с этим тестом и после прогона сценария
 * `docs/testing/youtube-live.md` на устройстве. `-dontwarn` не требуются: пропажа такого правила валит сборку
 * громко, а не молча.
 */
class R8RulesGuardTest {
    @Test
    fun `the timeago patterns of the extractor keep their names`() {
        assertRules("-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }")
    }

    @Test
    fun `rhino is kept whole`() {
        assertRules(
            "-keep class org.mozilla.javascript.** { *; }",
            "-keep class org.mozilla.classfile.ClassFileWriter",
        )
    }

    @Test
    fun `protobuf messages keep their fields`() {
        assertRules("-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }")
    }

    @Test
    fun `the rules of the core bridge and the player stay`() {
        assertRules(
            "-keepattributes SourceFile,LineNumberTable",
            "-renamesourcefileattribute SourceFile",
            "-keepclassmembernames interface androidx.media3.common.Player\$Listener { <methods>; }",
            "-keep class com.sun.jna.** { *; }",
            "-keep class * implements com.sun.jna.** { *; }",
            "-keep class io.github.puflik.plinth.ffi.generated.** { *; }",
        )
    }

    @Test
    fun `r8 is not switched off and warnings are not silenced wholesale`() {
        val offenders = rules().filter { it.substringBefore(' ') in SWITCHES_OFF || it.isBlanketDontWarn() }

        assertWithMessage("R8 выключен или все предупреждения погашены разом: $offenders").that(offenders).isEmpty()
    }

    private fun assertRules(vararg required: String) {
        val have = rules().toSet()
        val missing = required.map(::normalize).filterNot(have::contains)

        assertWithMessage("В $RULES_FILE нет правил: $missing — без них release ломается молча")
            .that(missing)
            .isEmpty()
    }

    /** `-dontwarn *` и `-dontwarn **` прячут пропавшие классы любых библиотек. */
    private fun String.isBlanketDontWarn(): Boolean =
        startsWith("-dontwarn ") &&
            removePrefix("-dontwarn ").split(',').map(String::trim).any { it == "*" || it == "**" }

    /** Правила файла в нормальном виде: без комментариев, одной строкой, с одним пробелом между словами. */
    private fun rules(): List<String> {
        val text = SourceTree.resourceFile(RULES_FILE).readLines().joinToString(" ") { it.substringBefore('#') }

        return OPTION_START.split(" $text").map(::normalize).filter(String::isNotEmpty)
    }

    private fun normalize(rule: String): String =
        rule
            .replace("{", " { ")
            .replace("}", " } ")
            .replace(";", " ; ")
            .trim()
            .replace(WHITESPACE, " ")

    private companion object {
        /** `app/proguard-rules.pro` от `app/src/main/res`. */
        const val RULES_FILE = "../../../proguard-rules.pro"

        val SWITCHES_OFF = setOf("-dontobfuscate", "-dontshrink", "-dontoptimize", "-ignorewarnings")

        /** Пробел перед очередной опцией: правило начинается с `-слово`. */
        val OPTION_START = Regex("\\s(?=-[a-z])")
        val WHITESPACE = Regex("\\s+")
    }
}
