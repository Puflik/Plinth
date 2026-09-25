use plinth_types::{PlaylistEntryId, PlaylistId, Position, Timestamp, TrackId};

/// Плейлист (B1.3). Живёт в журнале; записи — отдельно ([`PlaylistEntry`]).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Playlist {
    pub id: PlaylistId,
    pub name: String,
    pub kind: PlaylistKind,
    pub created_at: Timestamp,
}

/// Умные плейлисты с правилами придут в v2+ отдельным вариантом.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum PlaylistKind {
    Manual,
}

/// Трек в плейлисте. Порядок — по [`Position`], а не по индексу: перестановка
/// меняет позицию одной записи, и параллельные перестановки на двух
/// устройствах не размножают трек (C1).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlaylistEntry {
    pub id: PlaylistEntryId,
    pub playlist: PlaylistId,
    pub track: TrackId,
    pub position: Position,
    pub added_at: Timestamp,
}

/// Записи в порядке плейлиста: по позиции, равные позиции — по записи.
/// Одинаково на всех устройствах.
pub fn ordered_entries(mut entries: Vec<PlaylistEntry>) -> Vec<PlaylistEntry> {
    entries.sort_by(|a, b| (&a.position, a.id).cmp(&(&b.position, b.id)));
    entries
}

/// Позиция, с которой запись встанет на место `index` в упорядоченном
/// `sorted`. `moving` — запись из того же списка, которую переставляют: её
/// нынешняя позиция соседом не считается.
///
/// Между двумя равными позициями (их вставили в одно место на двух
/// устройствах) места нет — запись встаёт сразу после них.
pub fn position_for(sorted: &[PlaylistEntry], index: usize, moving: Option<PlaylistEntryId>) -> Position {
    let others: Vec<&Position> = sorted.iter().filter(|e| Some(e.id) != moving).map(|e| &e.position).collect();
    let index = index.min(others.len());
    let before = index.checked_sub(1).and_then(|i| others.get(i).copied());
    let after = others.get(index).copied();
    match (before, after) {
        (None, None) => Position::first(),
        (Some(before), None) => Position::after(before),
        (None, Some(after)) => Position::before(after),
        (Some(before), Some(after)) => Position::between(before, after).unwrap_or_else(|| Position::after(before)),
    }
}

#[cfg(test)]
mod tests {
    use plinth_types::{PlaylistEntryId, PlaylistId, Position, Timestamp, TrackId};

    use super::{PlaylistEntry, ordered_entries, position_for};

    fn entry(playlist: PlaylistId, position: Position) -> PlaylistEntry {
        PlaylistEntry {
            id: PlaylistEntryId::new(),
            playlist,
            track: TrackId::new(),
            position,
            added_at: Timestamp::from_millis(0),
        }
    }

    /// Собирает плейлист, вставляя каждый трек в конец.
    fn playlist_of(n: usize) -> Vec<PlaylistEntry> {
        let playlist = PlaylistId::new();
        let mut entries: Vec<PlaylistEntry> = Vec::new();
        for _ in 0..n {
            let position = position_for(&entries, entries.len(), None);
            entries.push(entry(playlist, position));
        }
        entries
    }

    fn tracks(entries: &[PlaylistEntry]) -> Vec<TrackId> {
        ordered_entries(entries.to_vec()).into_iter().map(|e| e.track).collect()
    }

    #[test]
    fn appended_entries_keep_their_order() {
        let entries = playlist_of(5);

        assert_eq!(tracks(&entries), entries.iter().map(|e| e.track).collect::<Vec<_>>());
    }

    #[test]
    fn inserting_at_an_index_lands_there() {
        let mut entries = playlist_of(4);
        let expected_before: Vec<TrackId> = tracks(&entries);

        for index in [0, 2, 5] {
            let sorted = ordered_entries(entries.clone());
            let new = entry(entries[0].playlist, position_for(&sorted, index, None));
            let track = new.track;
            entries.push(new);

            assert_eq!(tracks(&entries)[index], track, "index {index}");
        }
        let after: Vec<TrackId> = tracks(&entries);
        assert!(expected_before.iter().all(|t| after.contains(t)));
    }

    /// Перестановка меняет позицию одной записи — остальные не трогаются.
    #[test]
    fn moving_an_entry_changes_only_its_position() {
        let entries = ordered_entries(playlist_of(5));
        let moved = entries[0].id;

        let position = position_for(&entries, 3, Some(moved));
        let mut after = entries.clone();
        after[0].position = position;
        let after = ordered_entries(after);

        let order: Vec<PlaylistEntryId> = after.iter().map(|e| e.id).collect();
        let expected = vec![entries[1].id, entries[2].id, entries[3].id, moved, entries[4].id];
        assert_eq!(order, expected);
        for (old, new) in entries.iter().skip(1).zip(after.iter().filter(|e| e.id != moved)) {
            assert_eq!(old.position, new.position);
        }
    }

    /// Два устройства вставили в одно место: позиции равны, порядок — по записи.
    #[test]
    fn equal_positions_are_ordered_by_entry_id() {
        let playlist = PlaylistId::new();
        let (a, b) = (entry(playlist, Position::first()), entry(playlist, Position::first()));
        let (low, high) = if a.id < b.id { (a.id, b.id) } else { (b.id, a.id) };

        let order: Vec<PlaylistEntryId> = ordered_entries(vec![a.clone(), b.clone()]).iter().map(|e| e.id).collect();
        let reversed: Vec<PlaylistEntryId> = ordered_entries(vec![b, a]).iter().map(|e| e.id).collect();

        assert_eq!(order, vec![low, high]);
        assert_eq!(reversed, order);
    }

    /// Между двумя равными позициями вставить нельзя — запись встаёт сразу после них.
    #[test]
    fn insert_between_equal_positions_goes_right_after_them() {
        let playlist = PlaylistId::new();
        let tied = ordered_entries(vec![entry(playlist, Position::first()), entry(playlist, Position::first())]);

        let position = position_for(&tied, 1, None);

        assert!(position > tied[1].position);
    }
}
