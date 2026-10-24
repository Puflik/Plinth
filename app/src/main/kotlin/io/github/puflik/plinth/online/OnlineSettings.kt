package io.github.puflik.plinth.online

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Переключатель «Онлайн-источники» (E3) и выбор провайдеров (Ю3г): всё включено по умолчанию. */
interface OnlineSettings {
    val enabled: Flow<Boolean>

    suspend fun setEnabled(enabled: Boolean)

    /** ID провайдеров, которые человек выключил. Пусто — включены все; новый провайдер включён, пока его не выключат. */
    val disabledProviders: Flow<Set<String>>

    suspend fun setProviderEnabled(
        provider: String,
        enabled: Boolean,
    )
}

/**
 * [OnlineSettings] в DataStore. Выбор провайдеров хранится как набор *выключенных* (`online_providers_off`):
 * провайдер, которого в сохранённых данных нет, включён — новый после обновления играет сразу. Это формат данных
 * пользователя: после выпуска не менять без миграции.
 */
class DataStoreOnlineSettings(
    private val store: DataStore<Preferences>,
) : OnlineSettings {
    override val enabled: Flow<Boolean> = store.data.map { it[ENABLED] ?: true }.distinctUntilChanged()

    override val disabledProviders: Flow<Set<String>> =
        store.data.map { it[PROVIDERS_OFF].orEmpty() }.distinctUntilChanged()

    override suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[ENABLED] = enabled }
    }

    /**
     * Добавляет в набор или убирает из него ровно один ID — внутри одной правки; остальные не трогаются, и незнакомые
     * этой версии тоже (провайдер, которого здесь нет; откат версии). Пустой набор — ключ удаляется.
     */
    override suspend fun setProviderEnabled(
        provider: String,
        enabled: Boolean,
    ) {
        store.edit { preferences ->
            val off = preferences[PROVIDERS_OFF].orEmpty()
            val next = if (enabled) off - provider else off + provider
            if (next.isEmpty()) preferences.remove(PROVIDERS_OFF) else preferences[PROVIDERS_OFF] = next
        }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("online_sources")
        val PROVIDERS_OFF = stringSetPreferencesKey("online_providers_off")
    }
}
