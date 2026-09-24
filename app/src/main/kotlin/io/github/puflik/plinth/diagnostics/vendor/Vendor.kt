package io.github.puflik.plinth.diagnostics.vendor

/**
 * Производитель с точки зрения фоновых ограничений (G2.2, фича 141): у
 * суббрендов та же прошивка и те же «убийцы» — Redmi и POCO это MIUI/HyperOS,
 * Honor — EMUI, realme и OnePlus — ColorOS.
 *
 * @property brand как назвать производителя в тексте; `null` — не называть.
 * @property slug страница на dontkillmyapp.com.
 */
enum class Vendor(
    val brand: String?,
    val slug: String,
    private val manufacturers: Set<String>,
) {
    XIAOMI("Xiaomi", "xiaomi", setOf("xiaomi", "redmi", "poco")),
    SAMSUNG("Samsung", "samsung", setOf("samsung")),
    HUAWEI("Huawei", "huawei", setOf("huawei", "honor")),
    OPPO("OPPO", "oppo", setOf("oppo", "realme", "oneplus")),
    OTHER(null, "", emptySet()),
    ;

    companion object {
        /** По `Build.MANUFACTURER`, без регистра. */
        fun of(manufacturer: String): Vendor =
            entries.firstOrNull { manufacturer.trim().lowercase() in it.manufacturers } ?: OTHER
    }
}
