package io.github.puflik.plinth.library

import kotlinx.coroutines.flow.Flow

/**
 * Фоновый скан фонотеки глазами экранов (C2.4).
 *
 * Лежит рядом с [LibraryRepository], а не в `library/scan`: экранам сканер
 * не виден (`LibraryBoundaryTest`). Сканирует ядро на Rust (D3c), экраны
 * управляют сканом так же, как в v0.1.
 */
interface LibraryScan {
    /** Состояние последнего скана; новый подписчик сразу получает текущее. */
    val progress: Flow<ScanProgress>

    /** Запускает скан, если он ещё не идёт. */
    fun start()

    fun cancel()
}

/** Что показать о скане (C2.4). */
sealed interface ScanProgress {
    /** Скана не было, или его отменили. */
    data object Idle : ScanProgress

    /**
     * Скан идёт: обработано [written] из [total] новых и изменённых файлов.
     * Пока файлы ищутся, оба — ноль.
     */
    data class Running(
        val written: Int,
        val total: Int,
    ) : ScanProgress

    /** Скан закончен: в сканируемых папках [found] треков. */
    data class Done(
        val found: Int,
    ) : ScanProgress

    data object Failed : ScanProgress
}
