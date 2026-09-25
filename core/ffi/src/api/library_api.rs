//! Чтение библиотеки (A3.1): списки экранов одним вызовом, строки готовы к
//! показу. Каталог в базе появится со сканером (D1); запросы экранов под
//! замену Room — альбомы, исполнители, естественная сортировка — D3.

use plinth_library::db::query::{TrackRow, TrackSort};
use plinth_library::model::TrackUserData;
use plinth_types::{AlbumId, CoreError, TrackId};

use crate::panic;
use crate::session::Core;

#[uniffi::export]
impl Core {
    /// Все треки в порядке `sort`; `search` — по названию и исполнителю, без
    /// учёта регистра, диакритики и знаков.
    pub fn tracks(&self, sort: TrackSort, search: Option<String>) -> Result<Vec<TrackRow>, CoreError> {
        panic::guard(|| self.with(|state| state.db.track_list(sort, search.as_deref())))
    }

    /// Треки альбома по дискам и номерам.
    pub fn album_tracks(&self, album: AlbumId) -> Result<Vec<TrackRow>, CoreError> {
        panic::guard(|| self.with(|state| state.db.album_tracks(album)))
    }

    /// Лайк, оценка и счётчики трека; не слушали и не оценивали — пустые.
    pub fn user_data(&self, track: TrackId) -> Result<TrackUserData, CoreError> {
        panic::guard(|| self.with(|state| state.db.user_data(track)))
    }
}

#[cfg(test)]
mod tests {
    use plinth_library::db::query::TrackSort;
    use plinth_library::model::Track;
    use plinth_types::{Timestamp, TrackId};

    use crate::session::Core;
    use crate::testing::Scratch;

    fn track(title: &str) -> Track {
        Track {
            id: TrackId::new(),
            title: title.to_owned(),
            artist_credit: "Radiohead".to_owned(),
            artists: Vec::new(),
            mbid_work: None,
            added_at: Timestamp::from_millis(1),
        }
    }

    #[test]
    fn a_fresh_library_is_empty() {
        let dir = Scratch::new();
        let core = Core::open(dir.path()).unwrap();

        assert!(core.tracks(TrackSort::Title, None).unwrap().is_empty());
    }

    /// Строка списка несёт и каталог, и пользовательское из журнала.
    #[test]
    fn track_rows_show_likes_and_follow_search() {
        let dir = Scratch::new();
        let core = Core::open(dir.path()).unwrap();
        let (creep, karma) = (track("Creep"), track("Karma Police"));
        core.with(|state| {
            state.db.save_track(&creep)?;
            state.db.save_track(&karma)
        })
        .unwrap();

        core.like(creep.id).unwrap();

        let rows = core.tracks(TrackSort::Title, None).unwrap();
        assert_eq!(
            rows.iter().map(|r| (r.title.as_str(), r.liked)).collect::<Vec<_>>(),
            [("Creep", true), ("Karma Police", false)]
        );
        let found = core.tracks(TrackSort::Title, Some("karma".to_owned())).unwrap();
        assert_eq!(found.iter().map(|r| r.id).collect::<Vec<_>>(), [karma.id]);
    }
}
