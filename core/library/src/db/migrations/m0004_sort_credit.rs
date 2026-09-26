//! Миграция 4 — исполнитель для сортировки из тегов (`TSOP`, `TSO2`) у трека
//! и альбома. Нет его — ключ `artist_sort` считается по `artist_credit`, как
//! раньше, поэтому ключи уже существующих строк не меняются.
//!
//! Скан читает новые теги только у новых и изменённых файлов. Чтобы их
//! получили и уже известные, отметки скана сбрасываются: следующий скан один
//! раз перечитает теги всех файлов. Папка файла остаётся.
//! **Заморожена** после выпуска (ADR 0011).

use super::Migration;

pub(crate) const MIGRATION: Migration = Migration { version: 4, name: "sort_credit", apply };

/// Время изменения, которого не бывает у файла: с ним файл считается изменённым.
const NEVER: i64 = -1;

fn apply(tx: &rusqlite::Transaction<'_>) -> rusqlite::Result<()> {
    tx.execute_batch(
        "ALTER TABLE track ADD COLUMN sort_artist_credit TEXT;
         ALTER TABLE album ADD COLUMN sort_artist_credit TEXT;",
    )?;
    tx.execute("UPDATE scan_file SET modified_at = ?1", [NEVER]).map(drop)
}

#[cfg(test)]
mod tests {
    use rusqlite::Connection;

    use crate::db::migrations::{MIGRATIONS, migrate};

    /// База v3 с файлом: строки получают пустую строку сортировки и прежние
    /// ключи, а файл — отметку, по которой скан перечитает его теги.
    #[test]
    fn rows_keep_their_keys_and_files_are_read_again() {
        let mut conn = Connection::open_in_memory().unwrap();
        migrate(&mut conn, &MIGRATIONS[..3], None).unwrap();
        conn.execute_batch(
            "INSERT INTO artist(id, name, name_normalized, name_sort) VALUES (x'01', 'The Beatles', 'the beatles', 'beatles');
             INSERT INTO album(id, title, title_normalized, artist_credit, artist_sort)
                 VALUES (x'02', 'Abbey Road', 'abbey road', 'The Beatles', 'beatles');
             INSERT INTO track(id, title, title_normalized, artist_credit, artist_normalized, added_at, artist_sort)
                 VALUES (x'03', 'Something', 'something', 'The Beatles', 'the beatles', 1, 'beatles');
             INSERT INTO version(id, track, kind, explicitness) VALUES (x'04', x'03', 'original', 'unknown');
             INSERT INTO source(id, version, local_uri, format, availability)
                 VALUES (x'05', x'04', '/Music/Something.flac', 'flac', 'available');
             INSERT INTO scan_file(source, modified_at, size, folder) VALUES (x'05', 1758000000000, 4096, 'Music/');",
        )
        .unwrap();

        migrate(&mut conn, MIGRATIONS, None).unwrap();

        let row = |sql: &str| {
            conn.query_row(sql, [], |row| Ok((row.get::<_, Option<String>>(0)?, row.get::<_, String>(1)?))).unwrap()
        };
        assert_eq!(row("SELECT sort_artist_credit, artist_sort FROM track"), (None, "beatles".to_owned()));
        assert_eq!(row("SELECT sort_artist_credit, artist_sort FROM album"), (None, "beatles".to_owned()));
        let stamp = conn
            .query_row("SELECT modified_at, size, folder FROM scan_file", [], |row| {
                Ok((row.get::<_, i64>(0)?, row.get::<_, i64>(1)?, row.get::<_, String>(2)?))
            })
            .unwrap();
        assert_eq!(stamp, (-1, 4096, "Music/".to_owned()));
    }
}
