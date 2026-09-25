package io.github.puflik.plinth.core

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Показывать ли ошибку человеку (G3, план 17.6): тихая деградация там, где
 * система справляется сама; сообщение — когда человеку есть что с этим
 * делать или он ждал звука и должен понять, почему играет другое.
 *
 * - Пропуск — о каждом файле один раз за запуск: очередь с повтором
 *   встречает битый файл на каждом круге, а говорить о нём каждый раз —
 *   шум.
 * - Остановка — всегда: звук кончился, выбирать дальше человеку.
 * - При возврате сохранённой очереди — ничего: звука никто не ждал, а
 *   остановку и так покажет экран плеера.
 * - Отказ ядра — паника или сломанное хранилище — один раз за запуск:
 *   «что-то пошло не так» и предложение сохранить лог. Сеть, мусор
 *   провайдера и пропавший трек объясняют сами экраны (баннер «офлайн»,
 *   серый трек) — строка внизу для них лишняя.
 * - База собрана заново из журнала — один раз: пустые списки иначе
 *   напугали бы, а лайки и плейлисты на месте.
 *
 * Помнит, о каких файлах сказано, поэтому один на процесс.
 */
@Singleton
class ErrorPresenter
    @Inject
    constructor() {
        private val told = mutableSetOf<String>()
        private var toldCoreFailed = false
        private var toldRestored = false

        /** Что сказать о [error]; `null` — ничего, только лог. */
        fun present(error: AppError): ErrorNotice? =
            when (error) {
                is AppError.TracksSkipped -> if (error.whileRestoring) null else skipped(error.tracks)
                is AppError.PlaybackStopped ->
                    if (error.whileRestoring) null else ErrorNotice.Stopped(error.track, error.skipped.size + 1)
                is AppError.CoreFailed -> coreFailed(error.problem)
                AppError.LibraryRestored ->
                    if (toldRestored) {
                        null
                    } else {
                        toldRestored = true
                        ErrorNotice.LibraryRestored
                    }
            }

        private fun coreFailed(problem: CoreProblem): ErrorNotice? =
            when (problem) {
                CoreProblem.INTERNAL, CoreProblem.STORAGE ->
                    if (toldCoreFailed) {
                        null
                    } else {
                        toldCoreFailed = true
                        ErrorNotice.CoreFailed
                    }
                CoreProblem.NETWORK, CoreProblem.PARSE, CoreProblem.UNAVAILABLE -> null
            }

        private fun skipped(tracks: List<FailedTrack>): ErrorNotice? {
            val fresh = tracks.filter { told.add(it.file) }
            return fresh.firstOrNull()?.let { ErrorNotice.Skipped(it, fresh.size) }
        }
    }

/** Сообщение об ошибке — что показать; слова подбирает экран. */
sealed interface ErrorNotice {
    /** Пропущены треки: [first] — первый из тех, о ком ещё не говорили, всего таких [count]. */
    data class Skipped(
        val first: FailedTrack,
        val count: Int,
    ) : ErrorNotice

    /** Игра остановилась на [track]; вместе с ним подряд не сыграли [count] треков. */
    data class Stopped(
        val track: FailedTrack,
        val count: Int,
    ) : ErrorNotice

    /** Ядро не справилось: «что-то пошло не так» и предложение сохранить лог. */
    data object CoreFailed : ErrorNotice

    /** База собрана заново: лайки и плейлисты на месте, треки вернёт скан. */
    data object LibraryRestored : ErrorNotice
}
