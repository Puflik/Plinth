use plinth_types::{AlbumId, CoreError, EntityId};
use rusqlite::{OptionalExtension, Row, params};

use crate::db::Database;
use crate::db::sql::{Storage, atomically, first_id, id, opt_mbid};
use crate::model::Album;
use crate::sort::sort_key;
use crate::text::normalize;

impl Database {
    pub fn save_album(&self, album: &Album) -> Result<(), CoreError> {
        atomically(self.conn(), |tx| {
            tx.execute(
                // UPSERT, а не REPLACE: REPLACE удаляет строку, и версии потеряли бы альбом.
                "INSERT INTO album(id, title, title_normalized, artist_credit, year, label, country,
                                   disc_count, mbid_release, title_sort, artist_sort)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)
                 ON CONFLICT(id) DO UPDATE SET
                     title = excluded.title, title_normalized = excluded.title_normalized,
                     artist_credit = excluded.artist_credit, year = excluded.year, label = excluded.label,
                     country = excluded.country, disc_count = excluded.disc_count,
                     mbid_release = excluded.mbid_release, title_sort = excluded.title_sort,
                     artist_sort = excluded.artist_sort",
                params![
                    album.id.as_bytes(),
                    album.title,
                    normalize(&album.title),
                    album.artist_credit,
                    album.year,
                    album.label,
                    album.country,
                    album.disc_count,
                    album.mbid_release.map(|m| m.to_string()),
                    sort_key(&album.title),
                    sort_key(&album.artist_credit)
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
            Ok(())
        })
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

    /// Альбомы с тем же названием после нормализации — скан ищет среди них
    /// свой по исполнителю альбома.
    pub fn albums_titled(&self, title: &str) -> Result<Vec<Album>, CoreError> {
        let ids: Vec<AlbumId> = {
            let mut statement =
                self.conn().prepare("SELECT id FROM album WHERE title_normalized = ?1 ORDER BY id").storage()?;
            statement.query_map([normalize(title)], first_id).storage()?.collect::<Result<_, _>>().storage()?
        };
        ids.into_iter().filter_map(|id| self.album(id).transpose()).collect()
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
    use plinth_types::EntityId;

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

    #[test]
    fn albums_are_found_by_normalized_title() {
        let db = db();
        let radiohead = artist("Radiohead");
        db.save_artist(&radiohead).unwrap();
        let mut ok = album(&radiohead);
        ok.title = "OK Computer".to_owned();
        let mut again = album(&radiohead);
        again.title = "ok  computer!".to_owned();
        let mut other = album(&radiohead);
        other.title = "Kid A".to_owned();
        for album in [&ok, &again, &other] {
            db.save_album(album).unwrap();
        }

        let found = db.albums_titled("OK COMPUTER").unwrap();

        let mut expected = vec![ok.id, again.id];
        expected.sort_by_key(|id| *id.as_bytes());
        assert_eq!(found.iter().map(|a| a.id).collect::<Vec<_>>(), expected);
        assert_eq!(found.iter().find(|a| a.id == ok.id).unwrap().artists, vec![radiohead.id]);
    }

    /// Альбом ссылается на артистов внешним ключом: неизвестный артист — ошибка, а не молчание.
    #[test]
    fn album_needs_known_artists() {
        let db = db();
        let unknown = artist("Nobody");

        assert!(db.save_album(&album(&unknown)).is_err());
    }
}
