package io.github.puflik.plinth.library.permission

/**
 * Что с разрешением на чтение музыки (C1.1) и что показать пользователю.
 *
 * Android не говорит прямо, отказано ли навсегда: это выводится из того,
 * спрашивали ли мы, и из `shouldShowRequestPermissionRationale`.
 */
enum class PermissionState {
    Granted,

    /** Не спрашивали — или отказали навсегда раньше: узнать можно, только спросив. */
    NotRequested,

    /** Отказано, но спросить можно снова — сначала объяснив зачем. */
    Denied,

    /** Система больше не покажет диалог: остаются только настройки приложения. */
    PermanentlyDenied,
    ;

    companion object {
        /**
         * @param requested ответ на запрос уже пришёл — в этот запуск экрана.
         * @param showRationale `shouldShowRequestPermissionRationale`: `true`
         *   после первого отказа, `false` — до первого запроса и после отказа
         *   навсегда (второй отказ на Android 11+ или «Больше не спрашивать»).
         */
        fun of(
            granted: Boolean,
            requested: Boolean,
            showRationale: Boolean,
        ): PermissionState =
            when {
                granted -> Granted
                showRationale -> Denied
                requested -> PermanentlyDenied
                else -> NotRequested
            }
    }
}
