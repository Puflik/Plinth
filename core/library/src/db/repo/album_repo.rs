use plinth_types::{AlbumId, CoreError, EntityId};
use rusqlite::{OptionalExtension, Row, params};

use crate::db::Database;
use crate::db::sql::{Storage, first_id, id, opt_mbid};
use crate::model::Album;
use crate::text::normalize;

impl Database {
    pub fn save_album(&self, album: &Album) -> Result<(), CoreError> {
        let tx = self.conn().unchecked_transaction().storage()?;
        tx.execute(
            // UPSERT, а не REPLACE: REPLACE удаляет строку, и версии потеряли бы альбом.
            "INSERT INTO album(id, title, title_normalized, artist_credit, year, label, country,
                               disc_count, mbid_release)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
             ON CONFLICT(id) DO UPDATE SET
                 title = excluded.title, title_normalized = excluded.title_normalized,
                 artist_credit = excluded.artist_credit, year = excluded.year, label = excluded.label,
                 country = excluded.country, disc_count = excluded.disc_count,
                 mbid_release = excluded.mbid_release",
            params![
                album.id.as_bytes(),
                album.title,
                normalize(&album.title),
                album.artist_credit,
                album.year,
                album.label,
                album.country,
                album.disc_count,
                album.mbid_release.map(|m| m.to_string())
            ],
        )
        .storage()?;
        tx.execute("DELETE FROM album_artist WHERE album = ?1", [album.id.as_bytes()]).storage()?;
        for (ord, artist) in (0_i64..).zip(&album.artists) {
            tx.execute(
                "INSERT OR IGNORE INTO album_artist(album, artist, ord) VALUES (?1, ?2, ?3)",
                params![album.id.as_bytes(), artist.as_bytes(), ord],
            )
            .storage()?;
        }
        tx.commit().storage()
    }

    pub fn album(&self, id: AlbumId) -> Result<Option<Album>, CoreError> {
        let found = self
            .conn()
            .query_row("SELECT * FROM album WHERE id = ?1", [id.as_bytes()], read_album)
            .optional()
            .storage()?;
        let Some(mut album) = found else { return Ok(None) };
        let mut statement =
            self.conn().prepare("SELECT artist FROM album_artist WHERE album = ?1 ORDER BY ord").storage()?;
        album.artists =
            statement.query_map([id.as_bytes()], first_id).storage()?.collect::<Result<_, _>>().storage()?;
        Ok(Some(album))
    }
}

fn read_album(row: &Row<'_>) -> rusqlite::Result<Album> {
    Ok(Album {
        id: id(row, "id")?,
        title: row.get("title")?,
        artist_credit: row.get("artist_credit")?,
        artists: Vec::new(),
        year: row.get("year")?,
        label: row.get("label")?,
        country: row.get("country")?,
        disc_count: row.get("disc_count")?,
        mbid_release: opt_mbid(row, "mbid_release")?,
    })
}

#[cfg(test)]
mod tests {
    use crate::db::repo::fixtures::{album, artist, db};

    #[test]
    fn album_round_trips_with_artists_in_order() {
        let db = db();
        let (a, b) = (artist("Massive Attack"), artist("Tracey Thorn"));
        db.save_artist(&a).unwrap();
        db.save_artist(&b).unwrap();
        let mut protection = album(&a);
        protection.artists = vec![a.id, b.id];

        db.save_album(&protection).unwrap();

        assert_eq!(db.album(protection.id).unwrap(), Some(protection));
    }

    /// Альбом ссылается на артистов внешним ключом: неизвестный артист — ошибка, а не молчание.
    #[test]
    fn album_needs_known_artists() {
        let db = db();
        let unknown = artist("Nobody");

        assert!(db.save_album(&album(&unknown)).is_err());
    }
}
