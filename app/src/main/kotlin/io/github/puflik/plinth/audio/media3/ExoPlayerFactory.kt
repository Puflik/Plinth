package io.github.puflik.plinth.audio.media3

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

/**
 * Создаёт и настраивает ExoPlayer (B2.1).
 *
 * Плеер привязан к потоку, чей [Looper] передан в [create]: ExoPlayer
 * разрешает обращаться к себе только из одного потока. В приложении это
 * главный поток (`AudioModule`), в тестах — любой с looper.
 *
 * Что настроено сознательно не здесь:
 * - буферы — значения ExoPlayer по умолчанию; для локальных файлов их
 *   хватает с запасом, под сеть (v0.2) их придётся пересмотреть;
 * - gapless внутри трека ExoPlayer делает сам по метаданным энкодера
 *   (LAME, iTunSMPB); переход между треками — `PlaybackParams.gaplessNext`, позже;
 * - wake lock: пока режим не задан явно, Media3 1.11 сам держит
 *   `WAKE_MODE_LOCAL`, пока звук идёт (его требует обнаружение зависаний).
 *   Страж — `ExoPlayerFactoryTest`; потокам в v0.2 понадобится `WAKE_MODE_NETWORK`.
 *
 * Что настроено здесь — ядро B4.1 и B4.3, которое ExoPlayer делает сам:
 * - аудиофокус: плеер берёт его при старте, встаёт на паузу, когда фокус
 *   забирают насовсем, и приглушается под короткие подсказки навигатора;
 * - выдернутые наушники ставят плеер на паузу, а не переводят звук в динамик.
 */
class ExoPlayerFactory(
    context: Context,
) {
    private val context = context.applicationContext

    fun create(looper: Looper): ExoPlayer =
        ExoPlayer
            .Builder(context)
            .setLooper(looper)
            .setAudioAttributes(MUSIC, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

    private companion object {
        val MUSIC: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
    }
}
