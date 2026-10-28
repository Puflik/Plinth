package io.github.puflik.plinth.radio

import io.github.puflik.plinth.core.AppError
import io.github.puflik.plinth.core.RadioProblem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Вести о радио (Ю4в): [RadioStarter] и [RadioFeeder] говорят, `ErrorNoticesViewModel` отдаёт их строке внизу. */
@Singleton
class RadioNotices
    @Inject
    constructor() {
        private val mutableErrors = MutableSharedFlow<AppError>(extraBufferCapacity = BUFFER)

        /** Сказанное после подписки; подписчика нет — сказать некому. */
        val errors: SharedFlow<AppError> = mutableErrors.asSharedFlow()

        fun tell(problem: RadioProblem) {
            mutableErrors.tryEmit(AppError.Radio(problem))
        }

        private companion object {
            const val BUFFER = 8
        }
    }
