package io.github.puflik.plinth.library

/**
 * `FakeUserDataRepository` проходит общий контракт (D4): на фейке
 * тестируются плеер, списки и запись истории.
 */
class FakeUserDataRepositoryTest : UserDataRepositoryContractTest() {
    override fun createRepository(): UserDataRepository = FakeUserDataRepository()

    override suspend fun seed(
        repository: UserDataRepository,
        paths: List<String>,
    ) {
        (repository as FakeUserDataRepository).add(*paths.toTypedArray())
    }
}
