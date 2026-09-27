//! Копия журнала в папке человека (C4): осмотр найденных копий и
//! восстановление слиянием — после переустановки или со второй установки.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;

use common::{Scratch, Song, memory_db, play, playlist, scan, track_at};
use plinth_library::db::Database;
use plinth_sync::journal::{Journal, Op, record_and_project};
use plinth_sync::mirror::{Found, Restored, copy_of, inspect, restore};
use plinth_types::{DeviceId, Timestamp, TrackId};

const KINO: Song = Song {
    path: "/storage/emulated/0/Music/Кино/Кукушка.mp3",
    title: "Кукушка",
    artist: "Кино",
    album: None,
    duration_ms: Some(398_000),
};

const AT: Timestamp = Timestamp::from_millis(1_790_307_000_000);

/// Установка: папка журнала и база в памяти.
struct Installation {
    _dir: Scratch,
    journal: Journal,
    db: Database,
}

impl Installation {
    fn new() -> Self {
        let dir = Scratch::new();
        let journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
        Self { _dir: dir, journal, db: memory_db() }
    }

    fn record(&mut self, op: &Op) {
        record_and_project(&mut self.journal, &self.db, op).unwrap();
    }

    fn copy(&self) -> Vec<u8> {
        copy_of(&self.journal, AT)
    }

    fn liked(&self) -> Vec<TrackId> {
        self.db.all_user_data().unwrap().into_iter().filter(|data| data.liked).map(|data| data.track).collect()
    }
}

/// Прошлая установка: два лайка, плейлист, три прослушивания.
fn old_installation() -> (Installation, [TrackId; 2]) {
    let mut old = Installation::new();
    let liked = [TrackId::new(), TrackId::new()];
    for track in liked {
        old.record(&Op::Like { track });
    }
    old.record(&Op::CreatePlaylist(playlist("Road")));
    for at in 1..=3 {
        old.record(&Op::Play(play(liked[0], at, 300, None)));
    }
    (old, liked)
}

#[test]
fn a_copy_of_another_installation_is_news_with_its_counts() {
    let (old, _) = old_installation();
    let new = Installation::new();

    let found = inspect(&new.journal, &[&old.copy()]).unwrap();

    assert_eq!(found, Found { likes: 2, playlists: 1, plays: 3, written_at: Some(AT), news: true, unreadable: 0 });
}

#[test]
fn a_copy_of_the_same_state_is_not_news() {
    let (old, _) = old_installation();

    let found = inspect(&old.journal, &[&old.copy()]).unwrap();

    assert!(!found.news);
    assert_eq!(found.likes, 2);
}

/// Слияние, а не замена: сделанное до ответа «Восстановить» не теряется.
#[test]
fn a_restore_merges_and_keeps_what_was_done_before() {
    let (old, liked) = old_installation();
    let mut new = Installation::new();
    let fresh = TrackId::new();
    new.record(&Op::Like { track: fresh });

    let restored = restore(&mut new.journal, &new.db, &[&old.copy()]).unwrap();

    assert_eq!(restored, Restored { merged: 1, unreadable: 0, relinked: 0 });
    let mut expected = vec![liked[0], liked[1], fresh];
    expected.sort();
    let mut got = new.liked();
    got.sort();
    assert_eq!(got, expected);
    assert_eq!(new.db.playlists().unwrap().len(), 1);
    assert_eq!(new.db.recent_plays(10).unwrap().len(), 3);
    assert!(!inspect(&new.journal, &[&old.copy()]).unwrap().news, "восстановленная копия больше не новость");
}

/// Восстановление переживает перезапуск: слитое лежит в журнале на диске.
#[test]
fn a_restore_is_kept_on_disk() {
    let (old, liked) = old_installation();
    let dir = Scratch::new();
    let device = DeviceId::new();
    let mut journal = Journal::open(&dir.journal(), device).unwrap();
    restore(&mut journal, &memory_db(), &[&old.copy()]).unwrap();
    drop(journal);

    let reopened = Journal::open(&dir.journal(), device).unwrap();

    let mut likes = reopened.state().likes;
    likes.sort();
    let mut expected = liked.to_vec();
    expected.sort();
    assert_eq!(likes, expected);
}

/// Снятый потом лайк — тоже новость: слияние переносит и удаления.
#[test]
fn a_later_unlike_is_news_too() {
    let (mut old, liked) = old_installation();
    let mut new = Installation::new();
    restore(&mut new.journal, &new.db, &[&old.copy()]).unwrap();
    old.record(&Op::Unlike { track: liked[1] });

    assert!(inspect(&new.journal, &[&old.copy()]).unwrap().news);
    restore(&mut new.journal, &new.db, &[&old.copy()]).unwrap();

    assert_eq!(new.liked(), [liked[0]]);
}

/// Испорченная копия пропускается и считается, годная рядом — читается.
#[test]
fn a_damaged_copy_is_skipped_and_counted() {
    let (old, _) = old_installation();
    let mut new = Installation::new();
    let mut damaged = old.copy();
    let last = damaged.len() - 1;
    damaged[last] ^= 0xff;
    let copies: [&[u8]; 3] = [&damaged, b"not a copy", &old.copy()];

    let found = inspect(&new.journal, &copies).unwrap();
    let restored = restore(&mut new.journal, &new.db, &copies).unwrap();

    assert_eq!((found.likes, found.unreadable, found.news), (2, 2, true));
    assert_eq!(restored, Restored { merged: 1, unreadable: 2, relinked: 0 });
}

#[test]
fn nothing_readable_is_nothing_found() {
    let new = Installation::new();

    let found = inspect(&new.journal, &[b"junk"]).unwrap();

    assert_eq!(found, Found { likes: 0, playlists: 0, plays: 0, written_at: None, news: false, unreadable: 1 });
}

/// Из двух копий время — самой свежей.
#[test]
fn the_newest_copy_dates_what_was_found() {
    let (old, _) = old_installation();
    let (other, _) = old_installation();
    let new = Installation::new();
    let later = Timestamp::from_millis(AT.as_millis() + 86_400_000);

    let found = inspect(&new.journal, &[&old.copy(), &copy_of(&other.journal, later)]).unwrap();

    assert_eq!((found.likes, found.playlists, found.written_at), (4, 2, Some(later)));
}

/// Каталог уже просканирован заново — восстановление сразу перепривязывает треки.
#[test]
fn a_restore_relinks_the_scanned_catalog() {
    let mut old = Installation::new();
    scan(&old.db, &[KINO]);
    let kino = track_at(&old.db, KINO);
    old.record(&Op::Like { track: kino });
    let mut new = Installation::new();
    scan(&new.db, &[KINO]);

    let restored = restore(&mut new.journal, &new.db, &[&old.copy()]).unwrap();

    assert_eq!(restored.relinked, 1);
    assert_eq!(track_at(&new.db, KINO), kino);
    assert_eq!(new.db.liked_tracks().unwrap().len(), 1);
}
