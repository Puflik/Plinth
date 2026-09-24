package io.github.puflik.plinth.diagnostics.vendor

/** Экран настроек другого приложения — по имени пакета и класса. */
data class SettingsTarget(
    val packageName: String,
    val className: String,
)

/**
 * Куда вести за разрешением на фон (G2.3): экраны автозапуска и батареи у
 * каждого производителя свои, от версии к версии прошивки они переезжают —
 * поэтому список кандидатов, открывается первый существующий. Если ни один
 * не открылся — общие экраны Android (это решает вызывающий).
 * Адреса — по dontkillmyapp.com.
 */
object VendorIntents {
    private const val GUIDE = "https://dontkillmyapp.com/"

    fun targets(vendor: Vendor): List<SettingsTarget> =
        when (vendor) {
            Vendor.XIAOMI ->
                listOf(
                    SettingsTarget(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    ),
                    SettingsTarget("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
                )
            Vendor.SAMSUNG ->
                listOf(
                    SettingsTarget("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                    SettingsTarget("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                )
            Vendor.HUAWEI ->
                listOf(
                    SettingsTarget(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    ),
                    SettingsTarget(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity",
                    ),
                )
            Vendor.OPPO ->
                listOf(
                    SettingsTarget(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    ),
                    SettingsTarget("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                )
            Vendor.OTHER -> emptyList()
        }

    fun guideUrl(vendor: Vendor): String = GUIDE + vendor.slug
}
