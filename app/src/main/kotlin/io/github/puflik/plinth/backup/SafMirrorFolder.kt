package io.github.puflik.plinth.backup

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.puflik.plinth.diagnostics.log.AppLog
import io.github.puflik.plinth.ffi.MirrorFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Папка копии через SAF (C4): разрешение на дерево постоянное, файлы — через
 * `DocumentsContract`. Один код для Android 8–16; «доступ ко всем файлам» не
 * нужен, а после переустановки папку просто выбирают заново.
 *
 * Запись — во временный `<имя>.tmp`, затем старый файл удаляется, а новый
 * переименовывается: переименование SAF поверх существующего файла не
 * пишет, а дописывает « (1)» к имени. Сбой посередине оставит только
 * временный файл — он такая же полная копия, её прочтёт и восстановление.
 */
class SafMirrorFolder(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher,
) : MirrorFolder {
    /** Каталог копий по дереву: искать его — листать всю папку, а пишется копия часто. */
    private val journals = ConcurrentHashMap<String, String>()

    override suspend fun adopt(tree: String): Boolean =
        withContext(io) {
            try {
                resolver.takePersistableUriPermission(Uri.parse(tree), READ_WRITE)
                true
            } catch (e: SecurityException) {
                AppLog.w(TAG, "folder permission refused", e)
                false
            }
        }

    override suspend fun read(tree: String): List<MirrorFile>? =
        withContext(io) {
            guarded("read") {
                val root = Uri.parse(tree)
                val journal = find(root, rootOf(root), PATH) ?: return@guarded emptyList()
                children(root, journal)
                    .filter { !it.directory && it.size <= MAX_BYTES }
                    .mapNotNull { child ->
                        resolver
                            .openInputStream(child.uri(root))
                            ?.use { it.readBytes() }
                            ?.let { MirrorFile(child.name, it) }
                    }
            }
        }

    override suspend fun write(
        tree: String,
        file: MirrorFile,
    ): Boolean =
        withContext(io) {
            val written =
                guarded("write") {
                    val root = Uri.parse(tree)
                    writeInto(root, journalOf(tree, root), file)
                } ?: false
            // Каталог могли удалить или переименовать — в следующий раз он ищется заново.
            if (!written) journals.remove(tree)
            written
        }

    private fun writeInto(
        root: Uri,
        journal: String,
        file: MirrorFile,
    ): Boolean {
        val present = children(root, journal)
        val temporary = "${file.name}.tmp"
        present.find { it.name == temporary }?.let { DocumentsContract.deleteDocument(resolver, it.uri(root)) }
        val created =
            DocumentsContract.createDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(root, journal),
                MIME,
                temporary,
            ) ?: throw IOException("the provider did not create $temporary")
        resolver.openOutputStream(created, "wt")?.use { it.write(file.content) }
            ?: throw IOException("the provider did not open $temporary")
        present.find { it.name == file.name }?.let { DocumentsContract.deleteDocument(resolver, it.uri(root)) }
        DocumentsContract.renameDocument(resolver, created, file.name)
        return true
    }

    private fun rootOf(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    /** Каталог копий `.plinth/journal` в дереве; нет — создаётся. */
    private fun journalOf(
        tree: String,
        root: Uri,
    ): String = journals.getOrPut(tree) { PATH.fold(rootOf(root)) { parent, name -> directory(root, parent, name) } }

    /** Каталог [path] от документа [start]; нет — `null`. */
    private fun find(
        tree: Uri,
        start: String,
        path: List<String>,
    ): String? =
        path.fold<String, String?>(start) { parent, name ->
            parent?.let { id -> children(tree, id).find { it.directory && it.name == name }?.id }
        }

    /** Каталог [name] в [parent]; нет — создаётся. */
    private fun directory(
        tree: Uri,
        parent: String,
        name: String,
    ): String {
        children(tree, parent).find { it.directory && it.name == name }?.let { return it.id }
        val created =
            DocumentsContract.createDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, parent),
                Document.MIME_TYPE_DIR,
                name,
            ) ?: throw IOException("the provider did not create $name")
        return DocumentsContract.getDocumentId(created)
    }

    private fun children(
        tree: Uri,
        parent: String,
    ): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        return resolver.query(uri, COLUMNS, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Child(
                            id = cursor.getString(ID),
                            name = cursor.getString(NAME).orEmpty(),
                            directory = cursor.getString(MIME_TYPE) == Document.MIME_TYPE_DIR,
                            size = if (cursor.isNull(SIZE)) 0 else cursor.getLong(SIZE),
                        ),
                    )
                }
            }
        } ?: throw IOException("the provider did not list $parent")
    }

    /** Отказ провайдера или системы — в лог и `null`: копия подождёт следующей записи. */
    private inline fun <T> guarded(
        what: String,
        block: () -> T,
    ): T? =
        try {
            block()
        } catch (e: IOException) {
            AppLog.w(TAG, "journal copy $what failed", e)
            null
        } catch (e: SecurityException) {
            AppLog.w(TAG, "journal copy $what refused", e)
            null
        } catch (e: IllegalArgumentException) {
            // Так провайдер отвечает на документ, которого больше нет.
            AppLog.w(TAG, "journal copy $what: no such document", e)
            null
        }

    private class Child(
        val id: String,
        val name: String,
        val directory: Boolean,
        val size: Long,
    ) {
        fun uri(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }

    private companion object {
        const val TAG = "Mirror"
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        const val MIME = "application/octet-stream"
        val PATH = listOf(".plinth", "journal")

        /** Журнал на сотни тысяч событий — десятки мегабайт; больше — не копия. */
        const val MAX_BYTES = 64L * 1024 * 1024

        val COLUMNS =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
            )

        // Номера столбцов [COLUMNS].
        const val ID = 0
        const val NAME = 1
        const val MIME_TYPE = 2
        const val SIZE = 3
    }
}
