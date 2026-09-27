package io.github.puflik.plinth.backup

import io.github.puflik.plinth.ffi.MirrorFile
import io.github.puflik.plinth.ffi.MirrorFound
import io.github.puflik.plinth.ffi.MirrorRestore
import kotlinx.coroutines.flow.Flow

/**
 * Копия журнала пользовательских данных для папки человека (C4, план 17.5,
 * уровень 1): переживает переустановку без Google-аккаунта.
 *
 * Реализация — `CoreJournalMirror` поверх ядра; требования —
 * `JournalMirrorContractTest`, тот же, что проходит `FakeJournalMirror`.
 * Отказ ядра уже ушёл в `CoreErrors`: здесь вместо результата — `null`.
 */
interface JournalMirror {
    /** Сигнал «журнал изменился» — счётчик; писатель копии ждёт паузы в нём. */
    val changes: Flow<Long>

    /** Копия журнала этой установки; имя файла — по установке. */
    suspend fun copy(): MirrorFile?

    /** Что лежит в файлах из папки и есть ли новое; своя копия пропускается. */
    suspend fun inspect(files: List<MirrorFile>): MirrorFound?

    /**
     * Вливает [files] в журнал — слиянием: сделанное до этого не теряется.
     * Треки фонотеки узнаются по паспортам — и сейчас, и после скана.
     */
    suspend fun restore(files: List<MirrorFile>): MirrorRestore?
}
