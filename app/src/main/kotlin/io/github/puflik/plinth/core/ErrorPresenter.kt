package io.github.puflik.plinth.core

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
 * - Отказ ядра — паника или сломанное хранилище (не при записи правки, ниже)
 *   — один раз за запуск: «что-то пошло не так» и предложение сохранить лог.
 *   Сеть, мусор
 *   провайдера и пропавший трек объясняют сами экраны (баннер «офлайн»,
 *   серый трек) — строка внизу для них лишняя.
 * - Правка (лайк, плейлист…) не записалась из-за хранилища — вероятно,
 *   кончилось место — «не сохранено»: о каждой неудаче, а не раз за запуск,
 *   но не чаще раза в [NOT_SAVED_EVERY] — повторные касания сердца на полном
 *   диске не копят очередь строк. Помнит это отдельно от «что-то пошло не
 *   так»: одно не гасит другое. Паника при записи — по-прежнему общее
 *   «что-то пошло не так»: её надо отдать разработчику, а не списать на место.
 * - База собрана заново из журнала — один раз: пустые списки иначе
 *   напугали бы, а лайки и плейлисты на месте.
 * - Журнал начат заново (снимок не читался) — один раз: человек должен
 *   знать, что лайки и плейлисты пересобраны по библиотеке или что их надо
 *   вернуть из копии в папке.
 *
 * Помнит, о каких файлах сказано, поэтому один на процесс.
 */
@Singleton
class ErrorPresenter
    @Inject
    constructor(
        private val clock: Clock,
    ) {
        constructor() : this(Clock.System)

        private val told = mutableSetOf<String>()
        private var toldCoreFailed = false
        private var toldRestored = false
        private var toldJournalStartedOver = false
        private var toldNotSavedAt: Instant? = null

        /** Что сказать о [error]; `null` — ничего, только лог. */
        fun present(error: AppError): ErrorNotice? =
            when (error) {
                is AppError.TracksSkipped -> if (error.whileRestoring) null else skipped(error.tracks)
                is AppError.PlaybackStopped ->
                    if (error.whileRestoring) null else ErrorNotice.Stopped(error.track, error.skipped.size + 1)
                is AppError.CoreFailed -> coreFailed(error)
                AppError.LibraryRestored ->
                    if (toldRestored) {
                        null
                    } else {
                        toldRestored = true
                        ErrorNotice.LibraryRestored
                    }
                is AppError.JournalStartedOver ->
                    if (toldJournalStartedOver) {
                        null
                    } else {
                        toldJournalStartedOver = true
                        ErrorNotice.JournalStartedOver(error.carried)
                    }
            }

        private fun coreFailed(error: AppError.CoreFailed): ErrorNotice? =
            when (error.problem) {
                CoreProblem.STORAGE -> if (error.saving) notSaved() else coreFailedOnce()
                CoreProblem.INTERNAL -> coreFailedOnce()
                CoreProblem.NETWORK, CoreProblem.PARSE, CoreProblem.UNAVAILABLE -> null
            }

        private fun coreFailedOnce(): ErrorNotice? =
            if (toldCoreFailed) {
                null
            } else {
                toldCoreFailed = true
                ErrorNotice.CoreFailed
            }

        /** Часы пошли назад — говорим снова: молчать до «будущего» хуже лишней строки. */
        private fun notSaved(): ErrorNotice? {
            val now = clock.now()
            val last = toldNotSavedAt
            if (last != null && now >= last && now - last < NOT_SAVED_EVERY) return null
            toldNotSavedAt = now
            return ErrorNotice.NotSaved
        }

        private fun skipped(tracks: List<FailedTrack>): ErrorNotice? {
            val fresh = tracks.filter { told.add(it.file) }
            return fresh.firstOrNull()?.let { ErrorNotice.Skipped(it, fresh.size) }
        }

        companion object {
            /** Как часто можно повторять «не сохранено» на полном диске. */
            val NOT_SAVED_EVERY = 10.seconds
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

    /** Правка (лайк, плейлист…) не записалась: вероятно, на устройстве кончилось место. */
    data object NotSaved : ErrorNotice

    /** База собрана заново: лайки и плейлисты на месте, треки вернёт скан. */
    data object LibraryRestored : ErrorNotice

    /**
     * Журнал начат заново: [carried] — лайки и плейлисты пересобраны по
     * библиотеке; иначе их надо вернуть из копии в папке.
     */
    data class JournalStartedOver(
        val carried: Boolean,
    ) : ErrorNotice
}
