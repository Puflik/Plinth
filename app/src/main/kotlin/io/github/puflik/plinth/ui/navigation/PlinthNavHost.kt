package io.github.puflik.plinth.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import io.github.puflik.plinth.R
import io.github.puflik.plinth.ui.player.FilePlayerScreen

/**
 * Граф навигации (A2.1).
 *
 * Вместо библиотеки пока плеер первой вертикали («файл → звук»); поиск
 * приходит с C4, настройки с F — до тех пор заглушки. Граф заведён сейчас, чтобы им было куда встать.
 */
@Composable
fun PlinthNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Destination.START.route,
        modifier = modifier,
    ) {
        composable(Destination.Library.route) {
            // Библиотеки ещё нет (эпик C) — на её месте плеер первой вертикали.
            FilePlayerScreen()
        }
        composable(Destination.Search.route) {
            PlaceholderScreen(R.string.placeholder_search)
        }
        composable(Destination.Settings.route) {
            PlaceholderScreen(R.string.placeholder_settings)
        }
    }
}

@Composable
private fun PlaceholderScreen(
    @StringRes textRes: Int,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}
