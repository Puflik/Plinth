//! Раскладка документа `yrs` (C2.2): где какая запись лежит и что с ней
//! делает операция.
//!
//! Корни документа — только плоские `map` и `array` с именем. Вложенный
//! `map` на сущность («трек → { лайк, оценка }») нельзя: созданный
//! параллельно на двух устройствах, один из двух затрёт другой целиком.
//!
//! - `likes`, `subscriptions`, `blocks` — множества: ключ есть — элемент есть.
//!   Добавление побеждает параллельное удаление: удаление стирает только ту
//!   запись, которую видело.
//! - `ratings`, `playlists`, `entries`, `settings` — регистр на ключ:
//!   действует последняя запись. Параллельные записи одного ключа `yrs`
//!   разводит одинаково на всех устройствах.
//! - `plays`, `decisions` — только дописываются: прослушивания и решения
//!   о склейке неизменны, их порядок в массиве ничего не значит.
//!
//! Значение — запись `codec.rs`: заголовок операции и поля сущности.

use std::sync::Arc;

use plinth_library::model::{
    BlockEntry, BlockTarget, MergeDecision, PlayEvent, Playlist, PlaylistEntry, Rating, Setting, Subscription,
};
use plinth_types::{CoreError, PlaylistEntryId, PlaylistId, TrackId};
use yrs::{Any, Array, ArrayRef, Doc, Map, MapRef, Out, ReadTxn, TransactionMut};

use super::codec::{Reader, decode, encode};
use super::op::Op;
use super::op_meta::OpMeta;

/// Именованные корни документа — формат журнала, не переименовываются.
pub(crate) struct Roots {
    likes: MapRef,
    ratings: MapRef,
    playlists: MapRef,
    entries: MapRef,
    plays: ArrayRef,
    decisions: ArrayRef,
    subscriptions: MapRef,
    blocks: MapRef,
    settings: MapRef,
}

/// Всё, что лежит в журнале, — то, из чего собирается проекция (C3).
/// Порядок одинаков на всех устройствах с одним и тем же состоянием.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct JournalState {
    pub likes: Vec<TrackId>,
    pub ratings: Vec<(TrackId, Rating)>,
    pub playlists: Vec<Playlist>,
    pub entries: Vec<PlaylistEntry>,
    pub plays: Vec<PlayEvent>,
    pub decisions: Vec<MergeDecision>,
    pub subscriptions: Vec<Subscription>,
    pub blocklist: Vec<BlockEntry>,
    pub settings: Vec<Setting>,
    /// Записи, которые этот код не прочёл: битые или из более новой версии.
    /// В документе они остаются.
    pub unreadable: usize,
}

impl Roots {
    /// Корни создаются до первой транзакции: внутри неё `yrs` их не создаст.
    pub(crate) fn new(doc: &Doc) -> Self {
        Self {
            likes: doc.get_or_insert_map("likes"),
            ratings: doc.get_or_insert_map("ratings"),
            playlists: doc.get_or_insert_map("playlists"),
            entries: doc.get_or_insert_map("entries"),
            plays: doc.get_or_insert_array("plays"),
            decisions: doc.get_or_insert_array("decisions"),
            subscriptions: doc.get_or_insert_map("subscriptions"),
            blocks: doc.get_or_insert_map("blocks"),
            settings: doc.get_or_insert_map("settings"),
        }
    }

    /// Кладёт операцию в документ; `false` — она ничего не меняет. Сначала
    /// проверка, потом правка: у транзакции `yrs` нет отката.
    pub(crate) fn write(&self, txn: &mut TransactionMut, op: &Op, meta: &OpMeta) -> Result<bool, CoreError> {
        let changed = match op {
            Op::Like { track } => add(&self.likes, txn, track.to_string(), || encode(meta, |_| ())),
            Op::Unlike { track } => self.likes.remove(txn, &track.to_string()).is_some(),
            Op::Rate { track, rating: Some(rating) } => {
                let key = track.to_string();
                let unchanged = read(&self.ratings, txn, &key, |r| r.rating()) == Some(*rating);
                !unchanged && put(&self.ratings, txn, key, encode(meta, |w| w.rating(*rating)))
            }
            Op::Rate { track, rating: None } => self.ratings.remove(txn, &track.to_string()).is_some(),
            Op::CreatePlaylist(playlist) => {
                add(&self.playlists, txn, playlist.id.to_string(), || encode(meta, |w| w.playlist(playlist)))
            }
            Op::RenamePlaylist { playlist, name } => {
                let key = playlist.to_string();
                let current =
                    read(&self.playlists, txn, &key, |r| r.playlist(*playlist)).ok_or_else(|| missing("playlist"))?;
                let renamed = Playlist { name: name.clone(), ..current.clone() };
                renamed != current && put(&self.playlists, txn, key, encode(meta, |w| w.playlist(&renamed)))
            }
            Op::DeletePlaylist { playlist } => self.delete_playlist(txn, *playlist),
            Op::AddEntry(entry) => {
                if !self.playlists.contains_key(txn, &entry.playlist.to_string()) {
                    return Err(missing("playlist"));
                }
                add(&self.entries, txn, entry.id.to_string(), || encode(meta, |w| w.entry(entry)))
            }
            Op::MoveEntry { entry, position } => {
                let key = entry.to_string();
                let current = read(&self.entries, txn, &key, |r| r.entry(*entry)).ok_or_else(|| missing("entry"))?;
                let moved = PlaylistEntry { position: position.clone(), ..current.clone() };
                moved != current && put(&self.entries, txn, key, encode(meta, |w| w.entry(&moved)))
            }
            Op::RemoveEntry { entry } => self.entries.remove(txn, &entry.to_string()).is_some(),
            Op::Play(play) => {
                self.plays.push_back(txn, buffer(encode(meta, |w| w.play(play))));
                true
            }
            Op::Decide(decision) => {
                self.decisions.push_back(txn, buffer(encode(meta, |w| w.decision(decision))));
                true
            }
            Op::Subscribe(subscription) => add(&self.subscriptions, txn, subscription.artist.to_string(), || {
                encode(meta, |w| w.since(subscription.since))
            }),
            Op::Unsubscribe { artist } => self.subscriptions.remove(txn, &artist.to_string()).is_some(),
            Op::Block(entry) => {
                add(&self.blocks, txn, block_key(entry.target), || encode(meta, |w| w.since(entry.since)))
            }
            Op::Unblock { target } => self.blocks.remove(txn, &block_key(*target)).is_some(),
            Op::Set(setting) => {
                let key = setting_key(*setting);
                let unchanged = read(&self.settings, txn, key, |r| r.setting()) == Some(*setting);
                !unchanged && put(&self.settings, txn, key.to_owned(), encode(meta, |w| w.setting(*setting)))
            }
        };
        Ok(changed)
    }

    /// Плейлист и все его записи.
    fn delete_playlist(&self, txn: &mut TransactionMut, playlist: PlaylistId) -> bool {
        let doomed: Vec<String> = self
            .entries
            .iter(txn)
            .filter(|(key, value)| entry(key, value).is_ok_and(|entry| entry.playlist == playlist))
            .map(|(key, _)| key.to_owned())
            .collect();
        let removed = self.playlists.remove(txn, &playlist.to_string()).is_some();
        for key in &doomed {
            self.entries.remove(txn, key);
        }
        removed || !doomed.is_empty()
    }

    pub(crate) fn state<T: ReadTxn>(&self, txn: &T) -> JournalState {
        let mut state = JournalState::default();
        let mut unreadable = 0;
        let mut skip = |error: CoreError| {
            log::debug!("journal: skipped a record: {error}");
            unreadable += 1;
        };

        state.likes =
            keyed(&self.likes, txn, &mut skip, |key, value| record(value, |_| Ok(())).and_then(|()| key.parse()));
        state.ratings =
            keyed(&self.ratings, txn, &mut skip, |key, value| Ok((key.parse()?, record(value, |r| r.rating())?)));
        state.playlists = keyed(&self.playlists, txn, &mut skip, |key, value| {
            let id = key.parse()?;
            record(value, |r| r.playlist(id))
        });
        state.entries = keyed(&self.entries, txn, &mut skip, entry);
        state.plays = listed(&self.plays, txn, &mut skip, |value| record(value, |r| r.play()));
        state.decisions = listed(&self.decisions, txn, &mut skip, |value| record(value, |r| r.decision()));
        state.subscriptions = keyed(&self.subscriptions, txn, &mut skip, |key, value| {
            Ok(Subscription { artist: key.parse()?, since: record(value, |r| r.since())? })
        });
        state.blocklist = keyed(&self.blocks, txn, &mut skip, |key, value| {
            Ok(BlockEntry { target: block_target(key)?, since: record(value, |r| r.since())? })
        });
        state.settings = keyed(&self.settings, txn, &mut skip, |_, value| record(value, |r| r.setting()));
        state.unreadable = unreadable;
        state
    }

    /// Для тестов: сырое значение в `likes`, в обход кодека.
    #[cfg(test)]
    pub(crate) fn inject_raw_like(&self, txn: &mut TransactionMut, key: &str, bytes: Vec<u8>) {
        self.likes.insert(txn, key, buffer(bytes));
    }
}

fn buffer(bytes: Vec<u8>) -> Any {
    Any::Buffer(Arc::from(bytes))
}

fn missing(what: &str) -> CoreError {
    CoreError::unavailable(format!("journal: no such {what}"))
}

/// Добавляет элемент множества; `false` — он уже есть.
fn add(map: &MapRef, txn: &mut TransactionMut, key: String, record: impl FnOnce() -> Vec<u8>) -> bool {
    if map.contains_key(txn, &key) {
        return false;
    }
    put(map, txn, key, record())
}

fn put(map: &MapRef, txn: &mut TransactionMut, key: String, record: Vec<u8>) -> bool {
    map.insert(txn, key, buffer(record));
    true
}

/// Запись по ключу; нет её или она не читается — `None`.
fn read<T, R: ReadTxn>(
    map: &MapRef,
    txn: &R,
    key: &str,
    payload: impl FnOnce(&mut Reader<'_>) -> Result<T, CoreError>,
) -> Option<T> {
    map.get(txn, key).and_then(|value| record(&value, payload).ok())
}

fn record<T>(value: &Out, payload: impl FnOnce(&mut Reader<'_>) -> Result<T, CoreError>) -> Result<T, CoreError> {
    match value {
        Out::Any(Any::Buffer(bytes)) => decode(bytes, payload).map(|(_, value)| value),
        _ => Err(CoreError::parse("journal record: not a buffer")),
    }
}

/// Записи `map`, упорядоченные по ключу. Ключ, который не разбирается, и
/// нечитаемая запись уходят в `skip`.
fn keyed<T, R: ReadTxn>(
    map: &MapRef,
    txn: &R,
    skip: &mut impl FnMut(CoreError),
    read: impl Fn(&str, &Out) -> Result<T, CoreError>,
) -> Vec<T> {
    let mut entries: Vec<(&str, Out)> = map.iter(txn).collect();
    entries.sort_by(|a, b| a.0.cmp(b.0));
    entries
        .into_iter()
        .filter_map(|(key, value)| match read(key, &value) {
            Ok(item) => Some(item),
            Err(error) => {
                skip(error);
                None
            }
        })
        .collect()
}

fn entry(key: &str, value: &Out) -> Result<PlaylistEntry, CoreError> {
    let id = key.parse::<PlaylistEntryId>()?;
    record(value, |r| r.entry(id))
}

/// Записи массива в его порядке — одинаковом на всех устройствах.
fn listed<T, R: ReadTxn>(
    array: &ArrayRef,
    txn: &R,
    skip: &mut impl FnMut(CoreError),
    read: impl Fn(&Out) -> Result<T, CoreError>,
) -> Vec<T> {
    array
        .iter(txn)
        .filter_map(|value| match read(&value) {
            Ok(item) => Some(item),
            Err(error) => {
                skip(error);
                None
            }
        })
        .collect()
}

/// Ключ чёрного списка: `track:<id>` или `artist:<id>` — трек и артист с
/// одинаковыми байтами идентификатора не смешиваются.
fn block_key(target: BlockTarget) -> String {
    match target {
        BlockTarget::Track(track) => format!("track:{track}"),
        BlockTarget::Artist(artist) => format!("artist:{artist}"),
    }
}

fn block_target(key: &str) -> Result<BlockTarget, CoreError> {
    match key.split_once(':') {
        Some(("track", id)) => Ok(BlockTarget::Track(id.parse()?)),
        Some(("artist", id)) => Ok(BlockTarget::Artist(id.parse()?)),
        _ => Err(CoreError::parse("journal: block key")),
    }
}

/// Ключ настройки в `settings`: у каждой свой, правки разных не спорят.
fn setting_key(setting: Setting) -> &'static str {
    match setting {
        Setting::VersionPreference(_) => "version_preference",
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use plinth_library::model::{
        BlockEntry, BlockTarget, OutputDevice, PlayEvent, Playlist, PlaylistEntry, PlaylistKind, Rating, Setting,
        Subscription, VersionPreference, position_for,
    };
    use plinth_types::{
        ArtistId, CoreError, DeviceId, PlayEventId, PlaylistEntryId, PlaylistId, Position, Timestamp, TrackId,
    };
    use yrs::updates::decoder::Decode;
    use yrs::{Doc, ReadTxn, StateVector, Transact, Update};

    use super::{JournalState, Roots};
    use crate::journal::op::Op;
    use crate::journal::op_meta::OpMeta;

    /// Реплика журнала в памяти: документ и его корни.
    struct Replica {
        doc: Doc,
        roots: Roots,
        device: DeviceId,
    }

    impl Replica {
        fn new() -> Self {
            let doc = Doc::new();
            let roots = Roots::new(&doc);
            Self { doc, roots, device: DeviceId::new() }
        }

        fn write(&self, op: &Op) -> Result<bool, CoreError> {
            let mut txn = self.doc.transact_mut();
            self.roots.write(&mut txn, op, &OpMeta::now(self.device))
        }

        fn state(&self) -> JournalState {
            self.roots.state(&self.doc.transact())
        }

        /// Обмен обновлениями в обе стороны — синхронизация v1.5 в миниатюре.
        fn sync(&self, other: &Self) {
            for (from, to) in [(self, other), (other, self)] {
                let known = to.doc.transact().state_vector();
                let update = from.doc.transact().encode_state_as_update_v1(&known);
                to.doc.transact_mut().apply_update(Update::decode_v1(&update).unwrap()).unwrap();
            }
        }

        fn fork(&self) -> Self {
            let copy = Self::new();
            let update = self.doc.transact().encode_state_as_update_v1(&StateVector::default());
            copy.doc.transact_mut().apply_update(Update::decode_v1(&update).unwrap()).unwrap();
            copy
        }
    }

    fn playlist(name: &str) -> Playlist {
        Playlist {
            id: PlaylistId::new(),
            name: name.to_owned(),
            kind: PlaylistKind::Manual,
            created_at: Timestamp::now(),
        }
    }

    fn entry(playlist: &Playlist, position: Position) -> PlaylistEntry {
        PlaylistEntry {
            id: PlaylistEntryId::new(),
            playlist: playlist.id,
            track: TrackId::new(),
            position,
            added_at: Timestamp::now(),
        }
    }

    /// Плейлист из `n` записей, каждая — в конец.
    fn filled(replica: &Replica, n: usize) -> (Playlist, Vec<PlaylistEntry>) {
        let list = playlist("Mix");
        replica.write(&Op::CreatePlaylist(list.clone())).unwrap();
        let mut entries = Vec::new();
        for _ in 0..n {
            let added = entry(&list, position_for(&entries, entries.len(), None));
            replica.write(&Op::AddEntry(added.clone())).unwrap();
            entries.push(added);
        }
        (list, entries)
    }

    fn play(track: TrackId) -> PlayEvent {
        PlayEvent {
            id: PlayEventId::new(),
            track,
            version: None,
            source: None,
            started_at: Timestamp::from_millis(1_000),
            utc_offset_minutes: 180,
            listened: Duration::from_secs(200),
            track_length: Some(Duration::from_secs(240)),
            skipped_at: None,
            output: OutputDevice::Speaker,
            previous_track: None,
        }
    }

    #[test]
    fn every_kind_of_data_reads_back() {
        let replica = Replica::new();
        let (track, artist) = (TrackId::new(), ArtistId::new());
        let rating = Rating::new(4).unwrap();
        let (list, entries) = filled(&replica, 2);
        let heard = play(track);
        let subscription = Subscription { artist, since: Timestamp::from_millis(3) };
        let blocked = BlockEntry { target: BlockTarget::Track(track), since: Timestamp::from_millis(4) };
        let setting = Setting::VersionPreference(VersionPreference::Clean);

        for op in [
            Op::Like { track },
            Op::Rate { track, rating: Some(rating) },
            Op::Play(heard),
            Op::Subscribe(subscription),
            Op::Block(blocked),
            Op::Set(setting),
        ] {
            assert!(replica.write(&op).unwrap(), "{op:?}");
        }

        let state = replica.state();
        assert_eq!(state.likes, vec![track]);
        assert_eq!(state.ratings, vec![(track, rating)]);
        assert_eq!(state.playlists, vec![list]);
        assert_eq!(state.entries.len(), 2);
        assert!(entries.iter().all(|e| state.entries.contains(e)));
        assert_eq!(state.plays, vec![heard]);
        assert_eq!(state.subscriptions, vec![subscription]);
        assert_eq!(state.blocklist, vec![blocked]);
        assert_eq!(state.settings, vec![setting]);
        assert_eq!(state.unreadable, 0);
    }

    /// Операция без последствий не пишет в журнал ничего.
    #[test]
    fn a_no_op_changes_nothing() {
        let replica = Replica::new();
        let track = TrackId::new();
        let (list, entries) = filled(&replica, 1);
        let setting = Setting::VersionPreference(VersionPreference::Any);
        replica.write(&Op::Like { track }).unwrap();
        replica.write(&Op::Set(setting)).unwrap();

        for op in [
            Op::Like { track },
            Op::Unlike { track: TrackId::new() },
            Op::Rate { track, rating: None },
            Op::CreatePlaylist(list.clone()),
            Op::RenamePlaylist { playlist: list.id, name: list.name.clone() },
            Op::MoveEntry { entry: entries[0].id, position: entries[0].position.clone() },
            Op::RemoveEntry { entry: PlaylistEntryId::new() },
            Op::DeletePlaylist { playlist: PlaylistId::new() },
            Op::Unsubscribe { artist: ArtistId::new() },
            Op::Unblock { target: BlockTarget::Artist(ArtistId::new()) },
            Op::Set(setting),
        ] {
            assert!(!replica.write(&op).unwrap(), "{op:?}");
        }
    }

    /// Ссылка на то, чего нет, — ошибка, и документ не меняется.
    #[test]
    fn missing_playlist_or_entry_is_an_error() {
        let replica = Replica::new();
        let ghost = playlist("Ghost");
        let before = replica.state();

        for op in [
            Op::RenamePlaylist { playlist: ghost.id, name: "New".to_owned() },
            Op::AddEntry(entry(&ghost, Position::first())),
            Op::MoveEntry { entry: PlaylistEntryId::new(), position: Position::first() },
        ] {
            assert!(matches!(replica.write(&op), Err(CoreError::Unavailable { .. })), "{op:?}");
        }
        assert_eq!(replica.state(), before);
    }

    #[test]
    fn deleting_a_playlist_takes_its_entries_only() {
        let replica = Replica::new();
        let (doomed, _) = filled(&replica, 3);
        let (kept, kept_entries) = filled(&replica, 2);

        assert!(replica.write(&Op::DeletePlaylist { playlist: doomed.id }).unwrap());

        let state = replica.state();
        assert_eq!(state.playlists, vec![kept]);
        assert_eq!(state.entries.len(), kept_entries.len());
    }

    #[test]
    fn rename_keeps_the_rest_of_the_playlist() {
        let replica = Replica::new();
        let (list, _) = filled(&replica, 1);

        replica.write(&Op::RenamePlaylist { playlist: list.id, name: "Road".to_owned() }).unwrap();

        assert_eq!(replica.state().playlists, vec![Playlist { name: "Road".to_owned(), ..list }]);
    }

    /// Находка C1: оба устройства переставили один трек — трек не размножается.
    #[test]
    fn concurrent_moves_of_one_entry_keep_one_entry() {
        let phone = Replica::new();
        let (_, entries) = filled(&phone, 5);
        let tablet = phone.fork();
        let moved = entries[0].id;

        phone.write(&Op::MoveEntry { entry: moved, position: position_for(&entries, 5, Some(moved)) }).unwrap();
        tablet.write(&Op::MoveEntry { entry: moved, position: position_for(&entries, 2, Some(moved)) }).unwrap();
        phone.sync(&tablet);

        assert_eq!(phone.state(), tablet.state());
        assert_eq!(phone.state().entries.len(), 5);
    }

    /// Множества — «добавление побеждает»: параллельные лайк и снятие оставляют лайк.
    #[test]
    fn a_concurrent_like_beats_an_unlike() {
        let phone = Replica::new();
        let track = TrackId::new();
        phone.write(&Op::Like { track }).unwrap();
        let tablet = phone.fork();

        tablet.write(&Op::Unlike { track }).unwrap();
        phone.write(&Op::Unlike { track }).unwrap();
        phone.write(&Op::Like { track }).unwrap();
        phone.sync(&tablet);

        assert_eq!(phone.state().likes, vec![track]);
        assert_eq!(phone.state(), tablet.state());
    }

    /// Переименование на одном устройстве и удаление на другом: плейлист
    /// выживает с новым именем. Записи, удалённые вместе с ним, не
    /// возвращаются — их никто не трогал после удаления.
    #[test]
    fn a_concurrent_rename_beats_a_delete() {
        let phone = Replica::new();
        let (list, _) = filled(&phone, 2);
        let tablet = phone.fork();

        phone.write(&Op::DeletePlaylist { playlist: list.id }).unwrap();
        tablet.write(&Op::RenamePlaylist { playlist: list.id, name: "Keep".to_owned() }).unwrap();
        phone.sync(&tablet);

        assert_eq!(phone.state().playlists, vec![Playlist { name: "Keep".to_owned(), ..list }]);
        assert_eq!(phone.state(), tablet.state());
    }

    /// Прослушивания двух устройств складываются, а не спорят.
    #[test]
    fn plays_from_two_devices_add_up() {
        let phone = Replica::new();
        let tablet = Replica::new();
        let track = TrackId::new();

        phone.write(&Op::Play(play(track))).unwrap();
        tablet.write(&Op::Play(play(track))).unwrap();
        tablet.write(&Op::Like { track }).unwrap();
        phone.sync(&tablet);

        assert_eq!(phone.state().plays.len(), 2);
        assert_eq!(phone.state().likes, vec![track]);
        assert_eq!(phone.state(), tablet.state());
    }

    /// Нечитаемая запись — из будущей версии или битая — пропускается и считается.
    #[test]
    fn unreadable_records_are_skipped_and_counted() {
        let replica = Replica::new();
        let track = TrackId::new();
        replica.write(&Op::Like { track }).unwrap();
        {
            let mut txn = replica.doc.transact_mut();
            replica.roots.inject_raw_like(&mut txn, &TrackId::new().to_string(), vec![0, 1, 2]);
            replica.roots.inject_raw_like(&mut txn, "not-a-track-id", vec![1]);
        }

        let state = replica.state();

        assert_eq!(state.likes, vec![track]);
        assert_eq!(state.unreadable, 2);
    }

    /// Критерий № 1 из C1: журнал на 10 000 событий — в единицах мегабайт.
    /// Нагрузка как в замере C1: 2 000 треков, 85 % — прослушивания со всеми
    /// фактами, остальное — лайки, оценки и плейлисты; по транзакции на событие.
    #[test]
    fn ten_thousand_events_fit_in_about_a_megabyte() {
        let replica = Replica::new();
        let tracks: Vec<TrackId> = (0..2_000).map(|_| TrackId::new()).collect();
        let (list, _) = filled(&replica, 0);
        let mut entries: Vec<PlaylistEntry> = Vec::new();
        let mut seed = 42_u64;
        let mut next = move |bound: usize| {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            (seed % bound as u64) as usize
        };
        let mut tail = 0;

        for _ in 0..10_000 {
            let track = tracks[next(tracks.len())];
            let op = match next(100) {
                0..85 => Op::Play(PlayEvent {
                    version: Some(plinth_types::VersionId::new()),
                    skipped_at: Some(Duration::from_secs(100)),
                    previous_track: Some(tracks[next(tracks.len())]),
                    ..play(track)
                }),
                85..92 => Op::Like { track },
                92..95 => Op::Rate { track, rating: Some(Rating::new(3).unwrap()) },
                95..98 => {
                    let added = entry(&list, position_for(&entries, next(entries.len() + 1), None));
                    entries.push(added.clone());
                    Op::AddEntry(added)
                }
                _ => Op::Unlike { track },
            };
            let mut txn = replica.doc.transact_mut();
            if replica.roots.write(&mut txn, &op, &OpMeta::now(replica.device)).unwrap() {
                tail += txn.encode_update_v1().len();
            }
        }
        let snapshot = replica.doc.transact().encode_state_as_update_v2(&StateVector::default()).len();

        println!("10 000 events: snapshot {} KB, tail {} KB", snapshot / 1024, tail / 1024);
        assert!(snapshot < 2 * 1024 * 1024, "{snapshot}");
        assert!(tail < 3 * 1024 * 1024, "{tail}");
    }
}
