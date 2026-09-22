package io.github.puflik.plinth.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource

/**
 * Вкладки `Библиотека` · `Поиск` · `Настройки` (A2.3).
 *
 * Состав и порядок вкладок задаёт [Destination.tabs], а не этот файл.
 */
@Composable
fun BottomNavigationBar(
    currentRoute: String?,
    onDestinationSelected: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        Destination.tabs.forEach { destination ->
            val label = stringResource(destination.labelRes)
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { onDestinationSelected(destination) },
                icon = {
                    Icon(
                        painter = painterResource(destination.iconRes),
                        contentDescription = null,
                    )
                },
                label = { Text(text = label) },
            )
        }
    }
}
