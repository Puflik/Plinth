package io.github.puflik.plinth.audio.media3

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.IdentityHashMap

/**
 * Плеер для сессии (D, шаг 3): «следующий» и «предыдущий» с уведомления,
 * экрана блокировки и гарнитуры уходят в очередь приложения.
 *
 * Очередь живёт в `PlaybackController`, а ExoPlayer всегда держит один трек —
 * своих соседей у него нет, и без обёртки эти кнопки ничего бы не делали или
 * вовсе не показывались. Поэтому команды перехода объявлены доступными всегда;
 * конец очереди `PlaybackController` обрабатывает сам.
 *
 * Сессия узнаёт о командах не только спросив плеер, но и из события
 * `onAvailableCommandsChanged`, которое `ForwardingPlayer` пересылает как
 * есть — с командами ExoPlayer. Поэтому слушатели получают событие уже с
 * командами очереди.
 */
class QueueCommandsPlayer(
    player: Player,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands = withQueueCommands(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean =
        command in QUEUE_COMMANDS || super.isCommandAvailable(command)

    override fun seekToNext() = onNext()

    override fun seekToNextMediaItem() = onNext()

    override fun seekToPrevious() = onPrevious()

    override fun seekToPreviousMediaItem() = onPrevious()

    // ForwardingPlayer узнаёт слушателя при отписке по экземпляру (IdentityHashMap),
    // поэтому отписывать надо ту самую обёртку, что подписали.
    private val wrappers = IdentityHashMap<Player.Listener, Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        val wrapper = queueCommandsListener(listener)
        wrappers[listener] = wrapper
        super.addListener(wrapper)
    }

    override fun removeListener(listener: Player.Listener) {
        wrappers.remove(listener)?.let { super.removeListener(it) }
    }

    private companion object {
        fun withQueueCommands(commands: Player.Commands): Player.Commands =
            commands.buildUpon().addAll(*QUEUE_COMMANDS).build()

        /**
         * Слушатель, который видит команды очереди, а всё остальное получает
         * как есть. Прокси, а не `Player.Listener by listener`: все методы
         * слушателя — default-методы Java, и делегирование Kotlin их не
         * пересылает — сессия не узнала бы ни о состоянии, ни о треке.
         * Равенство — по экземпляру: по нему подписку ищут при отписке.
         */
        fun queueCommandsListener(listener: Player.Listener): Player.Listener =
            Proxy.newProxyInstance(
                Player.Listener::class.java.classLoader,
                arrayOf(Player.Listener::class.java),
            ) { proxy, method, args ->
                val arguments = args.orEmpty()
                when (method.name) {
                    "equals" -> proxy === arguments.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "QueueCommandsListener($listener)"
                    "onAvailableCommandsChanged" ->
                        listener.onAvailableCommandsChanged(withQueueCommands(arguments[0] as Player.Commands))
                    else ->
                        try {
                            method.invoke(listener, *arguments)
                        } catch (thrown: InvocationTargetException) {
                            throw thrown.targetException
                        }
                }
            } as Player.Listener

        val QUEUE_COMMANDS =
            intArrayOf(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
    }
}
