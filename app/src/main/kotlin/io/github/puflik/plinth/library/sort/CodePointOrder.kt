package io.github.puflik.plinth.library.sort

/**
 * Порядок строк по кодовым точкам Unicode — так их сравнивают SQLite
 * (побайтово в UTF-8) и Rust (`str`), то есть хранилище v0.1 и ядро v0.2.
 *
 * `String.compareTo` сравнивает единицы UTF-16, и символы вне BMP (эмодзи,
 * редкие иероглифы) у него стоят раньше U+E000–U+FFFF (полуширинная
 * катакана, U+FFFD из битых тегов). База поставила бы их позже — а всё, что
 * сортирует ключи в Kotlin, обязано сортировать как база.
 */
object CodePointOrder : Comparator<String> {
    override fun compare(
        a: String,
        b: String,
    ): Int {
        var index = 0
        while (index < a.length && index < b.length) {
            val left = a.codePointAt(index)
            val right = b.codePointAt(index)
            if (left != right) return left.compareTo(right)
            index += Character.charCount(left)
        }
        return a.length.compareTo(b.length)
    }
}
