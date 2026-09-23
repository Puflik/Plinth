package io.github.puflik.plinth.audio.media3

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration

/**
 * Тестовый трек: тишина в WAV (PCM, 16 бит, моно, 44,1 кГц).
 *
 * Файл пишется при запуске теста, а не лежит в ассетах: контракту нужен
 * любой трек заданной длины, и хранить ради этого бинарник в репозитории
 * незачем. Эталонные файлы семи форматов появятся в B2.4.
 * Тишина — чтобы прогон на эмуляторе со звуком не пищал в динамики.
 */
internal object SilentWav {
    private const val SAMPLE_RATE = 44_100
    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    private const val HEADER_SIZE = 44
    private const val FMT_CHUNK_SIZE = 16
    private const val PCM: Short = 1
    private const val MONO: Short = 1
    private const val MILLIS_PER_SECOND = 1000

    fun write(
        file: File,
        length: Duration,
    ) {
        val samples = (SAMPLE_RATE.toLong() * length.inWholeMilliseconds / MILLIS_PER_SECOND).toInt()
        val dataSize = samples * BYTES_PER_SAMPLE
        val header =
            ByteBuffer
                .allocate(HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray())
                .putInt(HEADER_SIZE - 8 + dataSize)
                .put("WAVE".toByteArray())
                .put("fmt ".toByteArray())
                .putInt(FMT_CHUNK_SIZE)
                .putShort(PCM)
                .putShort(MONO)
                .putInt(SAMPLE_RATE)
                .putInt(SAMPLE_RATE * BYTES_PER_SAMPLE)
                .putShort(BYTES_PER_SAMPLE.toShort())
                .putShort(BITS_PER_SAMPLE.toShort())
                .put("data".toByteArray())
                .putInt(dataSize)
        file.outputStream().use {
            it.write(header.array())
            it.write(ByteArray(dataSize))
        }
    }
}
