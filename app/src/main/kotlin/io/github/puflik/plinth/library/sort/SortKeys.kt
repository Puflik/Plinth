package io.github.puflik.plinth.library.sort

/**
 * Ключ сортировки названий фонотеки: без ведущего артикля и в
 * [естественном порядке][NaturalOrder].
 *
 * Хранилище считает ключи при записи и сортирует по ним, поэтому смена
 * списка артиклей требует пересчитать ключи — но не пересканировать файлы.
 */
class SortKeys(
    private val articles: ArticleStripper = ArticleStripper(),
) {
    fun of(text: String): String = NaturalOrder.key(articles.strip(text))
}
