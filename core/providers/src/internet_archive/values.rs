//! Значения полей Internet Archive (E2.5): любительский каталог пишет их
//! как придётся — год `0`, длительность секундами или `мм:сс`, номер
//! `01` или `1/14`, длина файла в 104 часа.

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

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use plinth_types::Bitrate;

    use super::{bitrate, duration, track_number, year};

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
}
