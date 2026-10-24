package io.github.puflik.plinth.online

import kotlinx.coroutines.flow.MutableStateFlow

/** [OnlineSettings] в памяти; как настоящие, включены по умолчанию — и главный переключатель, и каждый провайдер. */
class FakeOnlineSettings : OnlineSettings {
    override val enabled = MutableStateFlow(true)

    override val disabledProviders = MutableStateFlow(emptySet<String>())

    override suspend fun setEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }

    override suspend fun setProviderEnabled(
        provider: String,
        enabled: Boolean,
    ) {
        val off = disabledProviders.value
        disabledProviders.value = if (enabled) off - provider else off + provider
    }
}
