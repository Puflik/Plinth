package io.github.puflik.plinth

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * `NOTICE` — сторонние компоненты и их лицензии (Ю3г, `docs/work/yu3g3.md`, раздел А). GPL, MPL, MIT и BSD
 * ставят условия на раздачу: уведомление, адрес исходников, текст. Пропавший абзац не заметят ни сборка, ни
 * тесты приложения, поэтому страж читает файл и требует абзац на каждый компонент: первая строка
 * «Имя — лицензия», адрес в абзаце. Тот же текст лежит в assets — его показывает экран «Лицензии», — и
 * копии не должны разойтись.
 */
class Yu3g3NoticeTest {
    private val notice = RepoFiles.text("NOTICE")
    private val bundled = RepoFiles.text("app/src/main/assets/NOTICE")

    @Test
    fun `notice is a plain utf-8 file at the repository root`() {
        assertWithMessage("В корне репозитория нет файла NOTICE").that(notice).isNotNull()
        assertThat(notice.orEmpty().trim()).isNotEmpty()
        assertWithMessage("NOTICE начинается с BOM — экран «Лицензии» покажет лишний знак")
            .that(notice.orEmpty().firstOrNull())
            .isNotEqualTo('﻿')
    }

    @Test
    fun `every component has a paragraph with its name and licence in the first line and an address`() {
        val paragraphs = paragraphsOf(notice.orEmpty())

        val problems = COMPONENTS.mapNotNull { problemWith(it, paragraphs) }

        assertWithMessage("В NOTICE не хватает или неполно: $problems").that(problems).isEmpty()
    }

    @Test
    fun `mit and bsd texts are reproduced in full`() {
        val text = notice.orEmpty().replace(WHITESPACE, " ")

        val missing = SHORT_LICENCES.filterNot(text::contains)

        assertWithMessage("MIT и BSD требуют воспроизвести текст в раздаче; нет фраз: $missing")
            .that(missing)
            .isEmpty()
    }

    @Test
    fun `no placeholders are left in the text`() {
        val left = PLACEHOLDER.findAll(notice.orEmpty()).map { it.value }.toList()

        assertWithMessage("В NOTICE остались заглушки: $left").that(left).isEmpty()
    }

    @Test
    fun `the copy in assets is the same text`() {
        assertWithMessage("Нет app/src/main/assets/NOTICE — экрану «Лицензии» нечего показывать")
            .that(bundled)
            .isNotNull()
        assertWithMessage("NOTICE в assets отличается от корневого — править надо оба файла")
            .that(bundled)
            .isEqualTo(notice)
    }

    /** Абзацы, разделённые пустой строкой; строки внутри абзаца без концевых пробелов. */
    private fun paragraphsOf(text: String): List<String> =
        text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun problemWith(
        component: Component,
        paragraphs: List<String>,
    ): String? {
        val paragraph =
            paragraphs.firstOrNull { text ->
                val head = text.lineSequence().first()
                head.startsWith(component.name) && component.licenses.all(head::contains)
            } ?: return "${component.name} — ${component.licenses.joinToString()}: нет абзаца «Имя — лицензия»"
        val absent =
            listOfNotNull(component.address).filterNot(paragraph::contains) +
                component.mentions.filterNot { paragraph.contains(it, ignoreCase = true) }
        return if (absent.isEmpty()) null else "${component.name}: в абзаце нет $absent"
    }

    /** Один компонент: [name] начинает первую строку абзаца, [licenses] — в ней же, остальное — в абзаце. */
    private class Component(
        val name: String,
        val licenses: List<String>,
        val address: String? = null,
        val mentions: List<String> = emptyList(),
    )

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val PLACEHOLDER = Regex("\\b(TODO|TBD|FIXME|XXX)\\b")

        val SHORT_LICENCES =
            listOf(
                "Permission is hereby granted, free of charge, to any person obtaining a copy",
                "Redistribution and use in source and binary forms, with or without modification, " +
                    "are permitted provided that the following conditions are met",
            )

        val COMPONENTS =
            listOf(
                Component(
                    "Plinth",
                    listOf("AGPL-3.0"),
                    "https://github.com/Puflik/Plinth",
                    listOf("GPL-3.0-or-later", "section 13"),
                ),
                Component(
                    "NewPipeExtractor",
                    listOf("GPL-3.0-or-later"),
                    "https://github.com/TeamNewPipe/NewPipeExtractor",
                    listOf("https://www.gnu.org/licenses/gpl-3.0.txt"),
                ),
                Component("nanojson", listOf("Apache-2.0"), "https://github.com/TeamNewPipe/nanojson"),
                Component("jsoup", listOf("MIT"), "https://jsoup.org", listOf("Copyright")),
                Component(
                    "Rhino",
                    listOf("MPL-2.0"),
                    "https://github.com/mozilla/rhino",
                    listOf("source", "https://www.mozilla.org/MPL/2.0/"),
                ),
                Component(
                    "protobuf-javalite",
                    listOf("BSD-3-Clause"),
                    "https://github.com/protocolbuffers/protobuf",
                    listOf("Copyright"),
                ),
                Component("jsr305", listOf("Apache-2.0"), "http"),
                Component(
                    "desugar_jdk_libs_nio",
                    listOf("GPL-2.0", "Classpath-exception-2.0"),
                    "https://github.com/google/desugar_jdk_libs",
                ),
                Component(
                    "UniFFI",
                    listOf("MPL-2.0"),
                    "https://github.com/mozilla/uniffi-rs",
                    listOf("source", "core/Cargo.lock"),
                ),
                Component("AndroidX", listOf("Apache-2.0"), "https://www.apache.org/licenses/LICENSE-2.0"),
                Component("JNA", listOf("LGPL-2.1", "Apache-2.0"), "https://github.com/java-native-access/jna"),
                Component("Rust crates", listOf("MIT", "Apache-2.0", "MPL-2.0"), null, listOf("core/Cargo.lock")),
            )
    }
}
