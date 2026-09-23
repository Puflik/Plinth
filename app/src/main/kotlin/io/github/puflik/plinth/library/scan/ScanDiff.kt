package io.github.puflik.plinth.library.scan

/**
 * Что изменилось с прошлого скана (C2.3) — по `_ID` и времени изменения файла.
 *
 * Сравнивается только время: теги перечитываются, лишь когда файл менялся.
 * Любое другое время — изменение, в том числе более раннее: файл могли
 * восстановить из резервной копии.
 *
 * @property changed новые и изменённые файлы: их теги нужно прочитать и записать.
 * @property missing известные файлы, которых больше нет.
 */
data class ScanDiff(
    val changed: Set<Long>,
    val missing: Set<Long>,
) {
    companion object {
        /**
         * @param known `id` → время изменения из хранилища.
         * @param found `id` → время изменения из `MediaStore` сейчас.
         */
        fun of(
            known: Map<Long, Long>,
            found: Map<Long, Long>,
        ): ScanDiff =
            ScanDiff(
                changed = found.filter { (id, modifiedAt) -> known[id] != modifiedAt }.keys,
                missing = known.keys - found.keys,
            )
    }
}
