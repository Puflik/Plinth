package io.github.puflik.plinth.startup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Мастер первого запуска показывается один раз, пропущенное помнится (F1). */
class DataStoreOnboardingSettingsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `nothing stored means the first launch`() =
        runTest {
            val settings = DataStoreOnboardingSettings(storeIn(this))

            assertThat(settings.record.first()).isEqualTo(OnboardingRecord.FIRST_LAUNCH)
            assertThat(OnboardingRecord.FIRST_LAUNCH.finished).isFalse()
        }

    @Test
    fun `finished wizard and its skipped steps are kept`() =
        runTest {
            val store = storeIn(this)
            DataStoreOnboardingSettings(store).finish(setOf(OnboardingStep.FOLDERS))

            assertThat(DataStoreOnboardingSettings(store).record.first())
                .isEqualTo(OnboardingRecord(finished = true, skipped = setOf(OnboardingStep.FOLDERS)))
        }

    @Test
    fun `step unknown to this version is dropped`() =
        runTest {
            val store = storeIn(this)
            DataStoreOnboardingSettings(store).finish(setOf(OnboardingStep.FOLDERS))
            store.edit { it[stringSetPreferencesKey("onboarding_skipped")] = setOf("FOLDERS", "PROVIDERS") }

            assertThat(DataStoreOnboardingSettings(store).record.first().skipped)
                .containsExactly(OnboardingStep.FOLDERS)
        }

    private fun storeIn(scope: TestScope) =
        PreferenceDataStoreFactory.create(scope = scope.backgroundScope) {
            temp.root.resolve("onboarding.preferences_pb")
        }
}
