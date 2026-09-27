package io.github.puflik.plinth.library

/**
 * Файл плейлиста, выбранный через SAF (D4c): имя без расширения, байты как
 * есть — кодировку узнаёт ядро — и папка на томе, если документ на томе.
 */
class PlaylistFile(
    val name: String,
    val content: ByteArray,
    val folder: String?,
)

/** Файлы плейлистов через SAF (D4c): чтение выбранного документа и запись созданного. */
interface PlaylistFiles {
    /** Документ [uri]; не прочитался — `null`. */
    suspend fun read(uri: String): PlaylistFile?

    /** Пишет [text] в UTF-8 в документ [uri]; не записалось — `false`. */
    suspend fun write(
        uri: String,
        text: String,
    ): Boolean
}

/**
 * Папка документа SAF в виде пути на устройстве — от неё импорт считает
 * относительные пути плейлиста.
 *
 * Системный провайдер хранилища называет файл `том:путь` — `primary:Music/a.m3u`
 * на основном томе, `1A2B-3C4D:Music/a.m3u` на SD-карте, `home:a.m3u` в
 * «Документах». «Загрузки» иногда отдают сам путь: `raw:/storage/…`. Остальные
 * провайдеры (облака, медиапровайдер) путей не знают.
 */
object DocumentFolder {
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    private const val DOWNLOADS = "com.android.providers.downloads.documents"

    /** Папка без `/` в конце; [primaryRoot] — корень основного тома. `null` — документ не на томе. */
    fun of(
        authority: String,
        documentId: String,
        primaryRoot: String,
    ): String? {
        val path =
            when (authority) {
                EXTERNAL_STORAGE -> onVolume(documentId, primaryRoot)
                DOWNLOADS -> documentId.removePrefix("raw:").takeIf { documentId.startsWith("raw:/") }
                else -> null
            }
        return path?.substringBeforeLast('/')
    }

    private fun onVolume(
        documentId: String,
        primaryRoot: String,
    ): String? {
        if (':' !in documentId) return null
        val (volume, inside) = documentId.split(':', limit = 2)
        val root =
            when (volume) {
                "primary" -> primaryRoot
                "home" -> "$primaryRoot/Documents"
                else -> "/storage/$volume"
            }
        return "$root/${inside.trim('/')}"
    }
}
