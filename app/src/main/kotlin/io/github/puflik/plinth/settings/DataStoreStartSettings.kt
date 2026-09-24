package io.github.puflik.plinth.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * [StartSettings] в DataStore: имя варианта строкой. Вариант, которого эта
 * версия не знает (скажем, «Открытия» из будущей версии), читается как «Авто».
 */
class DataStoreStartSettings
    @Inject
    constructor(
        private val store: DataStore<Preferences>,
    ) : StartSettings {
        override val startScreen: Flow<StartScreen> =
            store.data
                .map { preferences ->
                    StartScreen.entries.find { it.name == preferences[START_SCREEN] } ?: StartScreen.AUTO
                }.distinctUntilChanged()

        override suspend fun setStartScreen(screen: StartScreen) {
            store.edit { it[START_SCREEN] = screen.name }
        }

        private companion object {
            val START_SCREEN = stringPreferencesKey("start_screen")
        }
    }
