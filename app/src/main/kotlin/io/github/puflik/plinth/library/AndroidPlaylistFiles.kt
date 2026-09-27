package io.github.puflik.plinth.library

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.puflik.plinth.diagnostics.log.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Файлы плейлистов через `ContentResolver` (D4c). Документ открывает и
 * создаёт системный выбор SAF — разрешения на файлы приложению не нужны.
 * Не прочиталось или не записалось — пишется в лог, наверх идёт `null` или `false`.
 */
class AndroidPlaylistFiles(
    private val context: Context,
    private val io: CoroutineDispatcher,
) : PlaylistFiles {
    override suspend fun read(uri: String): PlaylistFile? =
        withContext(io) {
            val document = Uri.parse(uri)
            try {
                val content = context.contentResolver.openInputStream(document)?.use { it.readAtMost(MAX_BYTES) }
                content?.let { PlaylistFile(nameOf(document), it, folderOf(document)) }
            } catch (e: IOException) {
                AppLog.w(TAG, "playlist file read failed", e)
                null
            } catch (e: SecurityException) {
                AppLog.w(TAG, "playlist file read refused", e)
                null
            }
        }

    override suspend fun write(
        uri: String,
        text: String,
    ): Boolean =
        withContext(io) {
            try {
                // "wt": провайдер, который открывает на запись без усечения, оставил бы хвост старого файла.
                context.contentResolver.openOutputStream(Uri.parse(uri), "wt")?.use { it.write(text.toByteArray()) } !=
                    null
            } catch (e: IOException) {
                AppLog.w(TAG, "playlist file write failed", e)
                false
            } catch (e: SecurityException) {
                AppLog.w(TAG, "playlist file write refused", e)
                false
            }
        }

    /** Имя файла без расширения; провайдер его не знает — последняя часть адреса. */
    private fun nameOf(document: Uri): String {
        val shown =
            context.contentResolver
                .query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val name = shown ?: document.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
        return name?.substringBeforeLast('.')?.trim()?.ifEmpty { null } ?: name.orEmpty()
    }

    private fun folderOf(document: Uri): String? {
        val authority = document.authority
        if (authority == null || !DocumentsContract.isDocumentUri(context, document)) return null
        // Путь основного тома; сам каталог приложение не читает.
        @Suppress("DEPRECATION")
        val primary = Environment.getExternalStorageDirectory().path
        return DocumentFolder.of(authority, DocumentsContract.getDocumentId(document), primary)
    }

    /** Всё содержимое, если оно не больше [limit] байт; больше — `null`. */
    private fun InputStream.readAtMost(limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
    }

    private companion object {
        const val TAG = "PlaylistFiles"

        /** Плейлист на десятки тысяч строк — единицы мегабайт; больше — выбран не тот файл. */
        const val MAX_BYTES = 16 * 1024 * 1024
    }
}
