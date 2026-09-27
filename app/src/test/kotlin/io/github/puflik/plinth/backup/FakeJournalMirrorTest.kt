package io.github.puflik.plinth.backup

/**
 * `FakeJournalMirror` проходит общий контракт (C4): на фейке тестируются
 * писатель копии и экраны восстановления.
 */
class FakeJournalMirrorTest : JournalMirrorContractTest() {
    override fun install(): Installation = FakeInstallation(FakeJournalMirror())

    private class FakeInstallation(
        override val mirror: FakeJournalMirror,
    ) : Installation {
        override suspend fun scan(paths: List<String>) = mirror.scan(paths)

        override suspend fun like(path: String) = mirror.like(path)

        override suspend fun playlist(
            name: String,
            paths: List<String>,
        ) = mirror.playlist(name, paths)

        override suspend fun liked(): Set<String> = mirror.liked()

        override suspend fun playlists(): Map<String, List<String>> = mirror.playlists()
    }
}
