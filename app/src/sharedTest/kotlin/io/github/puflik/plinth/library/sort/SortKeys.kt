package io.github.puflik.plinth.library.sort

/**
 * Ключ сортировки названий фонотеки: без ведущего артикля и в
 * [естественном порядке][NaturalOrder].
 *
 * Копия ключей ядра (`core/library/src/sort/`) для `FakeLibraryRepository`:
 * в приложении с D3c названия сортирует ядро, а фейк должен ставить их так же.
 */
class SortKeys(
    private val articles: ArticleStripper = ArticleStripper(),
) {
    fun of(text: String): String = NaturalOrder.key(articles.strip(text))
}
