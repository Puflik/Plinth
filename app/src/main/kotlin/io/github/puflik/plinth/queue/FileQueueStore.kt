package io.github.puflik.plinth.queue

import io.github.puflik.plinth.audio.engine.AudioSource
import io.github.puflik.plinth.diagnostics.log.AppLog
import io.github.puflik.plinth.ffi.PlaylistId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * [QueueStore] в трёх файлах папки [directory]: очередь, позиция и время
 * последней игры. Время — отдельно: позиция обнуляется с каждой новой
 * очередью, а время последней игры от смены трека не зависит.
 *
 * Формат — свой, двоичный, с версией в начале: таблица в базе ради одной
 * очереди не нужна, а фонотеку с 0.2 ведёт ядро. Файл очереди, который не
 * читается (повреждён, другая версия), — как будто ничего не сохранено;
 * позиция и время игры — мелочь рядом с очередью: не читаются — трек с
 * начала, но очередь не теряется (ревью v0.2, №19).
 * Запись — во временный файл и переименование: убитый посреди записи процесс
 * не оставит половину очереди; очередь перед переименованием ещё и сбрасывается
 * на диск. Заголовки потоков на диск не пишутся — в них бывают ключи доступа.
 * Строка длиннее предела `writeUTF` пишется обрезанной (см. [fitUtf]): одна
 * такая строка не должна останавливать сохранение очереди (ревью v0.2, №27).
 *
 * Позиция на диске либо принадлежит очереди в `queue.bin`, либо её там нет:
 * очередь не записалась — позицию новой очереди к прежней не пишем.
 *
 * Файлы трогает одна операция за раз ([lock]): очередь и позицию пишут две
 * корутины, и на пуле IO они сталкивались на временном файле позиции.
 */
class FileQueueStore(
    private val directory: File,
    private val io: CoroutineDispatcher,
) : QueueStore {
    private val queueFile = File(directory, "queue.bin")
    private val positionFile = File(directory, "position.bin")
    private val playedFile = File(directory, "played.bin")
    private val lock = Mutex()

    /** Новую очередь записать не вышло: на диске прежняя, и позиция новой ей не принадлежит. Под [lock]. */
    private var queueStale = false

    override suspend fun load(): SavedQueue? =
        exclusive {
            val queue =
                try {
                    DataInputStream(queueFile.inputStream().buffered()).use(::readQueue)
                } catch (expected: IOException) {
                    null
                } catch (expected: IllegalArgumentException) {
                    // Прочиталось, но не складывается в очередь: чужой или испорченный файл.
                    null
                } catch (expected: IndexOutOfBoundsException) {
                    null
                }
            queue?.let { SavedQueue(it, readPosition(), readPlayedAt()) }
        }

    override suspend fun saveQueue(queue: PlaybackQueue) {
        exclusive {
            queueStale = !write(queueFile, durable = true) { writeQueue(queue) }
            // Очередь записалась — позиция с начала трека; не записалась и она — позиции прежней очереди не оставляем.
            if (!queueStale && !write(positionFile) { writeLong(0) }) positionFile.delete()
        }
    }

    override suspend fun savePosition(position: Duration) {
        exclusive { if (!queueStale) write(positionFile) { writeLong(position.inWholeMilliseconds) } }
    }

    override suspend fun savePlayedAt(at: Instant) {
        exclusive { write(playedFile) { writeLong(at.toEpochMilliseconds()) } }
    }

    /**
     * Секунда в треке; файла нет, он короче восьми байт или не читается, а
     * прочитанное отрицательно — трек с начала. Очередь из-за позиции не теряется.
     */
    private fun readPosition(): Duration =
        try {
            if (positionFile.exists()) {
                DataInputStream(positionFile.inputStream()).use { it.readLong() }.coerceAtLeast(0).milliseconds
            } else {
                Duration.ZERO
            }
        } catch (expected: IOException) {
            Duration.ZERO
        }

    /** Время последней игры; не читается — неизвестно, но очередь из-за него не теряется. */
    private fun readPlayedAt(): Instant? =
        try {
            if (playedFile.exists()) {
                DataInputStream(playedFile.inputStream()).use { Instant.fromEpochMilliseconds(it.readLong()) }
            } else {
                null
            }
        } catch (expected: IOException) {
            null
        }

    private suspend fun <T> exclusive(block: () -> T): T = lock.withLock { withContext(io) { block() } }

    /**
     * Запись с заменой файла; `false` — не вышло. Нет места или доступа —
     * не повод ронять процесс (план 17.6): прежний файл цел, приложение
     * играет дальше, в лог — предупреждение.
     *
     * [durable] — до переименования данные сбрасываются на диск (`sync`),
     * иначе обесточенное устройство оставит переименованный, но пустой файл.
     * Это дорого для батареи и флеша, поэтому только для редкого — очереди;
     * позицию, что пишется каждую секунду, спасает допуск при чтении.
     */
    private fun write(
        file: File,
        durable: Boolean = false,
        block: DataOutputStream.() -> Unit,
    ): Boolean =
        try {
            directory.mkdirs()
            val temporary = File(directory, "${file.name}.tmp")
            temporary.outputStream().use { stream ->
                val output = DataOutputStream(stream.buffered())
                output.block()
                output.flush()
                if (durable) stream.fd.sync()
            }
            if (!temporary.renameTo(file)) {
                file.delete()
                if (!temporary.renameTo(file)) throw IOException("не удалось заменить ${file.name}")
            }
            true
        } catch (e: IOException) {
            AppLog.w(TAG, "${file.name} not saved", e)
            false
        }

    private fun DataOutputStream.writeQueue(queue: PlaybackQueue) {
        writeInt(VERSION)
        writeContext(queue.context)
        writeItems(queue.contextItems)
        writeInt(queue.order.size)
        queue.order.forEach(::writeInt)
        writeInt(queue.position)
        writeBoolean(queue.playingManual != null)
        queue.playingManual?.let { writeItem(it) }
        writeItems(queue.upNext)
        writeBoolean(queue.shuffle)
        writeUTF(queue.repeat.name)
    }

    private fun readQueue(input: DataInputStream): PlaybackQueue {
        if (input.readInt() != VERSION) throw IOException("другая версия очереди")
        return PlaybackQueue(
            context = input.readContext(),
            contextItems = input.readItems(),
            order = List(input.readInt()) { input.readInt() },
            position = input.readInt(),
            playingManual = if (input.readBoolean()) input.readItem() else null,
            upNext = input.readItems(),
            shuffle = input.readBoolean(),
            repeat = RepeatMode.valueOf(input.readUTF()),
        )
    }

    private fun DataOutputStream.writeContext(context: QueueContext?) {
        when (context) {
            null -> writeUTF(NO_CONTEXT)
            QueueContext.Tracks -> writeUTF("tracks")
            QueueContext.File -> writeUTF("file")
            QueueContext.Liked -> writeUTF("liked")
            QueueContext.Recent -> writeUTF("recent")
            is QueueContext.Album -> {
                writeUTF("album")
                writeText(context.title)
                writeNullable(context.artist)
            }
            is QueueContext.Folder -> {
                writeUTF("folder")
                writeText(context.path)
            }
            is QueueContext.Search -> {
                writeUTF("search")
                writeText(context.query)
            }
            is QueueContext.Artist -> {
                writeUTF("artist")
                writeText(context.name)
            }
            is QueueContext.Playlist -> {
                writeUTF("playlist")
                writeText(context.id.value)
                writeText(context.name)
            }
            is QueueContext.Radio -> {
                writeUTF("radio")
                writeText(context.provider)
                writeText(context.seed)
                writeText(context.title)
            }
        }
    }

    private fun DataInputStream.readContext(): QueueContext? =
        when (val kind = readUTF()) {
            NO_CONTEXT -> null
            "tracks" -> QueueContext.Tracks
            "file" -> QueueContext.File
            "album" -> QueueContext.Album(readUTF(), readNullable())
            "folder" -> QueueContext.Folder(readUTF())
            "search" -> QueueContext.Search(readUTF())
            "artist" -> QueueContext.Artist(readUTF())
            "playlist" -> QueueContext.Playlist(PlaylistId(readUTF()), readUTF())
            "liked" -> QueueContext.Liked
            "recent" -> QueueContext.Recent
            "radio" -> QueueContext.Radio(readUTF(), readUTF(), readUTF())
            else -> throw IOException("неизвестный контекст $kind")
        }

    private fun DataOutputStream.writeItems(items: List<QueueItem>) {
        writeInt(items.size)
        items.forEach { writeItem(it) }
    }

    private fun DataInputStream.readItems(): List<QueueItem> = List(readInt()) { readItem() }

    private fun DataOutputStream.writeItem(item: QueueItem) {
        when (val source = item.source) {
            is AudioSource.LocalFile -> {
                writeUTF("file")
                writeText(source.uri)
            }
            is AudioSource.Remote -> {
                writeUTF("remote")
                writeText(source.url)
            }
            is AudioSource.Online -> {
                writeUTF("online")
                writeText(source.track)
            }
        }
        writeNullable(item.title)
        writeNullable(item.artist)
        writeNullable(item.album)
        writeLong(item.duration?.inWholeMilliseconds ?: NO_DURATION)
        writeNullable(item.albumOwner)
    }

    private fun DataInputStream.readItem(): QueueItem {
        val source =
            when (val kind = readUTF()) {
                "file" -> AudioSource.LocalFile(readUTF())
                "remote" -> AudioSource.Remote(readUTF())
                "online" -> AudioSource.Online(readUTF())
                else -> throw IOException("неизвестный источник $kind")
            }
        return QueueItem(
            source = source,
            title = readNullable(),
            artist = readNullable(),
            album = readNullable(),
            duration = readLong().takeIf { it != NO_DURATION }?.milliseconds,
            albumOwner = readNullable(),
        )
    }

    private fun DataOutputStream.writeNullable(text: String?) {
        writeBoolean(text != null)
        text?.let { writeText(it) }
    }

    private fun DataInputStream.readNullable(): String? = if (readBoolean()) readUTF() else null

    /** Переменная строка — обрезанная до того, что `writeUTF` ещё запишет; метки формата пишутся прямо. */
    private fun DataOutputStream.writeText(text: String) = writeUTF(text.fitUtf())

    private companion object {
        /**
         * 2 — владелец альбома у элемента и контекст «исполнитель» (E5); файл версии 1 не читается. Контекст «радио»
         * (Ю4в) добавлен без подъёма версии: прежняя версия не знает его вида и очередь не прочтёт.
         */
        const val VERSION = 2
        const val NO_CONTEXT = "none"
        const val NO_DURATION = -1L
        const val TAG = "QueueStore"
    }
}

/** Предел `DataOutputStream.writeUTF`: длина записи в байтах модифицированного UTF-8. */
private const val MAX_UTF = 65_535

/** Самые большие коды, что ещё занимают в модифицированном UTF-8 один и два байта; дальше — три. */
private const val LAST_ONE_BYTE = 0x7f
private const val LAST_TWO_BYTES = 0x7ff
private const val THREE_BYTES = 3

/**
 * Самое длинное начало строки, которое `writeUTF` ещё запишет: не больше [MAX_UTF] байт
 * модифицированного UTF-8. Строка, что помещается, возвращается как есть. Обрезка
 * не оставляет первую половину суррогатной пары без второй.
 */
internal fun String.fitUtf(): String {
    val end = utfPrefixLength()
    return when {
        end == length -> this
        this[end - 1].isHighSurrogate() -> substring(0, end - 1)
        else -> substring(0, end)
    }
}

/** Сколько первых символов строки помещается в [MAX_UTF] байт модифицированного UTF-8. */
private fun String.utfPrefixLength(): Int {
    var bytes = 0
    for (index in indices) {
        bytes += utfSize(this[index])
        if (bytes > MAX_UTF) return index
    }
    return length
}

/** Размер символа в модифицированном UTF-8: `\u0000` — два байта, половина суррогатной пары — три. */
private fun utfSize(char: Char): Int =
    when (char.code) {
        in 1..LAST_ONE_BYTE -> 1
        in 0..LAST_TWO_BYTES -> 2
        else -> THREE_BYTES
    }
