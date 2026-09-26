//! Треки плейлиста для экрана (D4b): видимые строки в порядке плейлиста,
//! каждая — со своей записью.
//!
//! Пропавший файл скрыт, но его запись в плейлисте остаётся: файл вернётся —
//! трек встанет на своё место. Поэтому индекс строки на экране — не индекс
//! записи среди всех (`Database::entries`): перестановку экран переводит сам.

use plinth_types::{CoreError, EntityId, PlaylistEntryId, PlaylistId};
use rusqlite::Row;

use super::{PLAYABLE, TRACK_COLUMNS, TRACK_JOINS, TrackRow, read_row};
use crate::db::Database;
use crate::db::sql::{Storage, id};

/// Строка плейлиста: запись и её трек. Один трек может стоять в плейлисте
/// несколько раз — строки различает запись.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlaylistRow {
    pub entry: PlaylistEntryId,
    pub track: TrackRow,
}

impl Database {
    /// Видимые треки плейлиста в его порядке — том же, что у `entries`.
    /// Плейлиста нет — строк нет.
    pub fn playlist_tracks(&self, playlist: PlaylistId) -> Result<Vec<PlaylistRow>, CoreError> {
        let sql = format!(
            "{TRACK_COLUMNS}, e.id AS entry {PLAYABLE}
             JOIN playlist_entry e ON e.track = t.id {TRACK_JOINS}
             WHERE e.playlist = ?1
             ORDER BY e.position, e.id"
        );
        let mut statement = self.conn().prepare(&sql).storage()?;
        statement.query_map([playlist.as_bytes()], read_playlist_row).storage()?.collect::<Result<_, _>>().storage()
    }
}

fn read_playlist_row(row: &Row<'_>) -> rusqlite::Result<PlaylistRow> {
    Ok(PlaylistRow { entry: id(row, "entry")?, track: read_row(row)? })
}

#[cfg(test)]
mod tests {
    use plinth_types::{PlaylistEntryId, PlaylistId, Timestamp, TrackId};

    use super::super::testing::Library;
    use crate::model::{Playlist, PlaylistEntry, PlaylistKind, TrackUserData, position_for};

    fn playlist(lib: &Library, name: &str) -> PlaylistId {
        let playlist = Playlist {
            id: PlaylistId::new(),
            name: name.to_owned(),
            kind: PlaylistKind::Manual,
            created_at: Timestamp::from_millis(1),
        };
        lib.db.save_playlist(&playlist).unwrap();
        playlist.id
    }

    /// Добавляет `track` в конец плейлиста.
    fn append(lib: &Library, playlist: PlaylistId, track: TrackId) -> PlaylistEntryId {
        let entries = lib.db.entries(playlist).unwrap();
        let entry = PlaylistEntry {
            id: PlaylistEntryId::new(),
            playlist,
            track,
            position: position_for(&entries, entries.len(), None),
            added_at: Timestamp::from_millis(2),
        };
        lib.db.put_entry(&entry).unwrap();
        entry.id
    }

    fn titles(lib: &Library, playlist: PlaylistId) -> Vec<String> {
        lib.db.playlist_tracks(playlist).unwrap().into_iter().map(|row| row.track.title).collect()
    }

    /// Порядок — плейлиста, а не названий; повтор трека — две строки со
    /// своими записями.
    #[test]
    fn rows_follow_the_playlist_and_carry_their_entries() {
        let mut lib = Library::new();
        let (first, second) = (lib.track("A", "X", &[], None, true), lib.track("B", "X", &[], None, true));
        let mix = playlist(&lib, "Mix");
        let entries = [append(&lib, mix, second), append(&lib, mix, first), append(&lib, mix, second)];

        let rows = lib.db.playlist_tracks(mix).unwrap();

        assert_eq!(rows.iter().map(|row| row.entry).collect::<Vec<_>>(), entries);
        assert_eq!(rows.iter().map(|row| row.track.id).collect::<Vec<_>>(), [second, first, second]);
    }

    /// Пропавший файл скрыт, а его запись — нет: среди всех записей она на месте.
    #[test]
    fn a_missing_file_is_hidden_but_keeps_its_entry() {
        let mut lib = Library::new();
        let (a, lost, b) = (
            lib.track("A", "X", &[], None, true),
            lib.track("Lost", "X", &[], None, false),
            lib.track("B", "X", &[], None, true),
        );
        let mix = playlist(&lib, "Mix");
        for track in [a, lost, b] {
            append(&lib, mix, track);
        }

        assert_eq!(titles(&lib, mix), ["A", "B"]);
        assert_eq!(lib.db.entries(mix).unwrap().len(), 3);
    }

    /// Строка — та же, что в списках треков: с файлом и пользовательским.
    #[test]
    fn a_row_is_a_track_row_of_its_playlist_only() {
        let mut lib = Library::new();
        let (song, other) = (lib.track("Song", "X", &[], None, true), lib.track("Other", "X", &[], None, true));
        let (mix, road) = (playlist(&lib, "Mix"), playlist(&lib, "Road"));
        append(&lib, mix, song);
        append(&lib, road, other);
        let data = TrackUserData { liked: true, ..TrackUserData::empty(song) };
        lib.db.save_user_data(&data).unwrap();

        let row = lib.db.playlist_tracks(mix).unwrap().remove(0).track;

        assert_eq!(titles(&lib, mix), ["Song"]);
        assert!(row.liked);
        assert_eq!(row.uri.as_deref(), Some("/storage/emulated/0/Music/X/Song.mp3"));
        assert!(lib.db.playlist_tracks(PlaylistId::new()).unwrap().is_empty());
    }
}
