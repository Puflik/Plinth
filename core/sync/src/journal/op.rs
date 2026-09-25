//! Перечень операций журнала (C2.2) — всё, чем пользователь меняет
//! незаменимые данные (ADR 0007). Операция — намерение; как она ложится в
//! документ CRDT, решает `doc.rs`, как в базу — `apply.rs`.

use plinth_library::model::{
    BlockEntry, BlockTarget, MergeDecision, PlayEvent, Playlist, PlaylistEntry, Rating, Setting, Subscription,
};
use plinth_types::{ArtistId, PlaylistEntryId, PlaylistId, Position, TrackId};

#[derive(Debug, Clone, PartialEq)]
pub enum Op {
    Like {
        track: TrackId,
    },
    Unlike {
        track: TrackId,
    },
    /// Оценка звёздами; `None` — снять оценку.
    Rate {
        track: TrackId,
        rating: Option<Rating>,
    },
    CreatePlaylist(Playlist),
    RenamePlaylist {
        playlist: PlaylistId,
        name: String,
    },
    /// Удаляет плейлист вместе с его записями.
    DeletePlaylist {
        playlist: PlaylistId,
    },
    /// Запись в существующий плейлист; позицию считает `model::position_for`.
    AddEntry(PlaylistEntry),
    /// Перестановка — новая позиция одной записи, остальные не трогаются.
    MoveEntry {
        entry: PlaylistEntryId,
        position: Position,
    },
    RemoveEntry {
        entry: PlaylistEntryId,
    },
    Play(PlayEvent),
    /// Решение о склейке или разъединении; решения только добавляются.
    Decide(MergeDecision),
    Subscribe(Subscription),
    Unsubscribe {
        artist: ArtistId,
    },
    Block(BlockEntry),
    Unblock {
        target: BlockTarget,
    },
    Set(Setting),
}
