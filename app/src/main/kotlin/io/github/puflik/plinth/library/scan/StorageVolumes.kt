package io.github.puflik.plinth.library.scan

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/** Что скан делает с томом, по `StorageVolume.getState()`. */
enum class VolumeReadiness { READY, PENDING, ABSENT }

/**
 * MEDIA_MOUNTED и MEDIA_MOUNTED_READ_ONLY — READY; MEDIA_CHECKING — PENDING;
 * всё остальное, и незнакомое тоже, — ABSENT.
 *
 * Только `checking` — «проверяется»: из него система выходит сама. Вручную
 * отключённая (`unmounted`) и отключаемая (`ejecting`) карта — вынутая: иначе её
 * треки остались бы «доступными» и неиграющими, а путь «трек не играет →
 * пересканировать» не отметил бы их недоступными никогда.
 */
fun volumeReadiness(state: String): VolumeReadiness =
    when (state) {
        Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY -> VolumeReadiness.READY
        Environment.MEDIA_CHECKING -> VolumeReadiness.PENDING
        else -> VolumeReadiness.ABSENT
    }

/**
 * Тома из одного чтения системы: [ready] — корни, которые обходит скан ядра;
 * [pending] — корни карт, которые система знает, но ещё не отдала (проверяет
 * после загрузки или вставки): скан о файлах на них ничего не решает.
 */
data class VolumeSnapshot(
    val ready: List<File>,
    val pending: List<File> = emptyList(),
)

/** Тома, которые скан ядра обходит: основное хранилище и SD-карты (D3c). */
fun interface StorageVolumes {
    /** Только готовые тома. */
    fun roots(): List<File>

    /**
     * Тома из одного чтения системы. Умолчание — все тома готовы, проверяемых нет:
     * так отвечает тот, кто задаёт только [roots] (тесты подают их лямбдой).
     */
    fun snapshot(): VolumeSnapshot = VolumeSnapshot(roots())
}

/**
 * Тома устройства. С Android 11 путь тома даёт `StorageManager`, до него —
 * начало пути папки приложения на каждом томе. Вынутая карта в список не
 * попадает: её треки скан отметит недоступными, вернётся — доступными. Карта,
 * которую система проверяет, идёт отдельным списком: её треки скан не трогает.
 */
class AndroidStorageVolumes(
    private val context: Context,
) : StorageVolumes {
    override fun roots(): List<File> = snapshot().ready

    override fun snapshot(): VolumeSnapshot {
        val manager = context.getSystemService(StorageManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Один проход: том не может оказаться в двух списках сразу.
            val ready = mutableListOf<File>()
            val pending = mutableListOf<File>()
            for (volume in manager.storageVolumes) {
                when (volumeReadiness(volume.state)) {
                    VolumeReadiness.READY -> volume.directory?.let(ready::add)
                    VolumeReadiness.PENDING -> volume.uuid?.let { pending.add(mountPoint(it)) }
                    VolumeReadiness.ABSENT -> Unit
                }
            }
            VolumeSnapshot(ready, pending)
        } else {
            // Сначала проверяемые, потом готовые: карта, смонтировавшаяся между двумя
            // чтениями, окажется в обоих списках, а «в обоих» значит «готов». В обратном
            // порядке она выпала бы из обоих и скан счёл бы её вынутой.
            val pending =
                manager.storageVolumes
                    .filter { volumeReadiness(it.state) == VolumeReadiness.PENDING }
                    .mapNotNull { it.uuid }
                    .map(::mountPoint)
            val ready =
                context
                    .getExternalFilesDirs(null)
                    .filterNotNull()
                    .mapNotNull { dir -> dir.path.substringBefore(APP_FOLDERS, "").takeIf(String::isNotEmpty) }
                    .distinct()
                    .map(::File)
            VolumeSnapshot(ready, pending)
        }
    }

    private companion object {
        /** Папка приложения на томе: `<корень>/Android/data/<пакет>/files`. */
        const val APP_FOLDERS = "/Android/data/"

        /**
         * Корень проверяемой карты: `getDirectory()` у неё `null`, пока том не
         * смонтирован, поэтому путь выводится из `uuid` — так система называет тома
         * (`/storage/1234-ABCD`) и так лежат `local_uri` в каталоге. Основной том
         * (`uuid == null`) сюда не попадает.
         */
        fun mountPoint(uuid: String): File = File("/storage/$uuid")
    }
}
