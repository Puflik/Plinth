//! Позиция записи в упорядоченном списке — дробный индекс (B1.3).
//!
//! Порядок в плейлисте не хранится индексами массива. У каждой записи своя
//! позиция, а перестановка меняет позицию одной записи. Так параллельные
//! перестановки на двух устройствах не размножают трек: при удалении и
//! вставке в список CRDT размножали (C1, `core/sync/prototypes/BENCH.md`).
//! Две записи с равной позицией (вставили в одно место на двух
//! устройствах) упорядочиваются по идентификатору записи.

use std::fmt;
use std::str::FromStr;

use fractional_index::FractionalIndex;

use crate::CoreError;

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub struct Position(FractionalIndex);

impl Position {
    /// Позиция первой записи пустого списка.
    pub fn first() -> Self {
        Self(FractionalIndex::default())
    }

    pub fn before(next: &Self) -> Self {
        Self(FractionalIndex::new_before(&next.0))
    }

    pub fn after(previous: &Self) -> Self {
        Self(FractionalIndex::new_after(&previous.0))
    }

    /// Позиция строго между соседями; `None`, если `left` не меньше `right`.
    pub fn between(left: &Self, right: &Self) -> Option<Self> {
        FractionalIndex::new_between(&left.0, &right.0).map(Self)
    }
}

impl std::hash::Hash for Position {
    fn hash<H: std::hash::Hasher>(&self, state: &mut H) {
        self.0.as_bytes().hash(state);
    }
}

/// Шестнадцатеричная строка; строки сортируются так же, как позиции, —
/// SQLite упорядочивает по колонке TEXT без разбора.
impl fmt::Display for Position {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0.to_string())
    }
}

impl FromStr for Position {
    type Err = CoreError;

    fn from_str(text: &str) -> Result<Self, Self::Err> {
        FractionalIndex::from_string(text).map(Self).map_err(|error| CoreError::parse(format!("Position: {error}")))
    }
}

#[cfg(test)]
mod tests {
    use super::Position;
    use crate::CoreError;

    #[test]
    fn between_is_strictly_between() {
        let first = Position::first();
        let last = Position::after(&first);

        let middle = Position::between(&first, &last).unwrap();

        assert!(first < middle && middle < last);
    }

    #[test]
    fn between_needs_left_below_right() {
        let first = Position::first();

        assert!(Position::between(&first, &first).is_none());
        assert!(Position::between(&Position::after(&first), &first).is_none());
    }

    /// Пользователь раз за разом тащит трек на самый верх — позиция не кончается.
    #[test]
    fn inserting_at_the_top_many_times_keeps_order() {
        let mut positions = vec![Position::first()];
        for _ in 0..2_000 {
            positions.insert(0, Position::before(&positions[0]));
        }

        assert!(positions.windows(2).all(|pair| pair[0] < pair[1]));
    }

    /// Вставка всё время между двумя соседями — худший случай для длины.
    #[test]
    fn inserting_between_the_same_neighbours_keeps_order() {
        let low = Position::first();
        let mut high = Position::after(&low);
        for _ in 0..500 {
            let middle = Position::between(&low, &high).unwrap();
            assert!(low < middle && middle < high);
            high = middle;
        }
    }

    /// Текстовая форма сортируется так же, как позиции: SQLite упорядочит по колонке TEXT.
    #[test]
    fn text_form_round_trips_and_sorts_the_same() {
        let mut positions = vec![Position::first()];
        for i in 0..200 {
            let next = if i % 2 == 0 {
                Position::after(positions.last().unwrap())
            } else {
                Position::between(&positions[positions.len() - 2], positions.last().unwrap()).unwrap()
            };
            positions.push(next);
        }
        positions.sort();

        let texts: Vec<String> = positions.iter().map(Position::to_string).collect();
        let mut sorted_texts = texts.clone();
        sorted_texts.sort();

        assert_eq!(texts, sorted_texts);
        for (text, position) in texts.iter().zip(&positions) {
            assert_eq!(&text.parse::<Position>().unwrap(), position);
        }
    }

    #[test]
    fn broken_text_is_a_parse_error() {
        assert!(matches!("zz".parse::<Position>(), Err(CoreError::Parse { .. })));
        assert!(matches!("".parse::<Position>(), Err(CoreError::Parse { .. })));
    }
}
