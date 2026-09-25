//! Миграция 3 — ключи сортировки (D3.2) и папка файла (D3).
//!
//! Ключ — название без ведущего артикля в естественном порядке
//! (`crate::sort`). Списки экранов сортируются `ORDER BY` по нему. Для
//! строк, которые уже есть, ключи считает сама миграция. Изменится
//! алгоритм ключа — пересчёт делает новая миграция.
//!
//! Папка файла — от корня тома, для вкладки «Папки». У файлов, отсканированных
//! до этой миграции, она пустая до следующего изменения файла.
//! **Заморожена** после выпуска (ADR 0011).

use rusqlite::params;

use super::Migration;
use crate::sort::sort_key;

pub(crate) const MIGRATION: Migration = Migration { version: 3, name: "sort_keys", apply };

fn apply(tx: &rusqlite::Transaction<'_>) -> rusqlite::Result<()> {
    tx.execute_batch(
        "ALTER TABLE track ADD COLUMN title_sort TEXT NOT NULL DEFAULT '';
         ALTER TABLE track ADD COLUMN artist_sort TEXT NOT NULL DEFAULT '';
         ALTER TABLE album ADD COLUMN title_sort TEXT NOT NULL DEFAULT '';
         ALTER TABLE album ADD COLUMN artist_sort TEXT NOT NULL DEFAULT '';
         ALTER TABLE artist ADD COLUMN name_sort TEXT NOT NULL DEFAULT '';
         ALTER TABLE scan_file ADD COLUMN folder TEXT NOT NULL DEFAULT '';
         CREATE INDEX track_title_sort ON track(title_sort);
         CREATE INDEX track_artist_sort ON track(artist_sort);
         CREATE INDEX album_title_sort ON album(title_sort);
         CREATE INDEX artist_name_sort ON artist(name_sort);",
    )?;
    for (table, text, key) in [
        ("track", "title", "title_sort"),
        ("track", "artist_credit", "artist_sort"),
        ("album", "title", "title_sort"),
        ("album", "artist_credit", "artist_sort"),
        ("artist", "name", "name_sort"),
    ] {
        fill(tx, table, text, key)?;
    }
    Ok(())
}

/// Считает ключ `key` из колонки `text` для всех строк `table`.
fn fill(tx: &rusqlite::Transaction<'_>, table: &str, text: &str, key: &str) -> rusqlite::Result<()> {
    let rows: Vec<(Vec<u8>, String)> = {
        let mut select = tx.prepare(&format!("SELECT id, {text} FROM {table}"))?;
        select.query_map([], |row| Ok((row.get(0)?, row.get(1)?)))?.collect::<Result<_, _>>()?
    };
    let mut update = tx.prepare(&format!("UPDATE {table} SET {key} = ?1 WHERE id = ?2"))?;
    for (id, value) in rows {
        update.execute(params![sort_key(&value), id])?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use rusqlite::Connection;

    use crate::db::migrations::{MIGRATIONS, migrate};
    use crate::sort::key;

    /// База v2 со строками каталога: после миграции у каждой строки свой ключ.
    #[test]
    fn keys_are_filled_for_existing_rows() {
        let mut conn = Connection::open_in_memory().unwrap();
        migrate(&mut conn, &MIGRATIONS[..2], None).unwrap();
        conn.execute_batch(
            "INSERT INTO artist(id, name, name_normalized) VALUES (x'01', 'The Beatles', 'the beatles');
             INSERT INTO album(id, title, title_normalized, artist_credit) VALUES (x'02', 'Track 10', 'track 10', 'The Beatles');
             INSERT INTO track(id, title, title_normalized, artist_credit, artist_normalized, added_at)
                 VALUES (x'03', 'Élan', 'elan', 'The Beatles', 'the beatles', 1);",
        )
        .unwrap();

        migrate(&mut conn, MIGRATIONS, None).unwrap();

        let stored = |sql: &str| conn.query_row(sql, [], |row| row.get::<_, String>(0)).unwrap();
        assert_eq!(stored("SELECT name_sort FROM artist"), key("beatles"), "артикль снят");
        assert_eq!(stored("SELECT title_sort FROM album"), key("track 10"));
        assert_eq!(stored("SELECT artist_sort FROM album"), key("beatles"));
        assert_eq!(stored("SELECT title_sort FROM track"), key("elan"));
        assert_eq!(stored("SELECT artist_sort FROM track"), key("beatles"));
        assert_eq!(stored("SELECT title_sort FROM album"), "track 00000000000000000010");
    }
}
