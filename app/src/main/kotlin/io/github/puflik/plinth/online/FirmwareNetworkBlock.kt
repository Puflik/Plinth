package io.github.puflik.plinth.online

import io.github.puflik.plinth.diagnostics.vendor.SettingsTarget
import io.github.puflik.plinth.diagnostics.vendor.Vendor

/**
 * Н7 (приёмка 0.2.0): прошивки Transsion — HiOS у TECNO, XOS у Infinix,
 * itelOS — закрывают приложению сеть своим брандмауэром (Phone Master), и
 * Android об этом не знает: сеть есть и проверена, а соединения приложения
 * рвутся. После обновления запрет держится, даже когда в списке Phone Master
 * сеть разрешена. «Нет сети» при живой сети на таком телефоне — повод
 * показать, где это разрешить.
 */
object FirmwareNetworkBlock {
    /** «Управление сетями» Phone Master: переключатели «Данные» и «Wi-Fi» у каждого приложения. */
    val SETTINGS = SettingsTarget("com.transsion.phonemaster", "com.transsion.networkcontrol.view.NetWorkRuleActivity")

    /** По `Build.MANUFACTURER` и проверенной сети Android: без сети это просто «нет сети». */
    fun suspected(
        manufacturer: String,
        networkValidated: Boolean,
    ): Boolean = networkValidated && Vendor.of(manufacturer) == Vendor.TRANSSION
}
