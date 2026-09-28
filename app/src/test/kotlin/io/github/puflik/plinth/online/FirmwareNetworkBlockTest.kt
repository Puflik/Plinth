package io.github.puflik.plinth.online

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FirmwareNetworkBlockTest {
    /** TECNO CAMON 30 автора: `Build.MANUFACTURER` — «TECNO». */
    @Test
    fun `transsion phones with a live network are suspected`() {
        listOf("TECNO", "Infinix", "itel", " tecno ").forEach {
            assertThat(FirmwareNetworkBlock.suspected(it, networkValidated = true)).isTrue()
        }
    }

    /** Без проверенной сети «Нет сети» — правда, прошивка ни при чём. */
    @Test
    fun `no network is not blamed on the firmware`() {
        assertThat(FirmwareNetworkBlock.suspected("TECNO", networkValidated = false)).isFalse()
    }

    @Test
    fun `other makers are not suspected`() {
        listOf("Xiaomi", "samsung", "Google", "").forEach {
            assertThat(FirmwareNetworkBlock.suspected(it, networkValidated = true)).isFalse()
        }
    }
}
