//! Р1.11б (`docs/work/r1-11b.md`, находка 22 ревью v0.2): позиции для списка,
//! который заполняется разом. Цепочка `Position::after` растёт по байту на
//! ~127 записей: у 60 тысяч записей M3U последняя позиция — 948 символов, а
//! все вместе — 28,5 МБ в журнале (копия в папке перестаёт читаться). Позиции
//! `Position::run` короткие при любой длине и по-прежнему допускают вставку
//! между соседями и по краям.
//!
//! `Position::run` — новый API (сигнатура в `docs/work/r1-11b.md`): пока его
//! нет, тесты крейта не собираются.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

use plinth_types::Position;

#[test]
fn a_run_of_nothing_and_of_one() {
    assert!(Position::run(0).is_empty());
    assert_eq!(Position::run(1).len(), 1);
}

#[test]
fn a_run_goes_up_and_its_text_sorts_the_same() {
    for count in [2, 3, 10, 1_000, 60_000] {
        let run = Position::run(count);

        assert_eq!(run.len(), count);
        assert!(run.windows(2).all(|pair| pair[0] < pair[1]), "{count}: positions");
        let texts: Vec<String> = run.iter().map(Position::to_string).collect();
        assert!(texts.windows(2).all(|pair| pair[0] < pair[1]), "{count}: the text form must sort as the positions do");
        for at in (0..count).step_by(97).chain([count - 1]) {
            assert_eq!(texts[at].parse::<Position>().unwrap(), run[at], "{count}: round trip of {at}");
        }
    }
}

/// Длина не растёт с числом записей: цепочка `after` на 60 тысячах даёт 948 символов.
#[test]
fn a_run_stays_short_however_long() {
    for count in [1_000, 60_000, 200_000] {
        let lengths: Vec<usize> = Position::run(count).iter().map(|position| position.to_string().len()).collect();

        let longest = lengths.iter().copied().max().unwrap();
        let total: usize = lengths.iter().sum();
        assert!(longest <= 48, "{count}: the longest position is {longest} characters");
        assert!(total <= count * 24, "{count}: {total} characters in all");
    }
}

/// Запись можно вставить между любыми двумя соседями и по краям.
#[test]
fn a_run_leaves_room_between_neighbours_and_at_both_ends() {
    let run = Position::run(1_000);

    for pair in run.windows(2) {
        let between = Position::between(&pair[0], &pair[1]).unwrap();
        assert!(pair[0] < between && between < pair[1]);
    }
    assert!(Position::before(&run[0]) < run[0]);
    assert!(Position::after(&run[run.len() - 1]) > run[run.len() - 1]);
}
