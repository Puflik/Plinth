package io.github.puflik.plinth.library.sort

/**
 * Запрос поиска по фонотеке (C4.4): каждое слово запроса должно найтись
 * подстрокой хотя бы в одном из полей — без регистра и надстрочных знаков,
 * как [ключи сортировки][NaturalOrder.fold]. `beat` находит `The Beatles`,
 * `queen opera` — `Bohemian Rhapsody` с альбома `A Night at the Opera`.
 */
class SearchQuery(
    text: String,
) {
    private val words = NaturalOrder.fold(text).split(' ').filter(String::isNotEmpty)

    /** Искать нечего: пустой запрос ничего не находит. */
    val isBlank: Boolean get() = words.isEmpty()

    fun matches(vararg fields: String?): Boolean {
        if (isBlank) return false
        val text = NaturalOrder.fold(fields.filterNotNull().joinToString(" "))
        return words.all(text::contains)
    }
}
