package io.github.puflik.plinth.audio.media3

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

/**
 * Плеер сессии (D, шаг 3): «следующий» и «предыдущий» уходят в очередь
 * приложения — у ExoPlayer всегда один трек, своих соседей у него нет.
 */
class QueueCommandsPlayerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val calls = mutableListOf<String>()
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var player: QueueCommandsPlayer

    private fun create() =
        instrumentation.runOnMainSync {
            exoPlayer = ExoPlayer.Builder(instrumentation.targetContext).build()
            player = QueueCommandsPlayer(exoPlayer, onNext = { calls += "next" }, onPrevious = { calls += "previous" })
        }

    @After
    fun release() = instrumentation.runOnMainSync { exoPlayer.release() }

    @Test
    fun next_and_previous_are_always_available() {
        create()

        instrumentation.runOnMainSync {
            for (command in QUEUE_COMMANDS) {
                assertThat(player.isCommandAvailable(command)).isTrue()
                assertThat(player.availableCommands.contains(command)).isTrue()
            }
        }
    }

    @Test
    fun next_and_previous_go_to_the_queue() {
        create()

        instrumentation.runOnMainSync {
            player.seekToNext()
            player.seekToNextMediaItem()
            player.seekToPrevious()
            player.seekToPreviousMediaItem()
        }

        assertThat(calls).containsExactly("next", "next", "previous", "previous").inOrder()
    }

    @Test
    fun listeners_see_next_and_previous_when_the_player_changes_its_commands() {
        create()
        val reported = mutableListOf<Player.Commands>()
        val removedHeard = mutableListOf<Player.Commands>()
        val listener = commandsListener(reported)
        val removed = commandsListener(removedHeard)

        instrumentation.runOnMainSync {
            player.addListener(removed)
            player.removeListener(removed)
            player.addListener(listener)
            exoPlayer.setMediaItem(MediaItem.fromUri("file:///plinth-test/silence.wav"))
        }
        instrumentation.waitForIdleSync()

        assertThat(reported).isNotEmpty()
        assertThat(reported.last().contains(Player.COMMAND_SEEK_TO_NEXT)).isTrue()
        assertThat(removedHeard).isEmpty()
    }

    @Test
    fun listeners_still_hear_everything_else() {
        create()
        val transitions = mutableListOf<MediaItem?>()
        val listener =
            object : Player.Listener {
                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    transitions += mediaItem
                }
            }
        val item = MediaItem.fromUri("file:///plinth-test/silence.wav")

        instrumentation.runOnMainSync {
            player.addListener(listener)
            exoPlayer.setMediaItem(item)
        }
        instrumentation.waitForIdleSync()

        assertThat(transitions).containsExactly(item)
    }

    private fun commandsListener(into: MutableList<Player.Commands>) =
        object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                into += availableCommands
            }
        }

    private companion object {
        val QUEUE_COMMANDS =
            listOf(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
    }
}
