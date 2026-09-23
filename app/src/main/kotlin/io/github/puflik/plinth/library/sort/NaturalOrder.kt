package io.github.puflik.plinth.library.sort

import java.text.Normalizer
import java.util.Locale

/**
 * Естественный порядок названий (C4.3): `Track 2` раньше `Track 10`.
 *
 * Порядок задан ключом, а не одним компаратором: ключи сравниваются по
 * кодовым точкам ([CodePointOrder]), как их сравнивает и база — `ORDER BY` по
 * колонке с ключом, без своей сортировки в SQL.
 *
 * Ключ не замечает регистра, лишних пробелов и диакритики: `Élan` стоит
 * среди `E`, `Ёж` — среди `Е`, как в словарях. Кроме `й`: в русском алфавите
 * это отдельная буква после `и`. Числа дополняются нулями до [NUMBER_WIDTH]
 * знаков и сравниваются по значению; числа длиннее сравниваются как есть.
 */
object NaturalOrder {
    private const val NUMBER_WIDTH = 20
    private val SPACES = Regex("\\s+")
    private val DIGITS = Regex("[0-9]+")

    /** Надстрочные знаки после разложения, кроме краткой (U+0306), на которой держится `й`. */
    private val MARKS = Regex("[\\p{Mn}&&[^\\u0306]]")

    val comparator: Comparator<String> = compareBy(CodePointOrder, ::key)

    fun key(text: String): String {
        val lower = text.trim().replace(SPACES, " ").lowercase(Locale.ROOT)
        val folded = Normalizer.normalize(lower, Normalizer.Form.NFD).replace(MARKS, "")
        return Normalizer.normalize(folded, Normalizer.Form.NFC).replace(DIGITS) { padded(it.value) }
    }

    private fun padded(digits: String): String = digits.trimStart('0').ifEmpty { "0" }.padStart(NUMBER_WIDTH, '0')
}
