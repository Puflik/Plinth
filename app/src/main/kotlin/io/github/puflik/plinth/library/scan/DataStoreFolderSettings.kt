package io.github.puflik.plinth.library.scan

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.puflik.plinth.library.FolderSettings
import io.github.puflik.plinth.library.model.FolderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Выбор папок в DataStore (C2.5): два набора строк. Ключа нет — пользователь
 * ничего не менял, действует [FolderConfig.DEFAULT]; пустой набор — выбор
 * «ничего», он не подменяется умолчанием. Порядок набор не хранит, поэтому
 * папки читаются по алфавиту.
 */
class DataStoreFolderSettings
    @Inject
    constructor(
        private val store: DataStore<Preferences>,
    ) : FolderSettings {
        override val folders: Flow<FolderConfig> = store.data.map(::read)

        override suspend fun update(transform: (FolderConfig) -> FolderConfig) {
            store.edit { preferences ->
                val next = transform(read(preferences))
                if (next == FolderConfig.DEFAULT) {
                    // Умолчание — это отсутствие выбора, а не выбор, равный умолчанию.
                    preferences.remove(INCLUDED)
                    preferences.remove(EXCLUDED)
                } else {
                    preferences[INCLUDED] = next.included.toSet()
                    preferences[EXCLUDED] = next.excluded.toSet()
                }
            }
        }

        private fun read(preferences: Preferences) =
            FolderConfig(
                included = preferences[INCLUDED]?.sorted() ?: FolderConfig.DEFAULT.included,
                excluded = preferences[EXCLUDED]?.sorted() ?: FolderConfig.DEFAULT.excluded,
            )

        private companion object {
            val INCLUDED = stringSetPreferencesKey("included_folders")
            val EXCLUDED = stringSetPreferencesKey("excluded_folders")
        }
    }
