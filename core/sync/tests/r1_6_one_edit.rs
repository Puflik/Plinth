//! Р1.6, ревью круг 1 (`docs/work/r1-6.md`): стражи ускорения записи регистров.
//! Одна правка видит слоты, которые сама записала или стёрла; действие над
//! многими треками сравнивает каталог с действующим паспортом каждого трека.
//! На коде до ускорения они зелёные: ловят индекс слотов, отставший от правки,
//! и поиск паспортов пачкой, который берёт не тот слот.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

mod common;
mod r1_6_registers;

use std::time::Duration;

use plinth_library::db::Database;
use plinth_library::model::{AudioSpec, OnlineSource, OnlineTrack, PlaylistEntry, TrackPassport};
use plinth_sync::journal::{Op, record_and_project_all};
use plinth_types::{DeviceId, Format, PlaylistEntryId, ProviderId, Timestamp};
use r1_6_registers::{Replica, e1, entry, p1, playlist, position, rating, t1, t2};

/// Запись, заведённая правкой, видна дальше в той же правке: её двигают и стирают.
#[test]
fn an_entry_added_in_an_edit_can_be_moved_and_removed_in_it() {
    let mut replica = Replica::new();
    let second = PlaylistEntry { id: PlaylistEntryId::new(), ..entry(position(0)) };
    replica
        .journal
        .record_all(&[
            Op::CreatePlaylist(playlist("Road")),
            Op::AddEntry(entry(position(0))),
            Op::MoveEntry { entry: e1(), position: position(2) },
            Op::AddEntry(second.clone()),
            Op::RemoveEntry { entry: second.id },
        ])
        .unwrap();

    let entries = replica.journal.state().entries;
    assert_eq!(entries.len(), 1, "вторую запись стёрла та же правка");
    assert_eq!((entries[0].id, entries[0].position.clone()), (e1(), position(2)), "запись сдвинута той же правкой");
}

/// Плейлист, заведённый правкой, переименовывают в ней же.
#[test]
fn a_playlist_created_in_an_edit_can_be_renamed_in_it() {
    let mut replica = Replica::new();
    replica
        .journal
        .record_all(&[
            Op::CreatePlaylist(playlist("Road")),
            Op::RenamePlaylist { playlist: p1(), name: "Home".to_owned() },
        ])
        .unwrap();

    assert_eq!(replica.journal.state().playlists.iter().map(|p| p.name.as_str()).collect::<Vec<_>>(), ["Home"]);
}

/// Оценку, поставленную правкой, она же снимает; поставленную дважды — видно последнюю.
#[test]
fn a_rating_set_in_an_edit_is_seen_by_the_rest_of_it() {
    let mut replica = Replica::new();
    replica
        .journal
        .record_all(&[
            Op::Rate { track: t1(), rating: rating(5) },
            Op::Rate { track: t1(), rating: None },
            Op::Rate { track: t2(), rating: rating(2) },
            Op::Rate { track: t2(), rating: rating(4) },
        ])
        .unwrap();

    assert_eq!(replica.journal.state().ratings, vec![(t2(), rating(4).unwrap())]);
}

/// Снятие в правке стирает и слот, влитый до неё; оценка за ним в той же правке
/// остаётся единственной — у обеих установок.
#[test]
fn a_removal_in_an_edit_erases_a_slot_merged_before_it() {
    let (mut a, mut b) = (Replica::new(), Replica::new());
    a.journal.record_all(&[Op::Rate { track: t1(), rating: rating(5) }]).unwrap();
    b.take_from(&a);

    b.journal
        .record_all(&[Op::Rate { track: t1(), rating: None }, Op::Rate { track: t1(), rating: rating(2) }])
        .unwrap();
    a.take_from(&b);

    for replica in [&a, &b] {
        assert_eq!(replica.journal.state().ratings, vec![(t1(), rating(2).unwrap())]);
    }
}

fn online(i: usize) -> OnlineTrack {
    OnlineTrack {
        title: format!("Title {i}"),
        artist: Some("Band".to_owned()),
        album: Some("Debut".to_owned()),
        number: Some(1),
        year: None,
        duration: Some(Duration::from_secs(180 + i as u64)),
        mbid: None,
        sources: vec![OnlineSource {
            provider: ProviderId::new("archive.org").unwrap(),
            external_id: format!("item{i}/a.mp3"),
            audio: AudioSpec { format: Format::Mp3, bitrate: None, sample_rate_hz: None, bit_depth: None },
        }],
    }
}

/// У трека два слота паспорта: каталожный «Title 0» установки A и более поздний
/// «New» установки B. Каталог снова говорит «Title 0» — действие над пачкой
/// треков на B описывает трек заново. Установка A больше B: выбор по ключу или
/// по установке взял бы её слот, совпал бы с каталогом и ничего не записал.
#[test]
fn a_batch_action_compares_each_track_with_its_newest_passport() {
    let db = Database::open_in_memory().unwrap();
    let tracks: Vec<_> = (0..4).map(|i| db.add_online_track(&online(i), Timestamp::from_millis(1)).unwrap()).collect();
    let catalog = db.track_passport(tracks[0]).unwrap().unwrap();
    let renamed = TrackPassport { title: "New".to_owned(), ..catalog.clone() };

    let mut a = Replica::with_device("eeeeeeee-eeee-7eee-8eee-eeeeeeeeeeee".parse::<DeviceId>().unwrap());
    let mut b = Replica::with_device("01010101-0101-7101-8101-010101010101".parse::<DeviceId>().unwrap());
    a.journal.record_all(&[Op::Describe(catalog.clone())]).unwrap();
    b.take_from(&a);
    b.journal.record_all(&[Op::Describe(renamed.clone())]).unwrap();
    assert_eq!(b.journal.passport(tracks[0]), Some(renamed), "у B действует его более поздний слот");

    let likes: Vec<Op> = tracks.iter().map(|track| Op::Like { track: *track }).collect();
    record_and_project_all(&mut b.journal, &db, &likes).unwrap();

    assert_eq!(
        b.journal.passport(tracks[0]),
        Some(catalog),
        "каталог разошёлся с действующим паспортом — описан заново"
    );
    for track in &tracks[1..] {
        assert_eq!(b.journal.passport(*track), db.track_passport(*track).unwrap(), "паспорта у новых треков");
    }
}
