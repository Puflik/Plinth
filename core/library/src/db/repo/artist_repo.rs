use plinth_types::{ArtistId, CoreError, EntityId};
use rusqlite::{OptionalExtension, Row, params};

use crate::db::Database;
use crate::db::sql::{Storage, id, opt_mbid};
use crate::model::Artist;
use crate::text::normalize;

impl Database {
    pub fn save_artist(&self, artist: &Artist) -> Result<(), CoreError> {
        let tx = self.conn().unchecked_transaction().storage()?;
        tx.execute(
            // UPSERT, а не REPLACE: REPLACE удаляет строку, и каскад стёр бы
            // связи артиста с треками и альбомами.
            "INSERT INTO artist(id, name, name_normalized, sort_name, mbid, bio)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT(id) DO UPDATE SET
                 name = excluded.name, name_normalized = excluded.name_normalized,
                 sort_name = excluded.sort_name, mbid = excluded.mbid, bio = excluded.bio",
            params![
                artist.id.as_bytes(),
                artist.name,
                normalize(&artist.name),
                artist.sort_name,
                artist.mbid.map(|m| m.to_string()),
                artist.bio
            ],
        )
        .storage()?;
        tx.execute("DELETE FROM artist_alias WHERE artist = ?1", [artist.id.as_bytes()]).storage()?;
        for alias in &artist.aliases {
            tx.execute(
                "INSERT OR IGNORE INTO artist_alias(artist, alias) VALUES (?1, ?2)",
                params![artist.id.as_bytes(), alias],
            )
            .storage()?;
        }
        tx.commit().storage()
    }

    pub fn artist(&self, id: ArtistId) -> Result<Option<Artist>, CoreError> {
        let found = self
            .conn()
            .query_row("SELECT * FROM artist WHERE id = ?1", [id.as_bytes()], read_artist)
            .optional()
            .storage()?;
        found.map(|artist| self.with_aliases(artist)).transpose()
    }

    /// Артисты с тем же именем после нормализации: «The Beatles» = «the  beatles».
    pub fn artists_named(&self, name: &str) -> Result<Vec<Artist>, CoreError> {
        let mut statement =
            self.conn().prepare("SELECT * FROM artist WHERE name_normalized = ?1 ORDER BY id").storage()?;
        let artists: Vec<Artist> =
            statement.query_map([normalize(name)], read_artist).storage()?.collect::<Result<_, _>>().storage()?;
        artists.into_iter().map(|artist| self.with_aliases(artist)).collect()
    }

    fn with_aliases(&self, mut artist: Artist) -> Result<Artist, CoreError> {
        let mut statement =
            self.conn().prepare("SELECT alias FROM artist_alias WHERE artist = ?1 ORDER BY alias").storage()?;
        artist.aliases = statement
            .query_map([artist.id.as_bytes()], |row| row.get(0))
            .storage()?
            .collect::<Result<_, _>>()
            .storage()?;
        Ok(artist)
    }
}

fn read_artist(row: &Row<'_>) -> rusqlite::Result<Artist> {
    Ok(Artist {
        id: id(row, "id")?,
        name: row.get("name")?,
        sort_name: row.get("sort_name")?,
        mbid: opt_mbid(row, "mbid")?,
        aliases: Vec::new(),
        bio: row.get("bio")?,
    })
}

#[cfg(test)]
mod tests {
    use crate::db::repo::fixtures::{artist, db};

    #[test]
    fn artist_round_trips_with_aliases() {
        let db = db();
        let radiohead = artist("Radiohead");

        db.save_artist(&radiohead).unwrap();

        assert_eq!(db.artist(radiohead.id).unwrap(), Some(radiohead));
    }

    #[test]
    fn saving_again_replaces_aliases() {
        let db = db();
        let mut radiohead = artist("Radiohead");
        db.save_artist(&radiohead).unwrap();

        radiohead.aliases = vec!["On a Friday".to_owned()];
        db.save_artist(&radiohead).unwrap();

        assert_eq!(db.artist(radiohead.id).unwrap().unwrap().aliases, vec!["On a Friday".to_owned()]);
    }

    /// Пересохранение артиста (новый псевдоним, биография) не рвёт связи с треками и альбомами.
    #[test]
    fn saving_an_artist_again_keeps_links_to_tracks_and_albums() {
        let db = db();
        let (mut radiohead, album, track, _, _) = crate::db::repo::fixtures::creep(&db);

        radiohead.bio = Some("Oxfordshire".to_owned());
        db.save_artist(&radiohead).unwrap();

        assert_eq!(db.track(track.id).unwrap().unwrap().artists, vec![radiohead.id]);
        assert_eq!(db.album(album.id).unwrap().unwrap().artists, vec![radiohead.id]);
    }

    #[test]
    fn found_by_normalized_name() {
        let db = db();
        let beatles = artist("The Beatles");
        db.save_artist(&beatles).unwrap();
        db.save_artist(&artist("Radiohead")).unwrap();

        let found = db.artists_named("  the BEATLES ").unwrap();

        assert_eq!(found.iter().map(|a| a.id).collect::<Vec<_>>(), vec![beatles.id]);
    }
}
