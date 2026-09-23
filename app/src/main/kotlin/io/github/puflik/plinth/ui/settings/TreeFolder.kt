package io.github.puflik.plinth.ui.settings

/**
 * Папка, выбранная через `OpenDocumentTree`, в виде пути `MediaStore` (C2.5).
 *
 * Системный провайдер хранилища называет папку `том:путь` — `primary:Music/Rock`
 * на внутренней памяти, `1A2B-3C4D:Music` на SD-карте; путь внутри тома и есть
 * `RELATIVE_PATH`. Папки других провайдеров (облака, «Загрузки» как
 * отдельный источник) локальными путями не бывают.
 */
object TreeFolder {
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /** Путь папки с `/` в конце, корень тома — пустая строка; `null` — это не папка хранилища. */
    fun path(
        authority: String,
        documentId: String,
    ): String? {
        if (authority != EXTERNAL_STORAGE || ':' !in documentId) return null
        val inside = documentId.substringAfter(':').trim('/')
        return if (inside.isEmpty()) "" else "$inside/"
    }
}
