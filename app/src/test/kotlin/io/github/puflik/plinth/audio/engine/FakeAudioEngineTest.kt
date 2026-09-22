package io.github.puflik.plinth.audio.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * `FakeAudioEngine` проходит общий контракт (B1.3, B1.4).
 *
 * Фейк — не игрушка: на нём тестируются `ViewModel` и `PlaybackController`,
 * поэтому он обязан вести себя как настоящий движок во всём, что описано
 * контрактом. Расхождение фейка с контрактом означает, что тесты верхних
 * слоёв доказывают не то.
 */
class FakeAudioEngineTest : AudioEngineContractTest() {
    private val engine = FakeAudioEngine()

    override fun createEngine(): AudioEngine = engine

    override fun playableSource(): AudioSource = AudioSource.LocalFile("content://plinth.test/track.flac")

    override fun unavailableSource(): AudioSource =
        AudioSource.LocalFile("content://plinth.test/deleted.flac").also(engine::markUnavailable)

    override suspend fun playToEnd(engine: AudioEngine) {
        (engine as FakeAudioEngine).completeTrack()
    }

    // Ниже — проверки управляющей поверхности самого фейка: её контракт
    // не описывает, но без неё фейк бесполезен в тестах верхних слоёв.

    @Test
    fun `records what it was asked to play`() {
        val source = playableSource()
        val params = PlaybackParams(startPosition = 30.seconds, autoPlay = true)

        engine.prepare(source, params)

        assertThat(engine.preparedSources).containsExactly(source)
        assertThat(engine.lastParams).isEqualTo(params)
        assertThat(engine.position).isEqualTo(30.seconds)
    }

    @Test
    fun `records volume changes`() {
        engine.setVolume(0.25f)

        assertThat(engine.volume).isEqualTo(0.25f)
    }

    @Test
    fun `reports the error it was told to report`() {
        engine.prepare(playableSource())

        engine.failWith(PlaybackError.UnsupportedFormat("ape"))

        val state = engine.state.value
        assertThat(state).isInstanceOf(PlaybackState.Error::class.java)
        assertThat((state as PlaybackState.Error).error).isEqualTo(PlaybackError.UnsupportedFormat("ape"))
    }

    @Test
    fun `passes through buffering on prepare`() {
        // Промежуточное состояние не видно через StateFlow: он конфлейтит,
        // а фейк переключает состояния синхронно. Поэтому история состояний —
        // отдельное свойство фейка.
        engine.prepare(playableSource())

        assertThat(engine.stateHistory)
            .containsAtLeast(PlaybackState.Buffering, PlaybackState.Paused)
            .inOrder()
    }
}
