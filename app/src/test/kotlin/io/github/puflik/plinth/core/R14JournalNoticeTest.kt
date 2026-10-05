package io.github.puflik.plinth.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Р1.4 (`docs/work/r1-4.md`): журнал начат заново — сказать один раз. Что
 * сказать, зависит от того, перенесла ли база лайки и плейлисты: нет — их
 * может вернуть только копия в папке.
 */
class R14JournalNoticeTest {
    private val presenter = ErrorPresenter()

    @Test
    fun `a journal started over from the library is told once`() {
        assertThat(presenter.present(AppError.JournalStartedOver(carried = true)))
            .isEqualTo(ErrorNotice.JournalStartedOver(carried = true))
        assertThat(presenter.present(AppError.JournalStartedOver(carried = true))).isNull()
    }

    @Test
    fun `a journal started over with nothing carried is told so`() {
        assertThat(presenter.present(AppError.JournalStartedOver(carried = false)))
            .isEqualTo(ErrorNotice.JournalStartedOver(carried = false))
    }
}
