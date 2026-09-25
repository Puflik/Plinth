//! Нормализация тегов (D2.2, plan.md 13.2): несколько исполнителей в одной
//! строке, album artist, номер диска, сборники.
//!
//! Строку исполнителя для показа нормализация не трогает — она уходит в
//! `artist_credit` как есть. Список артистов нужен для связей «трек —
//! артист» и «альбом — артист»: по ним строятся экраны исполнителей.

use std::time::Duration;

use super::Tags;
use crate::model::AudioSpec;
use crate::text::normalize;

/// Теги, как они записаны в файле, — до нормализации.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RawTags {
    pub title: Option<String>,
    /// Значения тега исполнителя: обычно одно, в ID3v2.4 и Vorbis Comments —
    /// сколько угодно.
    pub artist: Vec<String>,
    /// Готовый список артистов (`ARTISTS` у MusicBrainz Picard), если есть.
    pub artists: Vec<String>,
    pub album: Option<String>,
    pub album_artist: Vec<String>,
    pub track: Option<u32>,
    pub disc: Option<u32>,
    pub disc_total: Option<u32>,
    pub year: Option<u32>,
    pub compilation: Option<bool>,
    pub duration: Option<Duration>,
    pub audio: Option<AudioSpec>,
}

/// Как делить строку исполнителей на артистов. Список настраиваемый
/// (plan.md 13.2): у каждой коллекции свои обычаи.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArtistSplit {
    /// Разделители. Ищутся как есть, с учётом регистра: « x » делит
    /// «Mori Calliope x Gawr Gura», но не «Lil Nas X».
    pub separators: Vec<String>,
    /// Что не делить, без учёта регистра латиницы: имена с разделителем
    /// внутри («AC/DC») и обороты вроде « & the » («Kool & the Gang»).
    pub keep: Vec<String>,
}

impl Default for ArtistSplit {
    fn default() -> Self {
        let separators = [
            ";",
            "/",
            " & ",
            " x ",
            " feat. ",
            " Feat. ",
            " FEAT. ",
            " feat ",
            " Feat ",
            " ft. ",
            " Ft. ",
            " featuring ",
            " Featuring ",
            " (feat. ",
            " (Feat. ",
            " (ft. ",
            " (Ft. ",
            " (featuring ",
        ];
        let keep = [
            "AC/DC",
            "K/DA",
            "Axwell /\\ Ingrosso",
            " & the ",
            "Above & Beyond",
            "Belle & Sebastian",
            "Chase & Status",
            "Crosby, Stills, Nash & Young",
            "Earth, Wind & Fire",
            "Hall & Oates",
            "Iron & Wine",
            "Mumford & Sons",
            "Simon & Garfunkel",
            "Years & Years",
        ];
        Self {
            separators: separators.into_iter().map(str::to_owned).collect(),
            keep: keep.into_iter().map(str::to_owned).collect(),
        }
    }
}

impl ArtistSplit {
    /// Артисты из строки по порядку, без повторов.
    pub fn split(&self, credit: &str) -> Vec<String> {
        let mut names = Vec::new();
        let (mut start, mut at) = (0, 0);
        while at < credit.len() {
            let rest = &credit[at..];
            if let Some(kept) = self.keep.iter().find(|k| starts_with_ignoring_case(rest, k)) {
                at += kept.len();
            } else if let Some(separator) =
                self.separators.iter().find(|s| !s.is_empty() && rest.starts_with(s.as_str()))
            {
                add_name(&mut names, &credit[start..at]);
                at += separator.len();
                start = at;
            } else {
                at += rest.chars().next().map_or(1, char::len_utf8);
            }
        }
        add_name(&mut names, &credit[start..]);
        names
    }

    /// Артисты из значений тега: каждое делится по правилам.
    fn split_all(&self, values: &[String]) -> Vec<String> {
        let mut names = Vec::new();
        for name in values.iter().flat_map(|value| self.split(value)) {
            add_name(&mut names, &name);
        }
        names
    }
}

/// Сравнение без учёта регистра латиницы. Байты сравниваются попарно, поэтому
/// конец совпадения — граница символа в `text`.
fn starts_with_ignoring_case(text: &str, prefix: &str) -> bool {
    !prefix.is_empty()
        && text.len() >= prefix.len()
        && text.as_bytes()[..prefix.len()].eq_ignore_ascii_case(prefix.as_bytes())
}

/// Добавляет имя без краевых пробелов и хвостовой скобки от «(feat. B)»,
/// если такого ещё нет.
fn add_name(names: &mut Vec<String>, name: &str) {
    let mut name = name.trim();
    for (open, close) in [('(', ')'), ('[', ']')] {
        if name.ends_with(close) && !name.contains(open) {
            name = name[..name.len() - close.len_utf8()].trim_end();
        }
    }
    let key = normalize(name);
    if !key.is_empty() && !names.iter().any(|known| normalize(known) == key) {
        names.push(name.to_owned());
    }
}

/// Как пишут исполнителя сборника вместо имени.
const VARIOUS: [&str; 5] = ["various artists", "various", "va", "v a", "разные исполнители"];

/// Сводит сырые теги к тому, что пишет скан.
pub fn normalized(raw: RawTags, rules: &ArtistSplit) -> Tags {
    let artist = credit(&raw.artist);
    let artists = if raw.artists.iter().any(|a| !a.trim().is_empty()) {
        let mut names = Vec::new();
        raw.artists.iter().for_each(|name| add_name(&mut names, name));
        names
    } else {
        rules.split_all(&raw.artist)
    };
    let album_artist = credit(&raw.album_artist);
    let various = album_artist.as_deref().is_some_and(|name| VARIOUS.contains(&normalize(name).as_str()));
    let album_artists = if various { Vec::new() } else { rules.split_all(&raw.album_artist) };
    Tags {
        title: raw.title.as_deref().and_then(text),
        artist,
        artists,
        album: raw.album.as_deref().and_then(text),
        album_artist,
        album_artists,
        compilation: various || raw.compilation == Some(true),
        track: number(raw.track),
        disc: number(raw.disc),
        disc_total: number(raw.disc_total),
        // Год — четыре цифры: 20210 — опечатка, а не год.
        year: number(raw.year).filter(|year| *year <= 9999),
        duration: raw.duration,
        audio: raw.audio,
    }
}

/// Строка для показа: одно значение — как есть, несколько — через «; ».
fn credit(values: &[String]) -> Option<String> {
    let values: Vec<&str> = values.iter().map(|v| v.trim()).filter(|v| !v.is_empty()).collect();
    (!values.is_empty()).then(|| values.join("; "))
}

fn text(value: &str) -> Option<String> {
    let value = value.trim();
    (!value.is_empty()).then(|| value.to_owned())
}

/// Номер трека, диска, год: ноль — «нет» (так пишет ID3v1), больше u16 — мусор.
fn number(value: Option<u32>) -> Option<u16> {
    value.and_then(|v| u16::try_from(v).ok()).filter(|v| *v > 0)
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use plinth_types::Format;

    use super::{ArtistSplit, RawTags, normalized};
    use crate::model::AudioSpec;

    fn split(credit: &str) -> Vec<String> {
        ArtistSplit::default().split(credit)
    }

    fn values(values: &[&str]) -> Vec<String> {
        values.iter().map(|v| (*v).to_owned()).collect()
    }

    #[test]
    fn one_artist_stays_whole() {
        assert_eq!(split("Radiohead"), ["Radiohead"]);
        assert_eq!(split("  Radiohead "), ["Radiohead"]);
        assert!(split("").is_empty());
        assert!(split(" ; / ").is_empty());
    }

    /// Разделители из plan.md 13.2: `;` `/` `feat.` `&` `x`.
    #[test]
    fn the_plan_separators_split_a_credit() {
        assert_eq!(split("Lollia/Sleeping Forest/Shiki Miyoshino"), ["Lollia", "Sleeping Forest", "Shiki Miyoshino"]);
        assert_eq!(split("Alpha; Beta;Gamma"), ["Alpha", "Beta", "Gamma"]);
        assert_eq!(split("Daft Punk feat. Pharrell Williams"), ["Daft Punk", "Pharrell Williams"]);
        assert_eq!(split("Daft Punk Feat. Pharrell"), ["Daft Punk", "Pharrell"]);
        assert_eq!(split("Calvin Harris ft. Rihanna"), ["Calvin Harris", "Rihanna"]);
        assert_eq!(split("AudioNeko & ElliMarshmallow"), ["AudioNeko", "ElliMarshmallow"]);
        assert_eq!(split("Mori Calliope x Gawr Gura"), ["Mori Calliope", "Gawr Gura"]);
    }

    #[test]
    fn separators_mix_and_repeats_are_dropped() {
        assert_eq!(split("A feat. B & C/A"), ["A", "B", "C"]);
        assert_eq!(split("Radiohead; radiohead"), ["Radiohead"]);
    }

    /// Guest в скобках: «A (feat. B)» — двое, скобка не остаётся в имени.
    #[test]
    fn a_featured_artist_in_brackets() {
        assert_eq!(split("Adam Lambert (feat. Xhara)"), ["Adam Lambert", "Xhara"]);
    }

    /// Буква X в имени — не разделитель: « x » делит только строчная.
    #[test]
    fn a_capital_x_is_a_name() {
        assert_eq!(split("Lil Nas X/Jack Harlow"), ["Lil Nas X", "Jack Harlow"]);
        assert_eq!(split("Lil Nas X & Jack Harlow"), ["Lil Nas X", "Jack Harlow"]);
        assert_eq!(split("Malcolm X"), ["Malcolm X"]);
    }

    /// Имена с разделителем внутри не делятся — в коллекции автора есть K/DA.
    #[test]
    fn names_with_a_separator_inside_stay_whole() {
        assert_eq!(
            split("K/DA/Madison Beer/i-dle/Jaira Burns/League of Legends"),
            ["K/DA", "Madison Beer", "i-dle", "Jaira Burns", "League of Legends"]
        );
        assert_eq!(split("ac/dc"), ["ac/dc"]);
        assert_eq!(split("Simon & Garfunkel"), ["Simon & Garfunkel"]);
        assert_eq!(split("Earth, Wind & Fire feat. The Emotions"), ["Earth, Wind & Fire", "The Emotions"]);
        assert_eq!(split("Kool & the Gang"), ["Kool & the Gang"]);
        assert_eq!(split("Bob Marley & The Wailers/Lauryn Hill"), ["Bob Marley & The Wailers", "Lauryn Hill"]);
    }

    /// Запятая не разделитель: «Tyler, The Creator» — один человек.
    #[test]
    fn a_comma_is_not_a_separator() {
        assert_eq!(split("Tyler, The Creator"), ["Tyler, The Creator"]);
    }

    #[test]
    fn rules_are_configurable() {
        let rules = ArtistSplit { separators: values(&[", "]), keep: values(&["Tyler, The Creator"]) };

        assert_eq!(
            rules.split("HOYO-MiX, Lilas Ikuta, 王可鑫, Ruby Qu"),
            ["HOYO-MiX", "Lilas Ikuta", "王可鑫", "Ruby Qu"]
        );
        assert_eq!(rules.split("Tyler, The Creator, Kali Uchis"), ["Tyler, The Creator", "Kali Uchis"]);
        assert_eq!(rules.split("A/B"), ["A/B"]);
    }

    #[test]
    fn a_credit_is_shown_as_written_and_split_into_artists() {
        let tags = normalized(
            RawTags {
                title: Some("Get Lucky".to_owned()),
                artist: values(&["Daft Punk feat. Pharrell"]),
                ..RawTags::default()
            },
            &ArtistSplit::default(),
        );

        assert_eq!(tags.title.as_deref(), Some("Get Lucky"));
        assert_eq!(tags.artist.as_deref(), Some("Daft Punk feat. Pharrell"));
        assert_eq!(tags.artists, ["Daft Punk", "Pharrell"]);
    }

    /// Несколько значений тега (ID3v2.4, Vorbis) — уже разделённые артисты;
    /// для показа они склеиваются через «; ».
    #[test]
    fn several_tag_values_are_several_artists() {
        let tags = normalized(
            RawTags { artist: values(&["Alpha", "Beta feat. Gamma"]), ..RawTags::default() },
            &ArtistSplit::default(),
        );

        assert_eq!(tags.artist.as_deref(), Some("Alpha; Beta feat. Gamma"));
        assert_eq!(tags.artists, ["Alpha", "Beta", "Gamma"]);
    }

    /// Готовый список `ARTISTS` точнее любого разбора строки.
    #[test]
    fn an_artists_list_wins_over_splitting() {
        let raw = RawTags {
            artist: values(&["Simon & Garfunkel feat. X"]),
            artists: values(&["Paul Simon", "Art Garfunkel", "X"]),
            ..RawTags::default()
        };

        let tags = normalized(raw, &ArtistSplit::default());

        assert_eq!(tags.artist.as_deref(), Some("Simon & Garfunkel feat. X"));
        assert_eq!(tags.artists, ["Paul Simon", "Art Garfunkel", "X"]);
    }

    #[test]
    fn empty_values_are_no_tag() {
        let raw = RawTags {
            title: Some("  ".to_owned()),
            artist: values(&["", "  "]),
            album: Some(String::new()),
            album_artist: values(&[" "]),
            track: Some(0),
            disc: Some(0),
            disc_total: Some(0),
            year: Some(0),
            ..RawTags::default()
        };

        let tags = normalized(raw, &ArtistSplit::default());

        assert_eq!(tags, crate::scan::Tags::default());
    }

    #[test]
    fn numbers_disc_and_year_pass_through() {
        let raw = RawTags {
            album: Some("OK Computer".to_owned()),
            track: Some(4),
            disc: Some(2),
            disc_total: Some(2),
            year: Some(1997),
            duration: Some(Duration::from_millis(238_000)),
            audio: Some(AudioSpec {
                format: Format::Flac,
                bitrate: None,
                sample_rate_hz: Some(44_100),
                bit_depth: Some(16),
            }),
            ..RawTags::default()
        };

        let tags = normalized(raw.clone(), &ArtistSplit::default());

        assert_eq!((tags.track, tags.disc, tags.disc_total, tags.year), (Some(4), Some(2), Some(2), Some(1997)));
        assert_eq!((tags.duration, tags.audio), (raw.duration, raw.audio));
        assert_eq!(tags.album.as_deref(), Some("OK Computer"));
    }

    /// Номер больше u16 — мусор, а не номер трека.
    #[test]
    fn absurd_numbers_are_dropped() {
        let tags = normalized(
            RawTags { track: Some(70_000), year: Some(20_210), ..RawTags::default() },
            &ArtistSplit::default(),
        );

        assert_eq!((tags.track, tags.year), (None, None));
    }

    #[test]
    fn album_artist_is_split_like_the_artist() {
        let raw = RawTags {
            album: Some("A".to_owned()),
            album_artist: values(&["Massive Attack & Tracey Thorn"]),
            ..RawTags::default()
        };

        let tags = normalized(raw, &ArtistSplit::default());

        assert_eq!(tags.album_artist.as_deref(), Some("Massive Attack & Tracey Thorn"));
        assert_eq!(tags.album_artists, ["Massive Attack", "Tracey Thorn"]);
        assert!(!tags.compilation);
    }

    /// Сборник — по флагу или по album artist «Various Artists» (plan.md
    /// 13.2). Такого артиста нет: у альбома-сборника список артистов пуст.
    #[test]
    fn a_compilation_by_flag_or_by_various_artists() {
        let flagged = normalized(
            RawTags { album: Some("Hits".to_owned()), compilation: Some(true), ..RawTags::default() },
            &ArtistSplit::default(),
        );
        let various = normalized(
            RawTags {
                album: Some("Hits".to_owned()),
                album_artist: values(&["VARIOUS ARTISTS"]),
                ..RawTags::default()
            },
            &ArtistSplit::default(),
        );
        let russian = normalized(
            RawTags {
                album: Some("Хиты".to_owned()),
                album_artist: values(&["Разные исполнители"]),
                ..RawTags::default()
            },
            &ArtistSplit::default(),
        );
        let flag_off = normalized(
            RawTags {
                album: Some("Hits".to_owned()),
                album_artist: values(&["Various Artists"]),
                compilation: Some(false),
                ..RawTags::default()
            },
            &ArtistSplit::default(),
        );

        assert!(flagged.compilation && various.compilation && russian.compilation && flag_off.compilation);
        assert!(various.album_artists.is_empty() && russian.album_artists.is_empty());
        assert_eq!(various.album_artist.as_deref(), Some("VARIOUS ARTISTS"));
    }

    /// Сборник с настоящим album artist («DJ-микс») — артист остаётся.
    #[test]
    fn a_compilation_keeps_a_real_album_artist() {
        let raw = RawTags {
            album: Some("Mix".to_owned()),
            album_artist: values(&["Tiësto"]),
            compilation: Some(true),
            ..RawTags::default()
        };

        let tags = normalized(raw, &ArtistSplit::default());

        assert!(tags.compilation);
        assert_eq!(tags.album_artists, ["Tiësto"]);
    }
}
