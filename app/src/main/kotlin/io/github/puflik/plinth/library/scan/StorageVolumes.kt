package io.github.puflik.plinth.library.scan

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/** Корни томов, которые обходит скан ядра: основное хранилище и SD-карты (D3c). */
fun interface StorageVolumes {
    fun roots(): List<File>
}

/**
 * Тома устройства. С Android 11 путь тома даёт `StorageManager`, до него —
 * начало пути папки приложения на каждом томе. Вынутая карта в список не
 * попадает: её треки скан отметит недоступными, вернётся — доступными.
 */
class AndroidStorageVolumes(
    private val context: Context,
) : StorageVolumes {
    override fun roots(): List<File> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context
                .getSystemService(StorageManager::class.java)
                .storageVolumes
                .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
                .mapNotNull(StorageVolume::getDirectory)
        } else {
            context
                .getExternalFilesDirs(null)
                .filterNotNull()
                .mapNotNull { dir -> dir.path.substringBefore(APP_FOLDERS, "").takeIf(String::isNotEmpty) }
                .distinct()
                .map(::File)
        }

    private companion object {
        /** Папка приложения на томе: `<корень>/Android/data/<пакет>/files`. */
        const val APP_FOLDERS = "/Android/data/"
    }
}
