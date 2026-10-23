package io.github.puflik.plinth.audio.media3

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * Первый отказ сервера (403) на адрес сетевого трека → один новый вопрос о нём (Ю3в, `yu3a.md`, раздел 8):
 * ссылка YouTube привязана к IP и к сроку, и на телефоне свежая ссылка бывала отвергнута. Загрузчик ExoPlayer
 * повторял бы ту же ссылку и получал тот же отказ, а здесь адрес забывается ([OnlineResolver.refused]), и
 * [upstream] открывается ещё раз: `ResolvingDataSource` под ним спросит новый.
 *
 * Один повтор на загрузку, а не на каждое открытие: после него загрузчик ExoPlayer ещё трижды откроет поток сам, и по
 * каждому отказу идти за новым извлечением — бить в сервер, который уже сказал «нет». Другой статус, второй отказ
 * загрузки и чужой адрес идут наружу как есть. Всё остальное — в [upstream].
 */
@OptIn(UnstableApi::class)
internal class ReResolvingDataSource(
    private val upstream: DataSource,
    private val resolver: OnlineResolver,
) : DataSource {
    override fun open(dataSpec: DataSpec): Long =
        try {
            upstream.open(dataSpec)
        } catch (refusal: HttpDataSource.InvalidResponseCodeException) {
            if (refusal.responseCode != FORBIDDEN || !resolver.refused(dataSpec.uri)) throw refusal
            // `DefaultDataSource.open` не принимает нового чтения, пока прежнее не закрыто.
            upstream.close()
            upstream.open(dataSpec)
        }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    private companion object {
        const val FORBIDDEN = 403
    }
}
