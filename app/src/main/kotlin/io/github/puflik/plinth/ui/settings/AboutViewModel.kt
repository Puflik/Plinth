package io.github.puflik.plinth.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.diagnostics.AppInfo
import javax.inject.Inject

/** «О приложении» в настройках (Ю3г): сведения о сборке, которой пользуется человек. Они не меняются за запуск. */
@HiltViewModel
class AboutViewModel
    @Inject
    constructor(
        info: AppInfo,
    ) : ViewModel() {
        val details: AboutDetails = aboutDetails(info)
    }
