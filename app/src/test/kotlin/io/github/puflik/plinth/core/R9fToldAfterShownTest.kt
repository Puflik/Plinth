package io.github.puflik.plinth.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Р9е (`docs/work/r9f.md`, ревью R1 №8): весть о восстановлении считается сказанной, когда
 * строка показана, а не когда её отдали экрану. Поворот экрана или уход в фон отменяет
 * начатую строку; при новом сборе весть приходит снова, и если бы презентер уже считал её
 * сказанной, человек не узнал бы о ней никогда.
 */
class R9fToldAfterShownTest {
    private val presenter = ErrorPresenter()
    private val lost = AppError.JournalStartedOver(carried = false)

    @Test
    fun `a journal started over is offered again until it has been shown`() {
        val first = presenter.present(lost)
        val afterRotation = presenter.present(lost)

        assertThat(first).isEqualTo(ErrorNotice.JournalStartedOver(carried = false))
        assertThat(afterRotation).isEqualTo(first)
    }

    @Test
    fun `a journal started over shown once is not told again`() {
        val notice = checkNotNull(presenter.present(lost))

        presenter.shown(notice)

        assertThat(presenter.present(lost)).isNull()
        assertThat(presenter.present(AppError.JournalStartedOver(carried = true))).isNull()
    }

    @Test
    fun `a restored library is offered again until it has been shown`() {
        assertThat(presenter.present(AppError.LibraryRestored)).isEqualTo(ErrorNotice.LibraryRestored)
        assertThat(presenter.present(AppError.LibraryRestored)).isEqualTo(ErrorNotice.LibraryRestored)

        presenter.shown(ErrorNotice.LibraryRestored)

        assertThat(presenter.present(AppError.LibraryRestored)).isNull()
    }

    @Test
    fun `showing one kind does not silence the other`() {
        presenter.shown(ErrorNotice.LibraryRestored)

        assertThat(presenter.present(lost)).isEqualTo(ErrorNotice.JournalStartedOver(carried = false))
    }

    @Test
    fun `showing a notice that is told by its own rules changes nothing`() {
        presenter.shown(ErrorNotice.NotSaved)
        presenter.shown(ErrorNotice.CoreFailed)

        assertThat(presenter.present(lost)).isNotNull()
        assertThat(presenter.present(AppError.CoreFailed(CoreProblem.INTERNAL))).isEqualTo(ErrorNotice.CoreFailed)
    }
}
