package io.github.puflik.plinth.diagnostics.vendor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Показан ли уже «Почему музыка останавливается» после убийства (G2.4): один раз. */
interface VendorPromptState {
    val shown: Flow<Boolean>

    suspend fun markShown()
}

/** [VendorPromptState] в DataStore — тот же файл, что у остальных настроек. */
class DataStoreVendorPromptState
    @Inject
    constructor(
        private val store: DataStore<Preferences>,
    ) : VendorPromptState {
        override val shown: Flow<Boolean> = store.data.map { it[SHOWN] == true }

        override suspend fun markShown() {
            store.edit { it[SHOWN] = true }
        }

        private companion object {
            val SHOWN = booleanPreferencesKey("vendor_guide_shown")
        }
    }
