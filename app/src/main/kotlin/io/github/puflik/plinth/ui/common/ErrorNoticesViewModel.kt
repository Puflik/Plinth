package io.github.puflik.plinth.ui.common

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.core.ErrorNotice
import io.github.puflik.plinth.core.ErrorPresenter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject

/** Ошибки воспроизведения, о которых стоит сказать (G3); говорить ли — решает [ErrorPresenter]. */
@HiltViewModel
class ErrorNoticesViewModel
    @Inject
    constructor(
        playback: PlaybackController,
        presenter: ErrorPresenter,
    ) : ViewModel() {
        val notices: Flow<ErrorNotice> = playback.errors.mapNotNull(presenter::present)
    }
