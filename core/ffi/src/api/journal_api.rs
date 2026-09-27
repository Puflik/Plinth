//! Действия пользователя (A3.1): лайк, оценка, плейлисты, прослушивания,
//! синхронизируемые настройки. Каждое — операция журнала, в базу она
//! попадает проекцией (ADR 0007): мимо журнала ядро пользовательское не пишет.

use plinth_library::db::query::TrackSort;
use plinth_library::model::{
    PlayEvent, Playlist, PlaylistEntry, PlaylistKind, Rating, Setting, SyncedSettings, VersionPreference, position_for,
};
use plinth_library::playlist::{match_lines, read};
use plinth_sync::journal::{Op, record_and_project_all};
use plinth_types::{CoreError, PlayEventId, PlaylistEntryId, PlaylistId, Position, Timestamp, TrackId};

use crate::panic;
use crate::session::Core;
use crate::types::{NewPlay, PlaylistImport, PlaylistItem};

#[uniffi::export]
impl Core {
    pub fn like(&self, track: TrackId) -> Result<(), CoreError> {
        self.record(&Op::Like { track })
    }

    pub fn unlike(&self, track: TrackId) -> Result<(), CoreError> {
        self.record(&Op::Unlike { track })
    }

    /// Оценка от 1 до 5; `None` — снять.
    pub fn rate(&self, track: TrackId, rating: Option<Rating>) -> Result<(), CoreError> {
        self.record(&Op::Rate { track, rating })
    }

    pub fn create_playlist(&self, name: String) -> Result<Playlist, CoreError> {
        let playlist =
            Playlist { id: PlaylistId::new(), name, kind: PlaylistKind::Manual, created_at: Timestamp::now() };
        self.record(&Op::CreatePlaylist(playlist.clone()))?;
        Ok(playlist)
    }

    pub fn rename_playlist(&self, playlist: PlaylistId, name: String) -> Result<(), CoreError> {
        self.record(&Op::RenamePlaylist { playlist, name })
    }

    /// Удаляет плейлист вместе с записями.
    pub fn delete_playlist(&self, playlist: PlaylistId) -> Result<(), CoreError> {
        self.record(&Op::DeletePlaylist { playlist })
    }

    /// Плейлисты по имени.
    pub fn playlists(&self) -> Result<Vec<Playlist>, CoreError> {
        panic::guard(|| self.with(|state| state.db.playlists()))
    }

    /// Записи плейлиста в его порядке.
    pub fn playlist_items(&self, playlist: PlaylistId) -> Result<Vec<PlaylistItem>, CoreError> {
        panic::guard(|| {
            self.with(|state| {
                let entries = state.db.entries(playlist)?;
                Ok(entries
                    .into_iter()
                    .map(|e| PlaylistItem { id: e.id, track: e.track, added_at: e.added_at })
                    .collect())
            })
        })
    }

    /// Добавляет трек на место `index`; `None` или за концом — в конец.
    /// Один трек может стоять в плейлисте несколько раз.
    pub fn add_to_playlist(
        &self,
        playlist: PlaylistId,
        track: TrackId,
        index: Option<u32>,
    ) -> Result<PlaylistEntryId, CoreError> {
        panic::guard(|| {
            self.with(|state| {
                let entries = state.db.entries(playlist)?;
                let at = index.map_or(entries.len(), |i| i as usize);
                let entry = PlaylistEntry {
                    id: PlaylistEntryId::new(),
                    playlist,
                    track,
                    position: position_for(&entries, at, None),
                    added_at: Timestamp::now(),
                };
                let id = entry.id;
                plinth_sync::journal::record_and_project(&mut state.journal, &state.db, &Op::AddEntry(entry))?;
                Ok(id)
            })
        })
    }

    /// Переставляет запись так, что она оказывается на месте `index`.
    pub fn move_in_playlist(&self, playlist: PlaylistId, entry: PlaylistEntryId, index: u32) -> Result<(), CoreError> {
        panic::guard(|| {
            self.with(|state| {
                let entries = state.db.entries(playlist)?;
                if !entries.iter().any(|e| e.id == entry) {
                    return Err(CoreError::unavailable("core: no such entry in the playlist"));
                }
                let position = position_for(&entries, index as usize, Some(entry));
                plinth_sync::journal::record_and_project(
                    &mut state.journal,
                    &state.db,
                    &Op::MoveEntry { entry, position },
                )
            })
        })
    }

    /// Плейлист `name` из файла M3U, M3U8 или PLS (D4c). `content` — байты
    /// файла, кодировку ядро узнаёт само; `folder` — папка файла, от неё
    /// считаются относительные пути. Строки ищутся среди видимых треков:
    /// абсолютный путь, путь от папки, «исполнитель - название»; ненайденные
    /// пропускаются и считаются. Плейлист и записи ложатся в журнал одной
    /// правкой; не нашлось ничего — плейлиста нет.
    pub fn import_playlist(
        &self,
        name: String,
        content: Vec<u8>,
        folder: Option<String>,
    ) -> Result<PlaylistImport, CoreError> {
        panic::guard(|| {
            let lines = read(&content);
            self.with(|state| {
                let rows = state.db.track_list(TrackSort::Title, None)?;
                let matched = match_lines(&lines, &rows, folder.as_deref());
                let added = u32::try_from(matched.tracks.len()).unwrap_or(u32::MAX);
                if matched.tracks.is_empty() {
                    return Ok(PlaylistImport { playlist: None, added, not_found: matched.not_found });
                }
                let now = Timestamp::now();
                let playlist = Playlist { id: PlaylistId::new(), name, kind: PlaylistKind::Manual, created_at: now };
                let mut ops = vec![Op::CreatePlaylist(playlist.clone())];
                let mut previous: Option<Position> = None;
                for track in matched.tracks {
                    let position = previous.as_ref().map_or_else(Position::first, Position::after);
                    previous = Some(position.clone());
                    let entry = PlaylistEntry {
                        id: PlaylistEntryId::new(),
                        playlist: playlist.id,
                        track,
                        position,
                        added_at: now,
                    };
                    ops.push(Op::AddEntry(entry));
                }
                record_and_project_all(&mut state.journal, &state.db, &ops)?;
                Ok(PlaylistImport { playlist: Some(playlist), added, not_found: matched.not_found })
            })
        })
    }

    pub fn remove_from_playlist(&self, entry: PlaylistEntryId) -> Result<(), CoreError> {
        self.record(&Op::RemoveEntry { entry })
    }

    /// Записывает прослушивание; засчитать ли его в счётчик, решает ядро
    /// (правило Last.fm, `PlayEvent::counts`).
    pub fn record_play(&self, play: NewPlay) -> Result<PlayEventId, CoreError> {
        let event = play.into_event();
        self.record(&Op::Play(event))?;
        Ok(event.id)
    }

    /// Последние прослушивания всей библиотеки, новые первыми.
    pub fn recent_plays(&self, limit: u32) -> Result<Vec<PlayEvent>, CoreError> {
        panic::guard(|| self.with(|state| state.db.recent_plays(limit)))
    }

    pub fn synced_settings(&self) -> Result<SyncedSettings, CoreError> {
        panic::guard(|| self.with(|state| state.db.synced_settings()))
    }

    pub fn set_version_preference(&self, preference: VersionPreference) -> Result<(), CoreError> {
        self.record(&Op::Set(Setting::VersionPreference(preference)))
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use plinth_library::model::{OutputDevice, Rating, VersionPreference};
    use plinth_types::{CoreError, PlaylistEntryId, Timestamp, TrackId};

    use crate::session::Core;
    use crate::testing::Scratch;
    use crate::types::NewPlay;

    fn core(dir: &Scratch) -> std::sync::Arc<Core> {
        Core::open(dir.path()).unwrap()
    }

    fn tracks_of(core: &Core, playlist: plinth_types::PlaylistId) -> Vec<TrackId> {
        core.playlist_items(playlist).unwrap().into_iter().map(|item| item.track).collect()
    }

    fn listen(track: TrackId, listened_s: u64) -> NewPlay {
        NewPlay {
            track,
            version: None,
            source: None,
            started_at: Timestamp::from_millis(1_790_307_000_000),
            utc_offset_minutes: 180,
            listened: Duration::from_secs(listened_s),
            track_length: Some(Duration::from_secs(240)),
            skipped_at: None,
            output: OutputDevice::Headphones,
            previous_track: None,
        }
    }

    fn file(uri: &str, artist: Option<&str>, title: &str) -> crate::api::test_api::TestFile {
        crate::api::test_api::TestFile {
            uri: uri.to_owned(),
            folder: "Music/".to_owned(),
            title: Some(title.to_owned()),
            artist: artist.map(str::to_owned),
            album: None,
            album_artist: None,
            disc: None,
            number: None,
            duration_ms: None,
        }
    }

    const QUEEN: &str = "/storage/emulated/0/Music/Queen/Bohemian Rhapsody.mp3";
    const KINO: &str = "/storage/emulated/0/Music/Кино/Кукушка.mp3";
    const INTRO: &str = "/storage/emulated/0/Music/intro.mp3";

    /// Queen и Кино с исполнителями, «Intro» — без.
    fn seeded(dir: &Scratch) -> std::sync::Arc<Core> {
        let core = core(dir);
        core.seed_for_test(vec![
            file(QUEEN, Some("Queen"), "Bohemian Rhapsody"),
            file(KINO, Some("Кино"), "Кукушка"),
            file(INTRO, None, "Intro"),
        ])
        .unwrap();
        core
    }

    fn at(core: &Core, path: &str) -> TrackId {
        core.track_at(path.to_owned()).unwrap().unwrap()
    }

    /// Строки файла находятся по пути, по пути от папки и по названию;
    /// ненайденные пропускаются и считаются.
    #[test]
    fn a_playlist_file_becomes_a_playlist_of_what_is_found() {
        let dir = Scratch::new();
        let core = seeded(&dir);
        let text = format!(
            "#EXTM3U\r\n{QUEEN}\r\nКино/Кукушка.mp3\r\n#EXTINF:5,Nobody - Nothing\r\n/gone.mp3\r\n\
             #EXTINF:5,Intro\r\nhttp://radio/intro\r\n{QUEEN}\r\n"
        );

        let report = core
            .import_playlist("Road".to_owned(), text.into_bytes(), Some("/storage/emulated/0/Music".to_owned()))
            .unwrap();

        let playlist = report.playlist.unwrap();
        assert_eq!((playlist.name.as_str(), report.added, report.not_found), ("Road", 4, 1));
        assert_eq!(
            tracks_of(&core, playlist.id),
            [at(&core, QUEEN), at(&core, KINO), at(&core, INTRO), at(&core, QUEEN)]
        );
        assert_eq!(core.playlists().unwrap(), [playlist]);
    }

    /// Не нашлось ни одной строки — пустого плейлиста не будет; пропавший файл не находится.
    #[test]
    fn nothing_found_makes_no_playlist() {
        let dir = Scratch::new();
        let core = seeded(&dir);
        core.hide_for_test(vec![KINO.to_owned()]).unwrap();

        let report = core
            .import_playlist(
                "Gone".to_owned(),
                format!(
                    "{KINO}
/nowhere.mp3
"
                )
                .into_bytes(),
                None,
            )
            .unwrap();

        assert_eq!((report.playlist, report.added, report.not_found), (None, 0, 2));
        assert!(core.playlists().unwrap().is_empty());
    }

    #[test]
    fn like_and_rate_reach_user_data() {
        let dir = Scratch::new();
        let core = core(&dir);
        let track = TrackId::new();

        core.like(track).unwrap();
        core.rate(track, Some(Rating::new(4).unwrap())).unwrap();
        let rated = core.user_data(track).unwrap();
        core.unlike(track).unwrap();
        core.rate(track, None).unwrap();

        assert!(rated.liked);
        assert_eq!(rated.rating.map(Rating::stars), Some(4));
        assert_eq!(core.user_data(track).unwrap(), plinth_library::model::TrackUserData::empty(track));
    }

    /// Kotlin работает с индексами, позиции считает ядро.
    #[test]
    fn playlist_entries_follow_indexes() {
        let dir = Scratch::new();
        let core = core(&dir);
        let (a, b, c, d) = (TrackId::new(), TrackId::new(), TrackId::new(), TrackId::new());
        let mix = core.create_playlist("Mix".to_owned()).unwrap();

        for track in [a, b, c] {
            core.add_to_playlist(mix.id, track, None).unwrap();
        }
        core.add_to_playlist(mix.id, d, Some(0)).unwrap();
        assert_eq!(tracks_of(&core, mix.id), vec![d, a, b, c]);

        let moved = core.playlist_items(mix.id).unwrap()[0].id;
        core.move_in_playlist(mix.id, moved, 3).unwrap();
        assert_eq!(tracks_of(&core, mix.id), vec![a, b, c, d]);

        let second = core.playlist_items(mix.id).unwrap()[1].id;
        core.remove_from_playlist(second).unwrap();
        assert_eq!(tracks_of(&core, mix.id), vec![a, c, d]);
    }

    #[test]
    fn playlists_are_created_renamed_and_deleted() {
        let dir = Scratch::new();
        let core = core(&dir);

        let mix = core.create_playlist("Mix".to_owned()).unwrap();
        core.rename_playlist(mix.id, "Road".to_owned()).unwrap();
        let listed = core.playlists().unwrap();
        core.delete_playlist(mix.id).unwrap();

        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].name, "Road");
        assert_eq!(listed[0].created_at, mix.created_at);
        assert!(core.playlists().unwrap().is_empty());
    }

    #[test]
    fn a_missing_playlist_or_entry_is_unavailable() {
        let dir = Scratch::new();
        let core = core(&dir);
        let mix = core.create_playlist("Mix".to_owned()).unwrap();
        let ghost = core.create_playlist("Ghost".to_owned()).unwrap();
        core.delete_playlist(ghost.id).unwrap();

        let results = [
            core.add_to_playlist(ghost.id, TrackId::new(), None).map(drop),
            core.move_in_playlist(mix.id, PlaylistEntryId::new(), 0),
            core.rename_playlist(ghost.id, "New".to_owned()),
        ];

        for result in results {
            assert!(matches!(result, Err(CoreError::Unavailable { .. })), "{result:?}");
        }
    }

    #[test]
    fn plays_are_history_and_count_by_the_rule() {
        let dir = Scratch::new();
        let core = core(&dir);
        let track = TrackId::new();

        let counted = core.record_play(listen(track, 200)).unwrap();
        let skipped = core.record_play(listen(track, 10)).unwrap();

        let history: Vec<_> = core.recent_plays(10).unwrap().into_iter().map(|play| play.id).collect();
        assert_eq!(history.len(), 2);
        assert!(history.contains(&counted) && history.contains(&skipped));
        assert_eq!(core.user_data(track).unwrap().play_count, 1);
    }

    #[test]
    fn version_preference_is_a_synced_setting() {
        let dir = Scratch::new();
        let core = core(&dir);

        core.set_version_preference(VersionPreference::Clean).unwrap();

        assert_eq!(core.synced_settings().unwrap().version_preference, VersionPreference::Clean);
    }
}
