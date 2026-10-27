package io.github.puflik.plinth.online.youtube

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.puflik.plinth.ffi.CoreHttpStatusTable
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Проба «живой Mix» (Ю4б, `docs/work/yu4b.md`) — не часть обычных прогонов и не часть CI: ходит в настоящий YouTube
 * настоящим загрузчиком приложения на эмуляторе. Без аргумента пропускается (`assumeTrue`), включается так:
 *
 *     -Pandroid.testInstrumentationRunnerArguments.liveYoutube=true
 *
 * Что она проверяет, чего не видит JVM-замена записей: ответ Mix (≈ 500 КБ после распаковки) проходит загрузчик на
 * Android целиком, страница экстрактора переживает путь «ключ → источник → следующая страница», затравка не
 * возвращается. Числа (число треков, миллисекунды) идут в logcat под тегом `YoutubeRadioLive` и в отчёт исполнителя;
 * жёстки только границы, которым верит любой Mix: от десяти до тридцати треков на странице, ключ есть, затравки нет.
 */
class YoutubeRadioLiveProbe {
    @Before
    fun onlyWhenAsked() {
        val asked = InstrumentationRegistry.getArguments().getString("liveYoutube")
        assumeTrue("живой YouTube: -Pandroid.testInstrumentationRunnerArguments.liveYoutube=true", asked == "true")
    }

    @Test
    fun the_mix_of_a_song_comes_in_pages_over_the_real_network_and_the_key_leads_to_the_next() {
        val source = NewPipeYoutubeSource(CoreHttpStatusTable)

        val firstStarted = System.nanoTime()
        val first = source.radio(SEED, null)
        val firstMillis = (System.nanoTime() - firstStarted) / NANOS_PER_MILLI
        val secondStarted = System.nanoTime()
        val second = source.radio(SEED, first.next)
        val secondMillis = (System.nanoTime() - secondStarted) / NANOS_PER_MILLI

        Log.i(TAG, "first page: ${first.tracks.size} tracks, $firstMillis ms; key ${first.next}")
        Log.i(TAG, "second page: ${second.tracks.size} tracks, $secondMillis ms; key ${second.next}")
        Log.i(TAG, "first tracks: " + first.tracks.take(FEW).joinToString { "${it.id} ${it.artist} - ${it.title}" })
        assertThat(first.tracks.size).isIn(PAGE_SIZE)
        assertThat(second.tracks.size).isIn(PAGE_SIZE)
        assertThat(first.next).isNotNull()
        assertThat(second.next).isNotNull()
        assertThat(second.next).isNotEqualTo(first.next)
        assertThat(first.tracks.map(YoutubeTrack::id)).doesNotContain(SEED)
        assertThat(second.tracks.map(YoutubeTrack::id)).doesNotContain(SEED)
        for (track in first.tracks + second.tracks) {
            assertThat(track.id).matches("[A-Za-z0-9_-]{11}")
            assertThat(track.variants.map(YoutubeVariant::id)).containsExactly("${track.id}/251", "${track.id}/140")
        }
    }

    private companion object {
        const val TAG = "YoutubeRadioLive"

        /** «Get Lucky» — видео, на котором записаны ответы для JVM-тестов и спайк Ю3а. */
        const val SEED = "4D7u5KF7SP8"
        val PAGE_SIZE = 10..30
        const val FEW = 5
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
