package io.github.puflik.plinth.startup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * [OnboardingSettings] в DataStore: флаг «пройден» и имена пропущенных
 * шагов. Шаг, которого эта версия не знает (переименовали или убрали),
 * отбрасывается.
 */
class DataStoreOnboardingSettings
    @Inject
    constructor(
        private val store: DataStore<Preferences>,
    ) : OnboardingSettings {
        override val record: Flow<OnboardingRecord> =
            store.data
                .map { preferences ->
                    OnboardingRecord(
                        finished = preferences[FINISHED] == true,
                        skipped =
                            preferences[SKIPPED]
                                .orEmpty()
                                .mapNotNull { name -> OnboardingStep.entries.find { it.name == name } }
                                .toSet(),
                    )
                }.distinctUntilChanged()

        override suspend fun finish(skipped: Set<OnboardingStep>) {
            store.edit { preferences ->
                preferences[FINISHED] = true
                preferences[SKIPPED] = skipped.map { it.name }.toSet()
            }
        }

        private companion object {
            val FINISHED = booleanPreferencesKey("onboarding_finished")
            val SKIPPED = stringSetPreferencesKey("onboarding_skipped")
        }
    }
