package io.github.puflik.plinth.library

import io.github.puflik.plinth.ffi.TrackId

/**
 * `FakePlaylistRepository` проходит общий контракт (D4b): на фейке
 * тестируются вкладка «Плейлисты», экран плейлиста и «В плейлист».
 */
class FakePlaylistRepositoryTest : PlaylistRepositoryContractTest() {
    private val library = FakeLibraryRepository()

    override fun createRepository(): PlaylistRepository = FakePlaylistRepository(library)

    override suspend fun seed(
        repository: PlaylistRepository,
        paths: List<String>,
    ): List<TrackId> = library.add(paths.map(::TaggedFile)).map { it.id }

    override suspend fun hide(
        repository: PlaylistRepository,
        paths: List<String>,
    ) = library.hide(paths)
}
