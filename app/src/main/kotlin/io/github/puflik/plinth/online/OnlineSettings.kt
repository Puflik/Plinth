package io.github.puflik.plinth.online

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Переключатель «Онлайн-источники» (E3): включён по умолчанию. */
interface OnlineSettings {
    val enabled: Flow<Boolean>

    suspend fun setEnabled(enabled: Boolean)
}

/** [OnlineSettings] в DataStore. */
class DataStoreOnlineSettings(
    private val store: DataStore<Preferences>,
) : OnlineSettings {
    override val enabled: Flow<Boolean> = store.data.map { it[ENABLED] ?: true }.distinctUntilChanged()

    override suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[ENABLED] = enabled }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("online_sources")
    }
}
