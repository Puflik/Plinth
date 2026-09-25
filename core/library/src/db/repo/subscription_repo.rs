use plinth_types::{ArtistId, CoreError, EntityId};
use rusqlite::{Row, params};

use crate::db::Database;
use crate::db::sql::{Storage, id, timestamp};
use crate::model::Subscription;

impl Database {
    /// Подписывает на артиста; повторная подписка обновляет дату.
    pub fn subscribe(&self, subscription: &Subscription) -> Result<(), CoreError> {
        self.conn()
            .execute(
                "INSERT OR REPLACE INTO subscription(artist, since) VALUES (?1, ?2)",
                params![subscription.artist.as_bytes(), subscription.since.as_millis()],
            )
            .storage()
            .map(drop)
    }

    pub fn unsubscribe(&self, artist: ArtistId) -> Result<(), CoreError> {
        self.conn().execute("DELETE FROM subscription WHERE artist = ?1", [artist.as_bytes()]).storage().map(drop)
    }

    /// Подписки по артисту.
    pub fn subscriptions(&self) -> Result<Vec<Subscription>, CoreError> {
        let mut statement = self.conn().prepare("SELECT * FROM subscription ORDER BY artist").storage()?;
        statement.query_map([], read_subscription).storage()?.collect::<Result<_, _>>().storage()
    }
}

fn read_subscription(row: &Row<'_>) -> rusqlite::Result<Subscription> {
    Ok(Subscription { artist: id(row, "artist")?, since: timestamp(row, "since")? })
}

#[cfg(test)]
mod tests {
    use plinth_types::{ArtistId, Timestamp};

    use crate::db::repo::fixtures::db;
    use crate::model::Subscription;

    #[test]
    fn subscribe_and_unsubscribe() {
        let db = db();
        let (a, b) = (ArtistId::new(), ArtistId::new());
        db.subscribe(&Subscription { artist: a, since: Timestamp::from_millis(1) }).unwrap();
        db.subscribe(&Subscription { artist: b, since: Timestamp::from_millis(2) }).unwrap();

        db.unsubscribe(a).unwrap();

        assert_eq!(db.subscriptions().unwrap(), vec![Subscription { artist: b, since: Timestamp::from_millis(2) }]);
    }
}
