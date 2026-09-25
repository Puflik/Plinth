package io.github.puflik.plinth.ui.common

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.audio.PlaybackController
import io.github.puflik.plinth.core.ErrorNotice
import io.github.puflik.plinth.core.ErrorPresenter
import io.github.puflik.plinth.ffi.CoreErrors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import javax.inject.Inject

/** Ошибки воспроизведения и отказы ядра, о которых стоит сказать (G3, A3); говорить ли — решает [ErrorPresenter]. */
@HiltViewModel
class ErrorNoticesViewModel
    @Inject
    constructor(
        playback: PlaybackController,
        core: CoreErrors,
        presenter: ErrorPresenter,
    ) : ViewModel() {
        val notices: Flow<ErrorNotice> = merge(playback.errors, core.events).mapNotNull(presenter::present)
    }
