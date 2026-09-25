use plinth_types::{ArtistId, CoreError, EntityId, TrackId};
use rusqlite::{Row, params};

use crate::db::Database;
use crate::db::sql::{Storage, invalid, timestamp};
use crate::model::{BlockEntry, BlockTarget};

impl Database {
    /// Добавляет в чёрный список; повтор обновляет дату.
    pub fn block(&self, entry: &BlockEntry) -> Result<(), CoreError> {
        let (kind, target) = split(entry.target);
        self.conn()
            .execute(
                "INSERT OR REPLACE INTO block_entry(kind, target, since) VALUES (?1, ?2, ?3)",
                params![kind, target, entry.since.as_millis()],
            )
            .storage()
            .map(drop)
    }

    pub fn unblock(&self, target: BlockTarget) -> Result<(), CoreError> {
        let (kind, target) = split(target);
        self.conn()
            .execute("DELETE FROM block_entry WHERE kind = ?1 AND target = ?2", params![kind, target])
            .storage()
            .map(drop)
    }

    /// Чёрный список: сначала артисты, потом треки.
    pub fn blocklist(&self) -> Result<Vec<BlockEntry>, CoreError> {
        let mut statement = self.conn().prepare("SELECT * FROM block_entry ORDER BY kind, target").storage()?;
        statement.query_map([], read_entry).storage()?.collect::<Result<_, _>>().storage()
    }
}

fn split(target: BlockTarget) -> (&'static str, [u8; 16]) {
    match target {
        BlockTarget::Track(track) => ("track", *track.as_bytes()),
        BlockTarget::Artist(artist) => ("artist", *artist.as_bytes()),
    }
}

fn read_entry(row: &Row<'_>) -> rusqlite::Result<BlockEntry> {
    let kind: String = row.get("kind")?;
    let bytes: [u8; 16] = row.get("target")?;
    let target = match kind.as_str() {
        "track" => BlockTarget::Track(TrackId::from_bytes(bytes)),
        "artist" => BlockTarget::Artist(ArtistId::from_bytes(bytes)),
        other => return Err(invalid("kind", other)),
    };
    Ok(BlockEntry { target, since: timestamp(row, "since")? })
}

#[cfg(test)]
mod tests {
    use plinth_types::{ArtistId, Timestamp, TrackId};

    use crate::db::repo::fixtures::db;
    use crate::model::{BlockEntry, BlockTarget};

    /// Трек и артист с одинаковыми байтами идентификатора — разные записи.
    #[test]
    fn tracks_and_artists_are_blocked_separately() {
        let db = db();
        let track = BlockTarget::Track(TrackId::new());
        let artist = BlockTarget::Artist(ArtistId::new());
        db.block(&BlockEntry { target: track, since: Timestamp::from_millis(1) }).unwrap();
        db.block(&BlockEntry { target: artist, since: Timestamp::from_millis(2) }).unwrap();

        db.unblock(track).unwrap();

        assert_eq!(db.blocklist().unwrap(), vec![BlockEntry { target: artist, since: Timestamp::from_millis(2) }]);
    }
}
