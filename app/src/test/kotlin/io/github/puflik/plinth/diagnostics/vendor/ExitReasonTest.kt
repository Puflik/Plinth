package io.github.puflik.plinth.diagnostics.vendor

import android.app.ApplicationExitInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Причина прошлого выхода по `ApplicationExitInfo` (G2.1, Т1 приёмки v0.1.1). */
class ExitReasonTest {
    @Test
    fun `a stop by the user is the user`() {
        assertThat(ExitReason.of(ApplicationExitInfo.REASON_USER_REQUESTED, null)).isEqualTo(ExitReason.USER)
        assertThat(ExitReason.of(ApplicationExitInfo.REASON_USER_STOPPED, null)).isEqualTo(ExitReason.USER)
    }

    @Test
    fun `our crash or a hang is a crash`() {
        assertThat(ExitReason.of(ApplicationExitInfo.REASON_CRASH, null)).isEqualTo(ExitReason.CRASH)
        assertThat(ExitReason.of(ApplicationExitInfo.REASON_CRASH_NATIVE, null)).isEqualTo(ExitReason.CRASH)
        assertThat(ExitReason.of(ApplicationExitInfo.REASON_ANR, null)).isEqualTo(ExitReason.CRASH)
    }

    @Test
    fun `an update or a permission change is an update`() {
        assertThat(ExitReason.of(ApplicationExitInfo.REASON_PACKAGE_UPDATED, null)).isEqualTo(ExitReason.UPDATE)
        assertThat(ExitReason.of(ApplicationExitInfo.REASON_PERMISSION_CHANGE, null)).isEqualTo(ExitReason.UPDATE)
    }

    @Test
    fun `low memory, a signal and other kills are the system`() {
        assertThat(ExitReason.of(ApplicationExitInfo.REASON_LOW_MEMORY, null)).isEqualTo(ExitReason.SYSTEM)
        assertThat(ExitReason.of(ApplicationExitInfo.REASON_SIGNALED, null)).isEqualTo(ExitReason.SYSTEM)
        assertThat(ExitReason.of(ApplicationExitInfo.REASON_OTHER, "freeze")).isEqualTo(ExitReason.SYSTEM)
    }

    @Test
    fun `a swipe from recents on HiOS is the user, not a kill`() {
        // TECNO CAMON 30, HiOS 16: reason=13 (OTHER KILLS BY SYSTEM), subreason=101 (Swipe-up clean).
        assertThat(ExitReason.of(ApplicationExitInfo.REASON_OTHER, "manual_swipUpClean")).isEqualTo(ExitReason.USER)
    }
}
