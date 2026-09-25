//! C3: проекция журнала в базу. Инкрементальная — операция за операцией —
//! даёт ту же базу, что пересборка, и ту же, что прямые записи в репозитории.

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

mod common;

use common::{Scratch, Workload, dump, memory_db, play, playlist};
use plinth_library::model::{
    BlockEntry, BlockTarget, DecidedBy, MergeDecision, PlaylistEntry, Rating, Setting, Subscription, TrackPair,
    TrackUserData, Verdict, VersionPreference, position_for,
};
use plinth_sync::journal::{CatchUp, Journal, Op, catch_up, rebuild, record_and_project};
use plinth_types::{ArtistId, DeviceId, MergeDecisionId, PlaylistEntryId, Timestamp, TrackId};

/// Главное свойство C3: как бы база ни доходила до состояния журнала —
/// операциями по одной или пересборкой, — она одна и та же.
#[test]
fn incremental_projection_equals_rebuild() {
    for seed in [1, 7, 42] {
        let dir = Scratch::new();
        let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
        let live = memory_db();
        let mut workload = Workload::new(seed);

        for _ in 0..250 {
            let op = workload.op(&journal);
            record_and_project(&mut journal, &live, &op).unwrap();
        }
        let rebuilt = memory_db();
        rebuild(&journal, &rebuilt).unwrap();

        assert_eq!(dump(&live), dump(&rebuilt), "seed {seed}");
        assert_eq!(live.journal_mark().unwrap(), Some(journal.mark()));
        assert_eq!(rebuilt.journal_mark().unwrap(), Some(journal.mark()));
        assert!(!dump(&live).plays.is_empty(), "seed {seed}: the workload must play something");
    }
}

/// Проекция — те же строки, что прямые записи в репозитории (B2).
#[test]
fn projection_matches_direct_writes() {
    let dir = Scratch::new();
    let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
    let projected = memory_db();
    let direct = memory_db();
    let (liked, rated) = (TrackId::new(), TrackId::new());
    let artist = ArtistId::new();
    let mut mix = playlist("Mix");
    let first = PlaylistEntry {
        id: PlaylistEntryId::new(),
        playlist: mix.id,
        track: liked,
        position: position_for(&[], 0, None),
        added_at: Timestamp::from_millis(10),
    };
    let second = PlaylistEntry {
        id: PlaylistEntryId::new(),
        track: rated,
        position: position_for(std::slice::from_ref(&first), 1, None),
        ..first.clone()
    };
    let moved = position_for(&[first.clone(), second.clone()], 0, Some(second.id));
    let heard = play(liked, 5_000, 200, Some(240));
    let decision = MergeDecision {
        id: MergeDecisionId::new(),
        pair: TrackPair::new(liked, rated).unwrap(),
        verdict: Verdict::Split,
        by: DecidedBy::User,
        decided_at: Timestamp::from_millis(6),
    };
    let subscription = Subscription { artist, since: Timestamp::from_millis(7) };
    let blocked = BlockEntry { target: BlockTarget::Artist(artist), since: Timestamp::from_millis(8) };
    let setting = Setting::VersionPreference(VersionPreference::Clean);

    for op in [
        Op::Like { track: liked },
        Op::Rate { track: rated, rating: Some(Rating::new(4).unwrap()) },
        Op::CreatePlaylist(mix.clone()),
        Op::AddEntry(first.clone()),
        Op::AddEntry(second.clone()),
        Op::MoveEntry { entry: second.id, position: moved.clone() },
        Op::RenamePlaylist { playlist: mix.id, name: "Road".to_owned() },
        Op::Play(heard),
        Op::Decide(decision),
        Op::Subscribe(subscription),
        Op::Block(blocked),
        Op::Set(setting),
    ] {
        record_and_project(&mut journal, &projected, &op).unwrap();
    }

    direct
        .save_user_data(&TrackUserData {
            liked: true,
            play_count: 1,
            last_played_at: Some(heard.started_at),
            ..TrackUserData::empty(liked)
        })
        .unwrap();
    direct
        .save_user_data(&TrackUserData { rating: Some(Rating::new(4).unwrap()), ..TrackUserData::empty(rated) })
        .unwrap();
    mix.name = "Road".to_owned();
    direct.save_playlist(&mix).unwrap();
    direct.put_entry(&first).unwrap();
    direct.put_entry(&PlaylistEntry { position: moved, ..second }).unwrap();
    direct.record_play(&heard).unwrap();
    direct.save_merge_decision(&decision).unwrap();
    direct.subscribe(&subscription).unwrap();
    direct.block(&blocked).unwrap();
    direct.save_setting(setting).unwrap();

    assert_eq!(dump(&projected), dump(&direct));
}

/// Правило Last.fm: в счётчик и «последнее прослушивание» — только
/// засчитанные; в истории — все.
#[test]
fn only_counted_plays_reach_the_counter() {
    let dir = Scratch::new();
    let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
    let db = memory_db();
    let track = TrackId::new();

    for heard in [play(track, 1_000, 130, Some(240)), play(track, 9_000, 30, Some(240)), play(track, 5_000, 300, None)]
    {
        record_and_project(&mut journal, &db, &Op::Play(heard)).unwrap();
    }

    let data = db.user_data(track).unwrap();
    assert_eq!(data.play_count, 2);
    assert_eq!(data.last_played_at, Some(Timestamp::from_millis(5_000)));
    assert_eq!(db.plays_of(track, 10).unwrap().len(), 3);
}

/// Операция легла в журнал, а до базы не дошла (сбой между ними): следующая
/// операция сначала пересобирает базу — ничего не теряется.
#[test]
fn a_projection_left_behind_is_rebuilt_before_the_next_op() {
    let dir = Scratch::new();
    let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
    let db = memory_db();
    let (missed, next) = (TrackId::new(), TrackId::new());
    record_and_project(&mut journal, &db, &Op::Like { track: TrackId::new() }).unwrap();

    journal.record(&Op::Like { track: missed }).unwrap();
    record_and_project(&mut journal, &db, &Op::Like { track: next }).unwrap();

    assert!(db.user_data(missed).unwrap().liked);
    assert!(db.user_data(next).unwrap().liked);
    assert_eq!(db.journal_mark().unwrap(), Some(journal.mark()));
}

#[test]
fn catch_up_rebuilds_only_when_behind() {
    let dir = Scratch::new();
    let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
    let db = memory_db();
    let track = TrackId::new();

    assert_eq!(catch_up(&journal, &db).unwrap(), CatchUp::Rebuilt);
    record_and_project(&mut journal, &db, &Op::Like { track }).unwrap();
    assert_eq!(catch_up(&journal, &db).unwrap(), CatchUp::UpToDate);

    journal.record(&Op::Unlike { track }).unwrap();
    assert_eq!(catch_up(&journal, &db).unwrap(), CatchUp::Rebuilt);
    assert!(!db.user_data(track).unwrap().liked);
}

/// Операция без последствий не трогает ни журнал, ни базу.
#[test]
fn a_no_op_leaves_the_mark_alone() {
    let dir = Scratch::new();
    let mut journal = Journal::open(&dir.journal(), DeviceId::new()).unwrap();
    let db = memory_db();
    let track = TrackId::new();
    record_and_project(&mut journal, &db, &Op::Like { track }).unwrap();
    let mark = journal.mark();

    record_and_project(&mut journal, &db, &Op::Like { track }).unwrap();

    assert_eq!(journal.mark(), mark);
    assert_eq!(db.journal_mark().unwrap(), Some(mark));
}
