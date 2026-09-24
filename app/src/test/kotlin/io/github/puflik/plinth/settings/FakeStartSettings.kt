package io.github.puflik.plinth.settings

import kotlinx.coroutines.flow.MutableStateFlow

/** Стартовый экран в памяти для тестов. */
class FakeStartSettings(
    initial: StartScreen = StartScreen.AUTO,
) : StartSettings {
    override val startScreen = MutableStateFlow(initial)

    override suspend fun setStartScreen(screen: StartScreen) {
        startScreen.value = screen
    }
}
