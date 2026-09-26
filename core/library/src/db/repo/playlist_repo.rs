use plinth_types::{CoreError, EntityId, PlaylistEntryId, PlaylistId, Position};
use rusqlite::{OptionalExtension, Row, params};

use crate::db::Database;
use crate::db::codes::{Code, column};
use crate::db::sql::{Storage, id, invalid, timestamp};
use crate::model::{Playlist, PlaylistEntry};
use crate::sort::sort_key;

impl Database {
    /// Создаёт плейлист или переименовывает; записи не трогает.
    pub fn save_playlist(&self, playlist: &Playlist) -> Result<(), CoreError> {
        self.conn()
            .execute(
                "INSERT INTO playlist(id, name, kind, created_at) VALUES (?1, ?2, ?3, ?4)
                 ON CONFLICT(id) DO UPDATE SET name = excluded.name, kind = excluded.kind",
                params![playlist.id.as_bytes(), playlist.name, playlist.kind.code(), playlist.created_at.as_millis()],
            )
            .storage()
            .map(drop)
    }

    pub fn playlist(&self, id: PlaylistId) -> Result<Option<Playlist>, CoreError> {
        self.conn()
            .query_row("SELECT * FROM playlist WHERE id = ?1", [id.as_bytes()], read_playlist)
            .optional()
            .storage()
    }

    /// Все плейлисты по имени — как списки экранов (`crate::sort`): без
    /// регистра, числа по значению; одноимённые — по идентификатору.
    pub fn playlists(&self) -> Result<Vec<Playlist>, CoreError> {
        let mut statement = self.conn().prepare("SELECT * FROM playlist").storage()?;
        let mut playlists: Vec<Playlist> =
            statement.query_map([], read_playlist).storage()?.collect::<Result<_, _>>().storage()?;
        playlists.sort_by_cached_key(|playlist| (sort_key(&playlist.name), playlist.id));
        Ok(playlists)
    }

    pub fn rename_playlist(&self, id: PlaylistId, name: &str) -> Result<(), CoreError> {
        self.conn()
            .execute("UPDATE playlist SET name = ?2 WHERE id = ?1", params![id.as_bytes(), name])
            .storage()
            .map(drop)
    }

    /// Удаляет плейлист вместе с записями.
    pub fn delete_playlist(&self, id: PlaylistId) -> Result<(), CoreError> {
        self.conn().execute("DELETE FROM playlist WHERE id = ?1", [id.as_bytes()]).storage().map(drop)
    }

    /// Добавляет запись или переставляет её: у существующей меняется позиция.
    pub fn put_entry(&self, entry: &PlaylistEntry) -> Result<(), CoreError> {
        self.conn()
            .execute(
                "INSERT INTO playlist_entry(id, playlist, track, position, added_at) VALUES (?1, ?2, ?3, ?4, ?5)
                 ON CONFLICT(id) DO UPDATE SET position = excluded.position",
                params![
                    entry.id.as_bytes(),
                    entry.playlist.as_bytes(),
                    entry.track.as_bytes(),
                    entry.position.to_string(),
                    entry.added_at.as_millis()
                ],
            )
            .storage()
            .map(drop)
    }

    /// Переставляет запись: новая позиция, остальное прежнее.
    pub fn move_entry(&self, id: PlaylistEntryId, position: &Position) -> Result<(), CoreError> {
        self.conn()
            .execute(
                "UPDATE playlist_entry SET position = ?2 WHERE id = ?1",
                params![id.as_bytes(), position.to_string()],
            )
            .storage()
            .map(drop)
    }

    pub fn remove_entry(&self, id: PlaylistEntryId) -> Result<(), CoreError> {
        self.conn().execute("DELETE FROM playlist_entry WHERE id = ?1", [id.as_bytes()]).storage().map(drop)
    }

    /// Записи в порядке плейлиста — тот же порядок, что `model::ordered_entries`.
    pub fn entries(&self, playlist: PlaylistId) -> Result<Vec<PlaylistEntry>, CoreError> {
        let mut statement =
            self.conn().prepare("SELECT * FROM playlist_entry WHERE playlist = ?1 ORDER BY position, id").storage()?;
        statement.query_map([playlist.as_bytes()], read_entry).storage()?.collect::<Result<_, _>>().storage()
    }
}

fn read_playlist(row: &Row<'_>) -> rusqlite::Result<Playlist> {
    Ok(Playlist {
        id: id(row, "id")?,
        name: row.get("name")?,
        kind: column(row, "kind")?,
        created_at: timestamp(row, "created_at")?,
    })
}

fn read_entry(row: &Row<'_>) -> rusqlite::Result<PlaylistEntry> {
    let position: String = row.get("position")?;
    Ok(PlaylistEntry {
        id: id(row, "id")?,
        playlist: id(row, "playlist")?,
        track: id(row, "track")?,
        position: position.parse::<Position>().map_err(|_| invalid("position", &position))?,
        added_at: timestamp(row, "added_at")?,
    })
}

#[cfg(test)]
mod tests {
    use plinth_types::{PlaylistEntryId, PlaylistId, Timestamp, TrackId};

    use crate::db::repo::fixtures::db;
    use crate::model::{Playlist, PlaylistEntry, PlaylistKind, ordered_entries, position_for};

    fn mix() -> Playlist {
        Playlist {
            id: PlaylistId::new(),
            name: "Mix".to_owned(),
            kind: PlaylistKind::Manual,
            created_at: Timestamp::from_millis(1),
        }
    }

    /// Плейлист из пяти треков, каждый добавлен в конец.
    fn filled(db: &crate::db::Database, playlist: &Playlist) -> Vec<PlaylistEntry> {
        db.save_playlist(playlist).unwrap();
        for _ in 0..5 {
            let current = db.entries(playlist.id).unwrap();
            let entry = PlaylistEntry {
                id: PlaylistEntryId::new(),
                playlist: playlist.id,
                track: TrackId::new(),
                position: position_for(&current, current.len(), None),
                added_at: Timestamp::from_millis(2),
            };
            db.put_entry(&entry).unwrap();
        }
        db.entries(playlist.id).unwrap()
    }

    /// Порядок из базы совпадает с порядком модели: SQLite сортирует текст позиции так же.
    #[test]
    fn entries_come_back_in_model_order() {
        let db = db();
        let playlist = mix();

        let entries = filled(&db, &playlist);

        assert_eq!(entries.len(), 5);
        assert_eq!(ordered_entries(entries.clone()), entries);
    }

    /// Трек в плейлисте может ещё не быть в каталоге — журнал пришёл с другого устройства.
    #[test]
    fn entry_does_not_need_the_track_in_the_catalog() {
        let db = db();

        assert_eq!(filled(&db, &mix()).len(), 5);
    }

    #[test]
    fn moving_an_entry_rewrites_only_its_position() {
        let db = db();
        let playlist = mix();
        let entries = filled(&db, &playlist);
        let mut moved = entries[0].clone();

        moved.position = position_for(&entries, 3, Some(moved.id));
        db.put_entry(&moved).unwrap();

        let order: Vec<_> = db.entries(playlist.id).unwrap().iter().map(|e| e.id).collect();
        assert_eq!(order, vec![entries[1].id, entries[2].id, entries[3].id, moved.id, entries[4].id]);
    }

    #[test]
    fn rename_keeps_entries_and_delete_takes_them() {
        let db = db();
        let mut playlist = mix();
        filled(&db, &playlist);

        playlist.name = "Road".to_owned();
        db.save_playlist(&playlist).unwrap();
        assert_eq!(db.playlist(playlist.id).unwrap(), Some(playlist.clone()));
        assert_eq!(db.entries(playlist.id).unwrap().len(), 5);

        db.delete_playlist(playlist.id).unwrap();
        assert_eq!(db.playlist(playlist.id).unwrap(), None);
        assert!(db.entries(playlist.id).unwrap().is_empty());
    }

    #[test]
    fn renaming_keeps_everything_but_the_name() {
        let db = db();
        let playlist = mix();
        filled(&db, &playlist);

        db.rename_playlist(playlist.id, "Road").unwrap();

        let renamed = db.playlist(playlist.id).unwrap().unwrap();
        assert_eq!(renamed, Playlist { name: "Road".to_owned(), ..playlist.clone() });
        assert_eq!(db.entries(playlist.id).unwrap().len(), 5);
    }

    #[test]
    fn moving_by_id_rewrites_only_the_position() {
        let db = db();
        let playlist = mix();
        let entries = filled(&db, &playlist);
        let position = position_for(&entries, 5, Some(entries[0].id));

        db.move_entry(entries[0].id, &position).unwrap();

        let after = db.entries(playlist.id).unwrap();
        assert_eq!(after.last().map(|e| e.id), Some(entries[0].id));
        assert_eq!(after.last().map(|e| e.position.clone()), Some(position));
        assert_eq!(after.last().map(|e| e.track), Some(entries[0].track));
    }

    /// По имени, как списки экранов: регистр не важен, числа — по значению.
    #[test]
    fn playlists_go_by_name_in_natural_order() {
        let db = db();
        for name in ["mix 10", "Road", "Mix 2", "ambient"] {
            db.save_playlist(&Playlist { id: PlaylistId::new(), name: name.to_owned(), ..mix() }).unwrap();
        }

        let names: Vec<String> = db.playlists().unwrap().into_iter().map(|p| p.name).collect();

        assert_eq!(names, ["ambient", "Mix 2", "mix 10", "Road"]);
    }

    #[test]
    fn removed_entry_is_gone() {
        let db = db();
        let playlist = mix();
        let entries = filled(&db, &playlist);

        db.remove_entry(entries[2].id).unwrap();

        assert!(!db.entries(playlist.id).unwrap().iter().any(|e| e.id == entries[2].id));
    }
}
