package io.github.puflik.plinth.online

/** Explicit acknowledgement for pre-readiness tests; readiness guards use a controlled barrier. */
class FakeOnlineReadiness : OnlineReadiness {
    override suspend fun awaitConfiguration(configuration: OnlineConfiguration): OnlineReady = OnlineReady.Applied(1L)
}
