package io.github.puflik.plinth.library

/**
 * `FakeLibraryRepository` проходит общий контракт (C3.3).
 *
 * На фейке тестируются экраны библиотеки, поэтому расхождение с контрактом
 * значит, что их тесты доказывают не то.
 */
class FakeLibraryRepositoryTest : LibraryRepositoryContractTest() {
    override fun createRepository(): LibraryRepository = FakeLibraryRepository()
}
