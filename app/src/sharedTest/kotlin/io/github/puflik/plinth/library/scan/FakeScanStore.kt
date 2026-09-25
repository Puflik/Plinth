package io.github.puflik.plinth.library.scan

/** Хранилище сканера v0.1 в памяти — для тестов сканера и `ScanWorker`. */
class FakeScanStore : ScanStore {
    private val tracks = mutableMapOf<Long, ScannedTrack>()
    private val missing = mutableSetOf<Long>()

    /** Видимые треки в порядке записи. */
    fun present(): List<ScannedTrack> = tracks.values.filterNot { it.id in missing }

    override suspend fun knownVersions(): Map<Long, Long> = present().associate { it.id to it.modifiedAt }

    override suspend fun upsert(tracks: Collection<ScannedTrack>) {
        for (track in tracks) {
            this.tracks[track.id] = track
            missing -= track.id
        }
    }

    override suspend fun markMissing(ids: Collection<Long>) {
        missing += ids.filter(tracks::containsKey)
    }
}
