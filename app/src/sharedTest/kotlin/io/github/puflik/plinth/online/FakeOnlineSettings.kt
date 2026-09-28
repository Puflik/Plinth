package io.github.puflik.plinth.online

import kotlinx.coroutines.flow.MutableStateFlow

/** [OnlineSettings] в памяти; как настоящие, включены по умолчанию. */
class FakeOnlineSettings : OnlineSettings {
    override val enabled = MutableStateFlow(true)

    override suspend fun setEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }
}
