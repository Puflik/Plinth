package io.github.puflik.plinth.backup

import java.net.URLDecoder

/**
 * Название папки копии для человека по адресу дерева SAF (C4):
 * `…/tree/primary%3AMusic` → «Music». Системный провайдер хранилища
 * называет папку `том:путь`; корень тома — «/». Адрес не дерева — как есть.
 */
object TreeLabel {
    fun of(tree: String): String {
        val encoded = tree.substringAfter("/tree/", missingDelimiterValue = "").substringBefore('/')
        if (encoded.isEmpty()) return tree
        // SAF кодирует пробел как %20, а «+» — как %2B: URLDecoder их не спутает.
        val document = URLDecoder.decode(encoded, Charsets.UTF_8)
        return document.substringAfter(':').trim('/').ifEmpty { "/" }
    }
}
