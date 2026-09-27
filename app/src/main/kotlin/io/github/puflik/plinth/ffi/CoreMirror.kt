package io.github.puflik.plinth.ffi

import kotlin.time.Instant
import io.github.puflik.plinth.ffi.generated.MirrorFile as RustMirrorFile
import io.github.puflik.plinth.ffi.generated.MirrorFound as RustMirrorFound
import io.github.puflik.plinth.ffi.generated.MirrorRestore as RustMirrorRestore

/**
 * Копия журнала в папке человека (C4) — `api/mirror_api.rs`. Файлы пишет и
 * читает приложение через SAF; ядро отдаёт копию байтами, осматривает
 * найденные и вливает их в журнал.
 */
class CoreMirror internal constructor(
    private val core: PlinthCore,
) {
    /**
     * Копия журнала этой установки. Имя файла — по установке: копия прошлой
     * установки для новой чужая и не перезаписывается.
     */
    fun copy(): MirrorFile = core.call { it.mirrorCopy().toApp() }

    /** Что лежит в файлах [files] из папки; своя копия пропускается. */
    fun inspect(files: List<MirrorFile>): MirrorFound = core.call { rust -> rust.inspectMirror(files.toRust()).toApp() }

    /**
     * Вливает [files] в журнал — слиянием, сделанное до этого не теряется;
     * треки фонотеки узнаются по паспортам и получают прежние ID.
     */
    fun restore(files: List<MirrorFile>): MirrorRestore =
        core.call { rust -> rust.restoreMirror(files.toRust()).toApp() }.also {
            core.userDataChanged()
            core.catalogChanged()
        }
}

/** Файл копии журнала: имя в папке и содержимое. */
class MirrorFile(
    val name: String,
    val content: ByteArray,
)

/**
 * Что нашлось в копиях из папки — для вопроса «Восстановить?».
 *
 * @property writtenAt когда записана самая свежая копия; прочитанных нет — `null`.
 * @property news в копиях есть то, чего журнал ещё не знает.
 * @property unreadable файлы, которые не прочлись: испорчены или не копии.
 */
data class MirrorFound(
    val likes: Int,
    val playlists: Int,
    val plays: Int,
    val writtenAt: Instant?,
    val news: Boolean,
    val unreadable: Int,
)

/**
 * Итог восстановления.
 *
 * @property merged копии, принёсшие новое.
 * @property relinked треки фонотеки, узнанные по паспортам.
 */
data class MirrorRestore(
    val merged: Int,
    val unreadable: Int,
    val relinked: Int,
)

private fun RustMirrorFile.toApp() = MirrorFile(name, content)

private fun List<MirrorFile>.toRust() = map { RustMirrorFile(it.name, it.content) }

private fun RustMirrorFound.toApp() =
    MirrorFound(
        likes = likes.toInt(),
        playlists = playlists.toInt(),
        plays = plays.toInt(),
        writtenAt = writtenAt?.let(Instant::fromEpochMilliseconds),
        news = news,
        unreadable = unreadable.toInt(),
    )

private fun RustMirrorRestore.toApp() = MirrorRestore(merged.toInt(), unreadable.toInt(), relinked.toInt())
