//! Строки плейлиста из файла — треки фонотеки (D4c). Порядок — ответ автора:
//! абсолютный путь, путь относительно папки плейлиста, «исполнитель -
//! название» из `#EXTINF`. Ненайденная строка пропускается и считается.

use std::collections::HashMap;

use plinth_types::TrackId;
use unicode_normalization::UnicodeNormalization;

use super::m3u::PlaylistLine;
use crate::db::query::TrackRow;
use crate::text::normalize;

/// Между исполнителем и названием в `#EXTINF`: дефис, короткое и длинное тире.
const SEPARATORS: [&str; 3] = [" - ", " – ", " — "];

/// Что нашлось: треки в порядке файла и сколько строк не нашлось.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Matched {
    pub tracks: Vec<TrackId>,
    pub not_found: u32,
}

/// Находит строки [`PlaylistLine`] среди `rows` — треков, которые видны на
/// экранах. `folder` — папка файла плейлиста: от неё считаются
/// относительные пути; не знаем её — относительные ищутся только по названию.
pub fn match_lines(lines: &[PlaylistLine], rows: &[TrackRow], folder: Option<&str>) -> Matched {
    let library = Index::new(rows);
    let mut matched = Matched { tracks: Vec::with_capacity(lines.len()), not_found: 0 };
    for line in lines {
        match library.find(line, folder) {
            Some(track) => matched.tracks.push(track),
            None => matched.not_found = matched.not_found.saturating_add(1),
        }
    }
    matched
}

/// Треки по пути файла и по исполнителю с названием. Одинаковые ключи —
/// первый в порядке `rows`.
struct Index {
    by_path: HashMap<String, TrackId>,
    by_name: HashMap<(String, String), TrackId>,
}

impl Index {
    fn new(rows: &[TrackRow]) -> Self {
        let (mut by_path, mut by_name) = (HashMap::new(), HashMap::new());
        for row in rows {
            if let Some(uri) = &row.uri {
                by_path.entry(path_key(uri)).or_insert(row.id);
            }
            by_name.entry((normalize(&row.artist_credit), normalize(&row.title))).or_insert(row.id);
        }
        Self { by_path, by_name }
    }

    /// Сначала путь — абсолютный или от папки плейлиста, — потом название.
    fn find(&self, line: &PlaylistLine, folder: Option<&str>) -> Option<TrackId> {
        path(&line.location, folder)
            .and_then(|path| self.by_path.get(&path_key(&path)).copied())
            .or_else(|| line.title.as_deref().and_then(|title| self.by_title(title)))
    }

    /// «Исполнитель - название»: тире бывает и внутри исполнителя или
    /// названия, поэтому пробуется каждое, слева направо. Без тире — трек без
    /// исполнителя с таким названием.
    fn by_title(&self, text: &str) -> Option<TrackId> {
        let mut cuts: Vec<(usize, usize)> = SEPARATORS
            .iter()
            .flat_map(|separator| text.match_indices(separator).map(|(at, s)| (at, at + s.len())))
            .collect();
        cuts.sort_unstable();
        cuts.into_iter().map(|(end, start)| (&text[..end], &text[start..])).chain(std::iter::once(("", text))).find_map(
            |(artist, title)| {
                let key = (normalize(artist), normalize(title));
                if key.1.is_empty() { None } else { self.by_name.get(&key).copied() }
            },
        )
    }
}

/// Абсолютный путь строки: `file://` — без схемы и `%XX`, относительный —
/// от папки `folder`. Адрес в сети, диск Windows и относительный путь без
/// папки путём на устройстве не бывают — `None`.
fn path(location: &str, folder: Option<&str>) -> Option<String> {
    let location = location.replace('\\', "/");
    if let Some(rest) = location.get(..5).filter(|scheme| scheme.eq_ignore_ascii_case("file:")).map(|_| &location[5..])
    {
        let rest = rest.strip_prefix("//localhost").or_else(|| rest.strip_prefix("//")).unwrap_or(rest);
        return Some(clean(&percent_decoded(rest)));
    }
    if has_scheme(&location) || is_drive(&location) {
        return None;
    }
    if location.starts_with('/') { Some(clean(&location)) } else { Some(clean(&format!("{}/{location}", folder?))) }
}

/// Путь без `.`, `..` и пустых частей.
fn clean(path: &str) -> String {
    let mut parts: Vec<&str> = Vec::new();
    for part in path.split('/') {
        match part {
            "" | "." => {}
            ".." => {
                parts.pop();
            }
            _ => parts.push(part),
        }
    }
    format!("/{}", parts.join("/"))
}

/// Ключ пути: общее хранилище Android регистр не различает, а плейлист с
/// компьютера мог записать буквы из частей (NFD, macOS).
fn path_key(path: &str) -> String {
    path.nfc().collect::<String>().to_lowercase()
}

/// `http://`, `smb://` и другие адреса — не файлы устройства.
fn has_scheme(location: &str) -> bool {
    location.split_once("://").is_some_and(|(scheme, _)| {
        scheme.len() > 1
            && scheme.starts_with(|c: char| c.is_ascii_alphabetic())
            && scheme.chars().all(|c| c.is_ascii_alphanumeric() || "+-.".contains(c))
    })
}

/// `C:/Music` — путь на компьютере.
fn is_drive(location: &str) -> bool {
    let bytes = location.as_bytes();
    bytes.len() >= 2 && bytes[0].is_ascii_alphabetic() && bytes[1] == b':'
}

/// `%XX` — байты UTF-8; неправильная последовательность остаётся как есть.
fn percent_decoded(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        let hex = bytes.get(i + 1..i + 3).and_then(|h| std::str::from_utf8(h).ok());
        match (bytes[i], hex.and_then(|h| u8::from_str_radix(h, 16).ok())) {
            (b'%', Some(byte)) => {
                out.push(byte);
                i += 3;
            }
            (byte, _) => {
                out.push(byte);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

#[cfg(test)]
mod tests {
    use plinth_types::TrackId;
    use unicode_normalization::UnicodeNormalization;

    use super::{Matched, match_lines};
    use crate::db::query::TrackRow;
    use crate::playlist::m3u::PlaylistLine;
    use crate::playlist::testing::row;

    const MUSIC: &str = "/storage/emulated/0/Music";

    fn line(location: &str, title: Option<&str>) -> PlaylistLine {
        PlaylistLine { location: location.to_owned(), title: title.map(str::to_owned) }
    }

    /// Queen, Кино и Ёлка в `Music/`, трек без исполнителя в корне тома.
    fn library() -> Vec<TrackRow> {
        vec![
            row(Some(&format!("{MUSIC}/Queen/Bohemian Rhapsody.mp3")), "Queen", "Bohemian Rhapsody", Some(354)),
            row(
                Some(&format!("{MUSIC}/Queen/Hammer to Fall (Live) - 1985.mp3")),
                "Queen",
                "Hammer to Fall - Live",
                None,
            ),
            row(Some(&format!("{MUSIC}/Кино/Кукушка.mp3")), "Кино", "Кукушка", Some(400)),
            row(Some("/storage/emulated/0/intro.mp3"), "", "Intro", Some(5)),
            row(Some(&format!("{MUSIC}/Ёлка/Прованс.mp3")), "Ёлка", "Прованс", Some(200)),
            row(Some(&format!("{MUSIC}/Simon/The Boxer.mp3")), "Simon - Garfunkel", "The Boxer", None),
        ]
    }

    fn ids(rows: &[TrackRow], at: &[usize]) -> Vec<TrackId> {
        at.iter().map(|&i| rows[i].id).collect()
    }

    /// Путь сильнее названия: строка с путём одного трека и `#EXTINF` другого — это трек по пути.
    #[test]
    fn an_absolute_path_comes_first() {
        let rows = library();
        let lines = [line(&format!("{MUSIC}/Кино/Кукушка.mp3"), Some("Queen - Bohemian Rhapsody"))];

        let matched = match_lines(&lines, &rows, Some(MUSIC));

        assert_eq!(matched.tracks, ids(&rows, &[2]));
        assert_eq!(matched.not_found, 0);
    }

    /// Относительный путь — от папки плейлиста, со слешами Windows, `.` и `..`.
    #[test]
    fn a_relative_path_is_taken_from_the_playlist_folder() {
        let rows = library();
        let lines = [
            line("Queen/Bohemian Rhapsody.mp3", Some("Кино - Кукушка")),
            line(".\\Кино\\Кукушка.mp3", None),
            line("../intro.mp3", None),
            line("Queen//./../Queen/Bohemian Rhapsody.mp3", None),
        ];

        let matched = match_lines(&lines, &rows, Some(&format!("{MUSIC}/")));

        assert_eq!(matched.tracks, ids(&rows, &[0, 2, 3, 0]));
        assert_eq!(match_lines(&lines[..1], &rows, Some(MUSIC)).tracks, ids(&rows, &[0]));
    }

    /// Папка неизвестна — относительный путь не ищется, остаётся название.
    #[test]
    fn without_a_folder_a_relative_path_falls_back_to_the_title() {
        let rows = library();
        let lines =
            [line("Queen/Bohemian Rhapsody.mp3", Some("Кино - Кукушка")), line("Queen/Bohemian Rhapsody.mp3", None)];

        let matched = match_lines(&lines, &rows, None);

        assert_eq!(matched.tracks, ids(&rows, &[2]));
        assert_eq!(matched.not_found, 1);
    }

    /// `file://` — тот же абсолютный путь, со знаками в `%XX`.
    #[test]
    fn a_file_uri_is_an_absolute_path() {
        let rows = library();
        let lines = [
            line(
                "file:///storage/emulated/0/Music/%D0%9A%D0%B8%D0%BD%D0%BE/%D0%9A%D1%83%D0%BA%D1%83%D1%88%D0%BA%D0%B0.mp3",
                None,
            ),
            line("file://localhost/storage/emulated/0/Music/Queen/Bohemian%20Rhapsody.mp3", None),
            line("FILE:/storage/emulated/0/intro.mp3", None),
        ];

        assert_eq!(match_lines(&lines, &rows, None).tracks, ids(&rows, &[2, 0, 3]));
    }

    /// Общее хранилище Android регистр не различает, а плейлист с компьютера
    /// мог записать другой регистр и буквы из частей (NFD, macOS).
    #[test]
    fn paths_ignore_case_and_unicode_composition() {
        let rows = library();
        let lines = [
            line("/STORAGE/emulated/0/music/queen/bohemian rhapsody.MP3", None),
            line(&nfd(&format!("{MUSIC}/Ёлка/Прованс.mp3")), None),
        ];

        assert_eq!(match_lines(&lines, &rows, None).tracks, ids(&rows, &[0, 4]));
    }

    /// Путь не нашёлся — ищется «исполнитель - название» без регистра, знаков
    /// и диакритики; тире — любое из трёх.
    #[test]
    fn artist_and_title_come_last() {
        let rows = library();
        let lines = [
            line("D:\\Music\\queen.mp3", Some("QUEEN - bohemian rhapsody!")),
            line("/nowhere/k.mp3", Some("кино — кукушка")),
            line("/nowhere/q.mp3", Some("Queen – Bohemian Rhapsody")),
            line("http://radio/intro", Some("Intro")),
        ];

        assert_eq!(match_lines(&lines, &rows, Some(MUSIC)).tracks, ids(&rows, &[0, 2, 0, 3]));
    }

    /// Тире бывает и в названии, и в исполнителе: пробуется каждое место разделителя.
    #[test]
    fn a_dash_may_be_in_the_title_or_in_the_artist() {
        let rows = library();
        let lines = [
            line("/x.mp3", Some("Queen - Hammer to Fall - Live")),
            line("/y.mp3", Some("Simon - Garfunkel - The Boxer")),
        ];

        assert_eq!(match_lines(&lines, &rows, None).tracks, ids(&rows, &[1, 5]));
    }

    /// Ненайденные строки пропускаются и считаются; найденные — в порядке
    /// файла, повторы остаются.
    #[test]
    fn missing_lines_are_skipped_and_counted() {
        let lines = [
            line("/storage/emulated/0/intro.mp3", None),
            line("/storage/emulated/0/gone.mp3", Some("Nobody - Nothing")),
            line("http://radio/stream", None),
            line("/storage/emulated/0/intro.mp3", None),
            line("/x.mp3", Some("Queen")),
        ];
        let rows = library();

        let matched = match_lines(&lines, &rows, Some(MUSIC));

        assert_eq!(matched.tracks, ids(&rows, &[3, 3]));
        assert_eq!(matched.not_found, 3);
        assert_eq!(match_lines(&[], &rows, None), Matched { tracks: vec![], not_found: 0 });
    }

    /// Только видимые треки: файла нет среди строк — строка не найдена.
    #[test]
    fn only_the_given_rows_are_searched() {
        let rows = library();

        let matched = match_lines(&[line(&format!("{MUSIC}/Кино/Кукушка.mp3"), None)], &rows[..2], None);

        assert_eq!(matched.not_found, 1);
    }

    fn nfd(text: &str) -> String {
        text.nfd().collect()
    }
}
