package io.github.puflik.plinth.online

/**
 * `FakeOnlineRepository` проходит общий контракт (E3b): на фейке тестируются
 * экраны поиска и альбома.
 */
class FakeOnlineRepositoryTest : OnlineRepositoryContractTest() {
    override fun world(): World = FakeWorld(FakeOnlineRepository())

    private class FakeWorld(
        override val online: FakeOnlineRepository,
    ) : World {
        override var networkUp: Boolean
            get() = online.networkUp
            set(value) {
                online.networkUp = value
            }
    }
}
