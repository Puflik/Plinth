package io.github.puflik.plinth.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Показывать ли ошибку (G3, план 17.6): тихо, где система справилась сама, и не повторяться. */
class ErrorPresenterTest {
    private val presenter = ErrorPresenter()
    private val gone = FailedTrack("Yesterday", "content://media/external/audio/media/1", TrackProblem.UNAVAILABLE)
    private val broken = FailedTrack("Help!", "content://media/external/audio/media/2", TrackProblem.UNPLAYABLE)
    private val lost = FailedTrack("Radio", "https://radio.example/live", TrackProblem.NETWORK)

    @Test
    fun `skipped tracks are told once with the first of them and how many`() {
        val notice = presenter.present(AppError.TracksSkipped(listOf(gone, broken)))

        assertThat(notice).isEqualTo(ErrorNotice.Skipped(gone, count = 2))
    }

    /** Битый файл в очереди с повтором — сообщение один раз, не на каждом круге. */
    @Test
    fun `a file is told about once per launch`() {
        presenter.present(AppError.TracksSkipped(listOf(broken)))

        assertThat(presenter.present(AppError.TracksSkipped(listOf(broken)))).isNull()
        assertThat(presenter.present(AppError.TracksSkipped(listOf(broken, gone))))
            .isEqualTo(ErrorNotice.Skipped(gone, count = 1))
    }

    /** Сохранённую очередь вернула система, человек звука не ждал — сама справилась. */
    @Test
    fun `skips while restoring the queue are quiet and told later`() {
        assertThat(presenter.present(AppError.TracksSkipped(listOf(gone), whileRestoring = true))).isNull()

        assertThat(presenter.present(AppError.TracksSkipped(listOf(gone)))).isEqualTo(ErrorNotice.Skipped(gone, 1))
    }

    /** Звук кончился — человеку выбирать, что дальше, даже если о файле уже говорили. */
    @Test
    fun `a stop is always told with every track that failed in a row`() {
        presenter.present(AppError.TracksSkipped(listOf(broken)))

        assertThat(presenter.present(AppError.PlaybackStopped(lost))).isEqualTo(ErrorNotice.Stopped(lost, count = 1))
        assertThat(presenter.present(AppError.PlaybackStopped(broken, skipped = listOf(gone))))
            .isEqualTo(ErrorNotice.Stopped(broken, count = 2))
    }

    @Test
    fun `a stop while restoring is left to the player screen`() {
        assertThat(presenter.present(AppError.PlaybackStopped(gone, whileRestoring = true))).isNull()
    }

    /** План 17.6: паника и сломанное хранилище — «что-то пошло не так» и сохранить лог. Один раз за запуск. */
    @Test
    fun `a core failure is told once per launch`() {
        assertThat(presenter.present(AppError.CoreFailed(CoreProblem.INTERNAL))).isEqualTo(ErrorNotice.CoreFailed)

        assertThat(presenter.present(AppError.CoreFailed(CoreProblem.INTERNAL))).isNull()
        assertThat(presenter.present(AppError.CoreFailed(CoreProblem.STORAGE))).isNull()
    }

    @Test
    fun `a broken storage is told like a panic`() {
        assertThat(presenter.present(AppError.CoreFailed(CoreProblem.STORAGE))).isEqualTo(ErrorNotice.CoreFailed)
    }

    /**
     * Сеть — баннер «офлайн», мусор провайдера — ничего, пропавший трек — серый
     * на экране (план 17.6): строка внизу тут не нужна.
     */
    @Test
    fun `core problems the screens explain themselves are quiet`() {
        for (problem in listOf(CoreProblem.NETWORK, CoreProblem.PARSE, CoreProblem.UNAVAILABLE)) {
            assertThat(presenter.present(AppError.CoreFailed(problem))).isNull()
        }
    }
}
