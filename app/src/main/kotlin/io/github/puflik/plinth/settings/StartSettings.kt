package io.github.puflik.plinth.settings

import kotlinx.coroutines.flow.Flow

/** Какой экран открывать при запуске (F3); пока ничего не выбрано — [StartScreen.AUTO]. */
interface StartSettings {
    val startScreen: Flow<StartScreen>

    suspend fun setStartScreen(screen: StartScreen)
}
