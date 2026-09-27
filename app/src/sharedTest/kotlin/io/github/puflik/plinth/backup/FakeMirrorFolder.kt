package io.github.puflik.plinth.backup

import io.github.puflik.plinth.ffi.MirrorFile

/**
 * Папки копий в памяти (C4): адрес дерева → файлы по имени. Папка, на
 * которую разрешения не дали ([refused]), не читается и не пишется.
 */
class FakeMirrorFolder : MirrorFolder {
    private val folders = mutableMapOf<String, MutableMap<String, MirrorFile>>()

    /** Папки, на которые система отказала в доступе. */
    val refused = mutableSetOf<String>()

    /** Следующая запись не получится — как отказ провайдера. */
    var failNextWrite = false

    /** Сколько файлов записано. */
    var writes = 0
        private set

    @Synchronized
    fun files(tree: String): List<MirrorFile> = folders[tree]?.values?.toList().orEmpty()

    /** Кладёт [file] в папку [tree] — как копия другой установки. */
    @Synchronized
    fun put(
        tree: String,
        file: MirrorFile,
    ) {
        folders.getOrPut(tree) { mutableMapOf() }[file.name] = file
    }

    override suspend fun adopt(tree: String): Boolean = tree !in refused

    override suspend fun read(tree: String): List<MirrorFile>? = if (tree in refused) null else files(tree)

    override suspend fun write(
        tree: String,
        file: MirrorFile,
    ): Boolean =
        synchronized(this) {
            val fails = tree in refused || failNextWrite
            failNextWrite = false
            if (!fails) {
                writes++
                put(tree, file)
            }
            !fails
        }
}
