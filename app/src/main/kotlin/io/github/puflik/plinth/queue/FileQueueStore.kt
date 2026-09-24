package io.github.puflik.plinth.queue

import io.github.puflik.plinth.audio.engine.AudioSource
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

/**
 * [QueueStore] в двух файлах папки [directory]: очередь и позиция.
 *
 * Формат — свой, двоичный, с версией в начале: таблица в базе ради одной
 * очереди не нужна, а Room и сканер в v0.2 уходят в ядро. Файл, который не
 * читается (повреждён, другая версия), — как будто ничего не сохранено.
 * Запись — во временный файл и переименование: убитый посреди записи процесс
 * не оставит половину очереди. Заголовки потоков на диск не пишутся — в них
 * бывают ключи доступа.
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
    private val lock = Mutex()

    override suspend fun load(): SavedQueue? =
        exclusive {
            try {
                val queue = DataInputStream(queueFile.inputStream().buffered()).use(::readQueue)
                val position =
                    if (positionFile.exists()) {
                        DataInputStream(
                            positionFile.inputStream(),
                        ).use { it.readLong() }
                    } else {
                        0L
                    }
                SavedQueue(queue, position.milliseconds)
            } catch (expected: IOException) {
                null
            } catch (expected: IllegalArgumentException) {
                // Прочиталось, но не складывается в очередь: чужой или испорченный файл.
                null
            } catch (expected: IndexOutOfBoundsException) {
                null
            }
        }

    override suspend fun saveQueue(queue: PlaybackQueue) =
        exclusive {
            write(queueFile) { writeQueue(queue) }
            write(positionFile) { writeLong(0) }
        }

    override suspend fun savePosition(position: Duration) =
        exclusive {
            write(positionFile) { writeLong(position.inWholeMilliseconds) }
        }

    private suspend fun <T> exclusive(block: () -> T): T = lock.withLock { withContext(io) { block() } }

    private fun write(
        file: File,
        block: DataOutputStream.() -> Unit,
    ) {
        directory.mkdirs()
        val temporary = File(directory, "${file.name}.tmp")
        DataOutputStream(temporary.outputStream().buffered()).use(block)
        if (!temporary.renameTo(file)) {
            file.delete()
            check(temporary.renameTo(file)) { "не удалось заменить $file" }
        }
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
            is QueueContext.Album -> {
                writeUTF("album")
                writeUTF(context.title)
                writeNullable(context.artist)
            }
            is QueueContext.Folder -> {
                writeUTF("folder")
                writeUTF(context.path)
            }
            is QueueContext.Search -> {
                writeUTF("search")
                writeUTF(context.query)
            }
            is QueueContext.Artist -> {
                writeUTF("artist")
                writeUTF(context.name)
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
                writeUTF(source.uri)
            }
            is AudioSource.Remote -> {
                writeUTF("remote")
                writeUTF(source.url)
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
        text?.let(::writeUTF)
    }

    private fun DataInputStream.readNullable(): String? = if (readBoolean()) readUTF() else null

    private companion object {
        /** 2 — владелец альбома у элемента и контекст «исполнитель» (E5); файл версии 1 не читается. */
        const val VERSION = 2
        const val NO_CONTEXT = "none"
        const val NO_DURATION = -1L
    }
}
