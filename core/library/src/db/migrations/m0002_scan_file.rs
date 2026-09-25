//! Миграция 2 — учёт сканера (D1). Время изменения и размер файла на этом
//! устройстве: по ним повторный скан перечитывает только изменённые файлы.
//! Это не каталог и не журнал — пропадёт, значит, файлы прочитаются заново.
//! **Заморожена** после выпуска (ADR 0011).

use super::Migration;

pub(crate) const MIGRATION: Migration = Migration { version: 2, name: "scan_file", apply };

fn apply(tx: &rusqlite::Transaction<'_>) -> rusqlite::Result<()> {
    tx.execute_batch(
        "CREATE TABLE scan_file (
            source      BLOB PRIMARY KEY REFERENCES source(id) ON DELETE CASCADE,
            modified_at INTEGER NOT NULL,
            size        INTEGER NOT NULL CHECK (size >= 0)
        ) STRICT;",
    )
}
