//! Значения полей Internet Archive (E2.5): любительский каталог пишет их
//! как придётся — год `0`, длительность секундами или `мм:сс`, номер
//! `01` или `1/14`, длина файла в 104 часа.

use std::cmp::Ordering;
use std::time::Duration;

use plinth_types::Bitrate;

/// Правдоподобная длительность трека: больше нуля и не больше суток.
const LONGEST: Duration = Duration::from_secs(24 * 60 * 60);

/// Год из `2013`, `2005-02-24 00:00:00`; `0` и мусор — `None`.
pub(super) fn year(text: &str) -> Option<u16> {
    let digits: String = text.trim().chars().take_while(char::is_ascii_digit).collect();
    if digits.len() != 4 {
        return None;
    }
    digits.parse::<u16>().ok().filter(|year| *year > 0)
}

/// `1315.72`, `21:55`, `1:02:03`. Неправдоподобная — `None`: у исходника
/// длина бывает неверной, тогда берётся длина другого файла трека.
pub(super) fn duration(text: &str) -> Option<Duration> {
    let text = text.trim();
    let seconds = if text.contains(':') {
        let mut total = 0.0_f64;
        let parts: Vec<&str> = text.split(':').collect();
        if parts.len() > 3 {
            return None;
        }
        for part in parts {
            let value = part.parse::<f64>().ok().filter(|v| v.is_finite() && *v >= 0.0)?;
            total = total * 60.0 + value;
        }
        total
    } else {
        text.parse::<f64>().ok()?
    };
    let plausible = seconds.is_finite() && seconds > 0.0 && seconds <= LONGEST.as_secs_f64();
    plausible.then(|| Duration::from_secs_f64(seconds))
}

/// Номер трека из `01`, `1/14`; `0` и `A1` — `None`.
pub(super) fn track_number(text: &str) -> Option<u16> {
    let number = text.trim().split('/').next()?.trim();
    number.parse::<u16>().ok().filter(|n| *n > 0)
}

/// Битрейт файла в кбит/с: `151`; `0` и мусор — `None`.
pub(super) fn bitrate(text: &str) -> Option<Bitrate> {
    text.trim().parse::<u32>().ok().filter(|kbps| *kbps > 0).map(Bitrate::kbps)
}

/// Диск трека по имени исходного файла (с папками). `/metadata` диск не
/// называет, его видно по имени: у etree — `d2t05` в имени файла, у альбомов —
/// папка `CD2/`, `Disc 2/`. Что только похоже на диск, диском не считается.
pub(super) fn disc(anchor: &str) -> Option<u16> {
    let (folders, file) = anchor.rsplit_once('/').unwrap_or(("", anchor));
    etree_disc(file).or_else(|| folders.split('/').find_map(folder_disc))
}

/// `d<N>t<M>`: `d` без буквы перед ней, 1–2 цифры диска, `t`, 1–3 цифры трека,
/// дальше не цифра. Берётся первое такое место.
fn etree_disc(file: &str) -> Option<u16> {
    let bytes = file.as_bytes();
    (0..bytes.len()).filter(|&at| bytes[at].eq_ignore_ascii_case(&b'd')).find_map(|at| {
        if at > 0 && bytes[at - 1].is_ascii_alphabetic() {
            return None;
        }
        let disc = digits(&bytes[at + 1..]);
        let after_disc = bytes.get(at + 1 + disc.len())?;
        if !after_disc.eq_ignore_ascii_case(&b't') || !(1..=2).contains(&disc.len()) {
            return None;
        }
        let track = digits(&bytes[at + 2 + disc.len()..]);
        (1..=3).contains(&track.len()).then(|| disc_number(disc)).flatten()
    })
}

/// Папка `cd`/`disc`/`disk` с номером: `CD1`, `cd 2`, `Disc_03`, `Disk-4`,
/// `CD 2 - Live`. `Discography`, `CD 2007 Remaster`, `Bonus CD 2` — не диски.
fn folder_disc(folder: &str) -> Option<u16> {
    let bytes = folder.as_bytes();
    let name = ["cd", "disc", "disk"]
        .into_iter()
        .find(|name| bytes.len() >= name.len() && bytes[..name.len()].eq_ignore_ascii_case(name.as_bytes()))?;
    let rest = &bytes[name.len()..];
    let separators = rest.iter().take_while(|byte| matches!(byte, b' ' | b'_' | b'.' | b'-')).count();
    let number = digits(&rest[separators..]);
    if !(1..=2).contains(&number.len()) {
        return None;
    }
    disc_number(number)
}

/// Начало `bytes` из цифр ASCII.
fn digits(bytes: &[u8]) -> &[u8] {
    let len = bytes.iter().take_while(|byte| byte.is_ascii_digit()).count();
    &bytes[..len]
}

/// Диск 0 — не диск, как трек «0» — не номер.
fn disc_number(digits: &[u8]) -> Option<u16> {
    std::str::from_utf8(digits).ok()?.parse::<u16>().ok().filter(|disc| *disc > 0)
}

/// Естественный порядок имён: цифры сравниваются как числа (`2` раньше `10`),
/// текст — без учёта регистра, число раньше текста, короткое — раньше своего
/// продолжения. Равные по всем кускам (`B`/`b`, `02`/`2`) идут по байтам
/// исходных строк: порядок полный и повторяемый.
pub(super) fn natural_cmp(a: &str, b: &str) -> Ordering {
    let (left, right) = (chunks(a), chunks(b));
    for (x, y) in left.iter().zip(&right) {
        let order = match (x, y) {
            (Chunk::Number(x), Chunk::Number(y)) => compare_numbers(x, y),
            (Chunk::Text(x), Chunk::Text(y)) => x.to_lowercase().cmp(&y.to_lowercase()),
            (Chunk::Number(_), Chunk::Text(_)) => Ordering::Less,
            (Chunk::Text(_), Chunk::Number(_)) => Ordering::Greater,
        };
        if order != Ordering::Equal {
            return order;
        }
    }
    left.len().cmp(&right.len()).then_with(|| a.cmp(b))
}

enum Chunk<'a> {
    Number(&'a str),
    Text(&'a str),
}

/// Подряд идущие цифры — число, остальное подряд — текст.
fn chunks(text: &str) -> Vec<Chunk<'_>> {
    let mut chunks = Vec::new();
    let mut rest = text;
    while let Some(first) = rest.chars().next() {
        let is_number = first.is_ascii_digit();
        let end = rest.find(|c: char| c.is_ascii_digit() != is_number).unwrap_or(rest.len());
        let (head, tail) = rest.split_at(end);
        chunks.push(if is_number { Chunk::Number(head) } else { Chunk::Text(head) });
        rest = tail;
    }
    chunks
}

/// Числа из цифр любой длины — без переполнения: без ведущих нулей сначала
/// сравнивается длина.
fn compare_numbers(a: &str, b: &str) -> Ordering {
    let (a, b) = (a.trim_start_matches('0'), b.trim_start_matches('0'));
    a.len().cmp(&b.len()).then_with(|| a.cmp(b))
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use plinth_types::Bitrate;

    use super::{bitrate, disc, duration, natural_cmp, track_number, year};

    #[test]
    fn years() {
        assert_eq!(year("2013"), Some(2013));
        assert_eq!(year("2005-02-24 00:00:00"), Some(2005));
        assert_eq!(year("1977-05-08"), Some(1977));
        for bad in ["0", "0000", "", "19", "circa 1920", "20133"] {
            assert_eq!(year(bad), None, "{bad}");
        }
    }

    #[test]
    fn durations_in_seconds_and_clock_form() {
        assert_eq!(duration("1315.72"), Some(Duration::from_secs_f64(1315.72)));
        assert_eq!(duration("21:55"), Some(Duration::from_secs(21 * 60 + 55)));
        assert_eq!(duration("06:21"), Some(Duration::from_secs(381)));
        assert_eq!(duration("1:02:03"), Some(Duration::from_secs(3723)));
        assert_eq!(duration(" 532.45 "), Some(Duration::from_secs_f64(532.45)));
    }

    #[test]
    fn implausible_durations_are_dropped() {
        for bad in ["374491.55", "0", "-5", "NaN", "inf", "", "abc", "1:2:3:4", "1:-2", "12:xx"] {
            assert_eq!(duration(bad), None, "{bad}");
        }
        assert!(duration("86400").is_some());
        assert_eq!(duration("86401"), None);
    }

    #[test]
    fn track_numbers() {
        assert_eq!(track_number("01"), Some(1));
        assert_eq!(track_number("1/14"), Some(1));
        assert_eq!(track_number(" 10 "), Some(10));
        for bad in ["0", "A1", "", "/3", "x/1"] {
            assert_eq!(track_number(bad), None, "{bad}");
        }
    }

    #[test]
    fn bitrates() {
        assert_eq!(bitrate("151"), Some(Bitrate::kbps(151)));
        for bad in ["0", "", "VBR", "-1"] {
            assert_eq!(bitrate(bad), None, "{bad}");
        }
    }

    #[test]
    fn disc_from_the_etree_marker() {
        for (name, expected) in [
            ("gd77-05-08d1t01.flac", 1),
            ("show.2006.D2T1.flac", 2),
            ("set_d4t10.flac", 4),
            ("show-d3t001.flac", 3),
            ("d12t3.flac", 12),
            ("Odd5t1 d2t2.flac", 2),
        ] {
            assert_eq!(disc(name), Some(expected), "{name}");
        }
        for bad in ["Odd1t3.flac", "d100t1.flac", "d1t1234.flac", "d0t1.flac", "d1.flac", "dt1.flac", "plain.flac"] {
            assert_eq!(disc(bad), None, "{bad}");
        }
    }

    #[test]
    fn disc_from_a_folder() {
        for (name, expected) in [
            ("CD1/a.flac", 1),
            ("cd 2/a.flac", 2),
            ("Disc_03/a.flac", 3),
            ("Disk-4/a.flac", 4),
            ("CD 2 - Live/a.flac", 2),
            ("Disc 10/a.flac", 10),
            ("Album/CD2/a.flac", 2),
            ("Disc1.5/a.flac", 1),
        ] {
            assert_eq!(disc(name), Some(expected), "{name}");
        }
        for bad in [
            "Discography/a.flac",
            "CD/a.flac",
            "Disc A/a.flac",
            "CD 2007 Remaster/a.flac",
            "Bonus CD 2/a.flac",
            "CD0/a.flac",
        ] {
            assert_eq!(disc(bad), None, "{bad}");
        }
        assert_eq!(disc("cd2 intro.flac"), None, "the file name is not a folder");
    }

    #[test]
    fn the_marker_in_the_file_name_beats_the_folder() {
        assert_eq!(disc("CD1/show-d2t01.flac"), Some(2));
    }

    #[test]
    fn natural_order_of_names() {
        let mut names = vec!["10 Finale", "3 Song", "1 Intro", "11 Bonus", "2 Song"];
        names.sort_by(|a, b| natural_cmp(a, b));
        assert_eq!(names, ["1 Intro", "2 Song", "3 Song", "10 Finale", "11 Bonus"]);

        let mut names = vec!["track_10", "track_02b", "track_2", "track_1"];
        names.sort_by(|a, b| natural_cmp(a, b));
        assert_eq!(names, ["track_1", "track_2", "track_02b", "track_10"]);

        let mut names = vec!["b", "B", "a2", "A10", "a1"];
        names.sort_by(|a, b| natural_cmp(a, b));
        assert_eq!(names, ["a1", "a2", "A10", "B", "b"]);
    }

    #[test]
    fn natural_order_is_total_and_does_not_overflow() {
        assert_eq!(natural_cmp("02", "2"), std::cmp::Ordering::Less);
        assert_eq!(natural_cmp("2", "02"), std::cmp::Ordering::Greater);
        assert_eq!(natural_cmp("x", "x"), std::cmp::Ordering::Equal);
        let huge = "9".repeat(60);
        let huger = format!("1{}", "0".repeat(60));
        assert_eq!(natural_cmp(&huge, &huger), std::cmp::Ordering::Less);
        assert_eq!(natural_cmp("7", "a"), std::cmp::Ordering::Less, "a number comes before text");
    }
}
