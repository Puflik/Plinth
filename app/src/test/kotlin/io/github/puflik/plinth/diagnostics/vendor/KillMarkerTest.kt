package io.github.puflik.plinth.diagnostics.vendor

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Метка «звук идёт» (G2.1): файл на диске, пока играет. */
class KillMarkerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `marker is set while playing and cleared after`() {
        val marker = KillMarker(temp.root.resolve("vendor"))

        marker.set()
        assertThat(marker.isSet()).isTrue()

        marker.clear()
        assertThat(marker.isSet()).isFalse()
    }

    @Test
    fun `marker that cannot be written is skipped without a throw`() {
        // Папку не создать — как на заполненном диске: запись не удаётся.
        val marker = KillMarker(temp.newFile("full").resolve("vendor"))

        marker.set()

        assertThat(marker.isSet()).isFalse()
    }

    /** Ревью №9: метка помнит загрузку системы — новый процесс видит, была ли перезагрузка. */
    @Test
    fun `marker left before a reboot tells about it`() {
        val directory = temp.root.resolve("vendor")
        KillMarker(directory, boot = { 7 }).set()

        assertThat(KillMarker(directory, boot = { 8 }).rebootedSinceSet()).isTrue()
        assertThat(KillMarker(directory, boot = { 7 }).rebootedSinceSet()).isFalse()
        // Номер загрузки неизвестен — перезагрузку не придумываем.
        assertThat(KillMarker(directory, boot = { null }).rebootedSinceSet()).isFalse()
    }

    @Test
    fun `marker set without a known boot is not a reboot`() {
        val directory = temp.root.resolve("vendor")
        KillMarker(directory, boot = { null }).set()

        assertThat(KillMarker(directory, boot = { 8 }).rebootedSinceSet()).isFalse()
    }
}
