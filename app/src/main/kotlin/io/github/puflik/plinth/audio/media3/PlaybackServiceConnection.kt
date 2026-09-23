package io.github.puflik.plinth.audio.media3

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * Держит `PlaybackService` поднятым, пока экран на виду (B3.1).
 *
 * UI управляет звуком через движок, а не через сессию, но службу кто-то
 * должен поднять: пока к ней подключён контроллер, она существует, а когда
 * звук пошёл, Media3 сам выводит её на передний план с уведомлением. Уход
 * экрана служба переживает, пока играет.
 */
class PlaybackServiceConnection(
    context: Context,
) : DefaultLifecycleObserver {
    private val context = context.applicationContext
    private var controller: ListenableFuture<MediaController>? = null

    override fun onStart(owner: LifecycleOwner) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controller = MediaController.Builder(context, token).buildAsync()
    }

    override fun onStop(owner: LifecycleOwner) {
        controller?.let(MediaController::releaseFuture)
        controller = null
    }
}
