package io.github.puflik.plinth.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.puflik.plinth.R
import io.github.puflik.plinth.library.permission.PermissionState
import io.github.puflik.plinth.startup.OnboardingStep
import io.github.puflik.plinth.ui.common.PermissionRationaleScreen
import io.github.puflik.plinth.ui.common.openAppSettings
import io.github.puflik.plinth.ui.common.rememberMediaPermission
import io.github.puflik.plinth.ui.settings.FolderSettingsViewModel
import io.github.puflik.plinth.ui.settings.folderChoice
import io.github.puflik.plinth.ui.settings.rememberFolderPicker

/**
 * Мастер первого запуска B+ (F1, план 12.4): шаг за шагом, внизу —
 * «Пропустить всё» на любом шаге и «Пропустить» у необязательных.
 *
 * Шаг разрешения сначала объясняет и спрашивает по кнопке, а проходит сам,
 * как только разрешение выдано. Папки правятся тем же списком, что и в
 * настройках; скан начнётся, когда откроется библиотека.
 */
@Composable
fun OnboardingScreen(
    step: OnboardingStep,
    viewModel: OnboardingViewModel,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        bottomBar = {
            OnboardingActions(
                step = step,
                onSkipAll = viewModel::onSkipAll,
                onSkip = viewModel::onSkip,
                onNext = viewModel::onNext,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp)) {
                Text(text = stringResource(R.string.onboarding_welcome), style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = stringResource(R.string.onboarding_step, step.ordinal + 1, OnboardingStep.entries.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (step) {
                OnboardingStep.PERMISSION -> PermissionStep(onPermission = viewModel::onPermission)
                OnboardingStep.FOLDERS -> FoldersStep()
            }
        }
    }
}

@Composable
private fun PermissionStep(onPermission: (PermissionState) -> Unit) {
    val context = LocalContext.current
    var permission by remember { mutableStateOf(PermissionState.NotRequested) }
    val request =
        rememberMediaPermission(askFirst = false) { state ->
            permission = state
            onPermission(state)
        }
    PermissionRationaleScreen(
        permanentlyDenied = permission == PermissionState.PermanentlyDenied,
        onRequest = request,
        onOpenSettings = { openAppSettings(context) },
    )
}

/** Выбор папок — Activity-овый [FolderSettingsViewModel]: мастер стоит вне навигации. */
@Composable
private fun FoldersStep(viewModel: FolderSettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val include = rememberFolderPicker(viewModel::onInclude)
    val exclude = rememberFolderPicker(viewModel::onExclude)
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "intro") {
            Column(
                modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_folders_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.onboarding_folders_text),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        folderChoice(state.folders, onRemove = viewModel::onRemove, onInclude = include, onExclude = exclude)
    }
}

/**
 * «Пропустить всё» — слева, на любом шаге. Справа — «Пропустить» у
 * необязательного шага и «Далее» («Готово» на последнем); шаг разрешения
 * кнопки «Далее» не имеет — он проходит сам, когда разрешение выдано.
 */
@Composable
private fun OnboardingActions(
    step: OnboardingStep,
    onSkipAll: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onSkipAll) { Text(stringResource(R.string.onboarding_skip_all)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step.skippable) TextButton(onClick = onSkip) { Text(stringResource(R.string.onboarding_skip)) }
            if (step != OnboardingStep.PERMISSION) {
                val label = if (step.next == null) R.string.onboarding_done else R.string.onboarding_next
                Button(onClick = onNext) { Text(stringResource(label)) }
            }
        }
    }
}
