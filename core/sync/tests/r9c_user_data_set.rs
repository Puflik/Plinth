//! Р9в (`docs/work/r9c.md`): `Database::tracks_with_user_data` — какие треки
//! каталога несут данные пользователя. Скан по ней решает, чей трек отдавать
//! файлу и какой трек уходит из каталога, поэтому набор обязан совпадать с тем,
//! на что ссылается журнал (`referenced`, `core/sync/src/journal/passport.rs`):
//! лайк, оценка, запись плейлиста, прослушивание, решение о склейке, блокировка
//! трека. Лайк, который сняли, данных не оставляет.
//!
//! Файл не собирается, пока метода нет: сигнатура — в разделе «Решение» файла задачи.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;

use std::collections::HashSet;

use common::{Scratch, Song, memory_db, play, playlist, scan, track_at};
use plinth_library::model::{
    BlockEntry, BlockTarget, DecidedBy, MergeDecision, PlaylistEntry, Rating, TrackPair, Verdict,
};
use plinth_sync::journal::{Journal, Op, record_and_project};
use plinth_types::{DeviceId, MergeDecisionId, PlaylistEntryId, Position, Timestamp, TrackId};

const fn song(path: &'static str, title: &'static str) -> Song {
    Song { path, title, artist: "Band", album: Some("Album"), duration_ms: Some(180_000) }
}

#[test]
fn tracks_with_user_data_are_the_tracks_the_journal_refers_to() {
    let songs = [
        song("/m/liked.mp3", "Liked"),
        song("/m/rated.mp3", "Rated"),
        song("/m/listed.mp3", "Listed"),
        song("/m/played.mp3", "Played"),
        song("/m/merged-a.mp3", "Merged A"),
        song("/m/merged-b.mp3", "Merged B"),
        song("/m/blocked.mp3", "Blocked"),
        song("/m/unliked.mp3", "Unliked"),
        song("/m/bare.mp3", "Bare"),
    ];
    let db = memory_db();
    scan(&db, &songs);
    let id = |index: usize| -> TrackId { track_at(&db, songs[index]) };
    let dir = Scratch::new();
    let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
    let road = playlist("Road");
    let pair = TrackPair::new(id(4), id(5)).unwrap();
    for op in [
        Op::Like { track: id(0) },
        Op::Rate { track: id(1), rating: Some(Rating::new(3).unwrap()) },
        Op::CreatePlaylist(road.clone()),
        Op::AddEntry(PlaylistEntry {
            id: PlaylistEntryId::new(),
            playlist: road.id,
            track: id(2),
            position: Position::first(),
            added_at: Timestamp::now(),
        }),
        Op::Play(play(id(3), 1_790_000_000_000, 150, Some(180))),
        Op::Decide(MergeDecision {
            id: MergeDecisionId::new(),
            pair,
            verdict: Verdict::Merge,
            by: DecidedBy::User,
            decided_at: Timestamp::now(),
        }),
        Op::Block(BlockEntry { target: BlockTarget::Track(id(6)), since: Timestamp::now() }),
        Op::Like { track: id(7) },
        Op::Unlike { track: id(7) },
    ] {
        record_and_project(&mut journal, &db, &op).unwrap();
    }

    let with_data = db.tracks_with_user_data().unwrap();

    let expected: HashSet<TrackId> = (0..7).map(id).collect();
    assert_eq!(with_data, expected, "лайк, оценка, плейлист, прослушивание, обе стороны склейки, блокировка");
    assert!(!with_data.contains(&id(7)), "снятый лайк данных не оставляет");
    assert!(!with_data.contains(&id(8)));
}
