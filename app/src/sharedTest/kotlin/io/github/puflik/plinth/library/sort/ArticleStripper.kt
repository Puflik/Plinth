package io.github.puflik.plinth.library.sort

import java.util.Locale

/**
 * Ведущий артикль, которого сортировка не замечает (C4.3): `The Beatles`
 * стоит под «B». Список настраиваемый — у каждой коллекции свои языки.
 *
 * Артикль — только отдельное первое слово и только если после него что-то
 * есть: `Theatre` и одинокое `The` остаются как есть.
 */
class ArticleStripper(
    articles: Collection<String> = DEFAULT_ARTICLES,
) {
    private val articles: Set<String> =
        articles.map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotEmpty).toSet()

    /** Название без ведущего артикля и без пробелов по краям. */
    fun strip(text: String): String {
        val trimmed = text.trim()
        val wordEnd = trimmed.indexOfFirst(Char::isWhitespace)
        if (wordEnd < 0) return trimmed
        val firstWord = trimmed.substring(0, wordEnd).lowercase(Locale.ROOT)
        return if (firstWord in articles) trimmed.substring(wordEnd).trimStart() else trimmed
    }

    companion object {
        /** Умолчание из плана (13.2): английский, немецкий, французский, испанский. */
        val DEFAULT_ARTICLES: List<String> = listOf("The", "A", "An", "Der", "Die", "Das", "Le", "La", "Los")
    }
}
