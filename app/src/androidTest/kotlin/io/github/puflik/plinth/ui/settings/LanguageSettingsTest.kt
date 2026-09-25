package io.github.puflik.plinth.ui.settings

import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.filters.SdkSuppress
import org.junit.Rule
import org.junit.Test

/**
 * Экрана «Язык приложения» нет (ревью №8): так бывает на прошивках Android 13,
 * а до 13-го его нет нигде — эмулятор API 30 и есть такой телефон. Касание
 * пункта «Язык» не роняет приложение.
 */
@SdkSuppress(maxSdkVersion = Build.VERSION_CODES.S_V2)
class LanguageSettingsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @SuppressLint("NewApi") // Нарочно: вызов там, где экрана языка нет.
    @Test
    fun missing_language_screen_does_not_crash_the_app() {
        compose.runOnUiThread { openLanguageSettings(compose.activity) }
    }
}
