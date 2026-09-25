package io.github.puflik.plinth.library

import android.content.Context
import io.github.puflik.plinth.diagnostics.log.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Стирает базу фонотеки v0.1 на Room (D3c): с 0.2 фонотеку ведёт ядро, а
 * старый каталог `MediaStore` ему не нужен — скан соберёт свой. Лайков и
 * плейлистов в ней не было. Файла нет — делать нечего, поэтому шаг остаётся
 * при каждом старте: он стоит одного обращения к диску.
 */
class OldLibraryCleanup(
    private val context: Context,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    fun start(): Job =
        scope.launch(io) {
            // Вместе с базой — её журнал WAL и файл общей памяти.
            if (context.deleteDatabase(DATABASE)) AppLog.i(TAG, "library database of v0.1 deleted")
        }

    private companion object {
        const val TAG = "Library"
        const val DATABASE = "plinth.db"
    }
}
