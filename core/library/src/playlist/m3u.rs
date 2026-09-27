//! Разбор M3U, M3U8 и PLS и запись M3U8 (D4c).

use std::collections::BTreeMap;

use super::encoding::decode;
use crate::db::query::TrackRow;

/// Строка плейлиста из файла: где лежит трек и как он назван.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlaylistLine {
    /// Как записано в файле: путь, `file://` или адрес в сети.
    pub location: String,
    /// «Исполнитель - название» из `#EXTINF`, у PLS — из `TitleN`.
    pub title: Option<String>,
}

/// Строки плейлиста M3U, M3U8 или PLS в его порядке. Вид файла — по
/// содержимому: PLS начинается с `[playlist]`.
pub fn read(bytes: &[u8]) -> Vec<PlaylistLine> {
    let text = decode(bytes);
    let mut lines = text.split(['\n', '\r']).map(str::trim).filter(|line| !line.is_empty()).peekable();
    if lines.peek().is_some_and(|first| first.eq_ignore_ascii_case("[playlist]")) { pls(lines) } else { m3u(lines) }
}

fn m3u<'a>(lines: impl Iterator<Item = &'a str>) -> Vec<PlaylistLine> {
    let mut out = Vec::new();
    let mut title: Option<String> = None;
    for line in lines {
        if let Some(info) = strip_prefix_ignore_case(line, "#EXTINF:") {
            title = extinf_title(info);
        } else if !line.starts_with('#') {
            out.push(PlaylistLine { location: line.to_owned(), title: title.take() });
        }
    }
    out
}

/// Название из `#EXTINF` — после первой запятой вне кавычек; пустое — `None`.
fn extinf_title(info: &str) -> Option<String> {
    let mut quoted = false;
    let comma = info.char_indices().find_map(|(i, c)| match c {
        '"' => {
            quoted = !quoted;
            None
        }
        ',' if !quoted => Some(i),
        _ => None,
    })?;
    Some(info[comma + 1..].trim()).filter(|t| !t.is_empty()).map(str::to_owned)
}

fn pls<'a>(lines: impl Iterator<Item = &'a str>) -> Vec<PlaylistLine> {
    let mut entries: BTreeMap<u32, (Option<String>, Option<String>)> = BTreeMap::new();
    for (key, value) in lines.filter_map(|line| line.split_once('=')) {
        let (key, value) = (key.trim().to_ascii_lowercase(), value.trim());
        let numbered = |prefix: &str| key.strip_prefix(prefix).and_then(|n| n.parse::<u32>().ok());
        if let Some(n) = numbered("file") {
            entries.entry(n).or_default().0 = Some(value.to_owned());
        } else if let Some(n) = numbered("title") {
            entries.entry(n).or_default().1 = Some(value.to_owned()).filter(|t| !t.is_empty());
        }
    }
    entries.into_values().filter_map(|(file, title)| file.map(|location| PlaylistLine { location, title })).collect()
}

fn strip_prefix_ignore_case<'a>(line: &'a str, prefix: &str) -> Option<&'a str> {
    let head = line.get(..prefix.len())?;
    head.eq_ignore_ascii_case(prefix).then(|| &line[prefix.len()..])
}

/// Плейлист в M3U8: UTF-8, `#EXTM3U`, у каждого трека `#EXTINF` с длительностью в
/// секундах и «исполнитель - название» и абсолютный путь. Строки без файла пропускаются.
pub fn write(rows: &[TrackRow]) -> String {
    let mut out = String::from("#EXTM3U\n");
    for row in rows {
        let Some(path) = row.uri.as_deref() else { continue };
        let seconds = row.duration.map_or(-1, |d| i64::try_from(d.as_secs()).unwrap_or(i64::MAX));
        let (artist, title) = (one_line(&row.artist_credit), one_line(&row.title));
        let name = if artist.is_empty() { title } else { format!("{artist} - {title}") };
        out.push_str(&format!("#EXTINF:{seconds},{name}\n{path}\n"));
    }
    out
}

/// Перевод строки в теге сломал бы файл — становится пробелом.
fn one_line(text: &str) -> String {
    text.split(['\r', '\n']).filter(|part| !part.is_empty()).collect::<Vec<_>>().join(" ")
}

#[cfg(test)]
mod tests {
    use super::{PlaylistLine, read, write};
    use crate::playlist::testing::row;

    fn line(location: &str, title: Option<&str>) -> PlaylistLine {
        PlaylistLine { location: location.to_owned(), title: title.map(str::to_owned) }
    }

    #[test]
    fn plain_m3u_is_one_path_per_line() {
        let text = "/Music/a.mp3\r\n\r\n  Rock\\b.flac  \r\n# a comment\r\nhttp://radio/stream\r\n";

        assert_eq!(
            read(text.as_bytes()),
            [line("/Music/a.mp3", None), line("Rock\\b.flac", None), line("http://radio/stream", None)]
        );
    }

    /// `#EXTINF` называет следующий путь и только его; другие директивы не мешают.
    #[test]
    fn extinf_names_the_next_path() {
        let text = "#EXTM3U\n#EXTINF:354,Queen - Bohemian Rhapsody\n#EXTALB:A Night at the Opera\n\
                    /Music/Queen/05.mp3\n/Music/untitled.mp3\n#EXTINF:-1,\n/Music/blank.mp3\n";

        assert_eq!(
            read(text.as_bytes()),
            [
                line("/Music/Queen/05.mp3", Some("Queen - Bohemian Rhapsody")),
                line("/Music/untitled.mp3", None),
                line("/Music/blank.mp3", None)
            ]
        );
    }

    /// Название — после первой запятой вне кавычек: запятые бывают в
    /// атрибутах и в самом названии.
    #[test]
    fn extinf_title_follows_the_first_comma_outside_quotes() {
        let text = "#EXTINF:200 tvg-name=\"Rock, Live\" group=\"x\",Crosby, Stills & Nash - Wooden Ships\n/a.mp3\n\
                    #extinf:12.5, Artist - Song \n/b.mp3\n#EXTINF:100\n/c.mp3\n";

        assert_eq!(
            read(text.as_bytes()),
            [
                line("/a.mp3", Some("Crosby, Stills & Nash - Wooden Ships")),
                line("/b.mp3", Some("Artist - Song")),
                line("/c.mp3", None)
            ]
        );
    }

    #[test]
    fn classic_mac_line_breaks_split_lines_too() {
        assert_eq!(read(b"/a.mp3\r/b.mp3"), [line("/a.mp3", None), line("/b.mp3", None)]);
    }

    /// M3U без метки кодировки в CP1251 читается кириллицей.
    #[test]
    fn a_cp1251_m3u_reads_as_cyrillic() {
        let bytes = b"#EXTINF:200,\xca\xe8\xed\xee - \xca\xf3\xea\xf3\xf8\xea\xe0\r\n\xca\xe8\xed\xee\\01.mp3\r\n";

        assert_eq!(read(bytes), [line("Кино\\01.mp3", Some("Кино - Кукушка"))]);
    }

    /// PLS: записи по номерам, а не по порядку строк; ключи без учёта регистра;
    /// номер без файла пропускается.
    #[test]
    fn pls_goes_by_entry_numbers() {
        let text = "\u{feff}[Playlist]\r\nNumberOfEntries=4\r\nFile2=/Music/b.mp3\r\nTitle2=Artist - B\r\n\
                    Length2=200\r\nfile1=/Music/a.mp3\r\nTitle3=Orphan\r\nFILE10=C:\\Music\\j.mp3\r\n\
                    Title10=J = K\r\nVersion=2\r\n";

        assert_eq!(
            read(text.as_bytes()),
            [
                line("/Music/a.mp3", None),
                line("/Music/b.mp3", Some("Artist - B")),
                line("C:\\Music\\j.mp3", Some("J = K"))
            ]
        );
    }

    /// Файл без `[playlist]` — M3U, даже если строки похожи на PLS.
    #[test]
    fn pls_needs_its_header() {
        assert_eq!(read(b"File1=/a.mp3\n"), [line("File1=/a.mp3", None)]);
    }

    #[test]
    fn m3u8_has_a_header_extinf_and_absolute_paths() {
        let rows = [
            row(Some("/storage/emulated/0/Music/Queen/05.mp3"), "Queen", "Bohemian Rhapsody", Some(354)),
            row(Some("/storage/emulated/0/Music/intro.mp3"), "", "Intro", None),
            row(None, "Nobody", "Stream", Some(10)),
        ];

        assert_eq!(
            write(&rows),
            "#EXTM3U\n\
             #EXTINF:354,Queen - Bohemian Rhapsody\n\
             /storage/emulated/0/Music/Queen/05.mp3\n\
             #EXTINF:-1,Intro\n\
             /storage/emulated/0/Music/intro.mp3\n"
        );
    }

    /// Перевод строки в теге сломал бы файл — в `#EXTINF` он становится пробелом.
    #[test]
    fn line_breaks_in_tags_do_not_break_the_file() {
        let rows = [row(Some("/a.mp3"), "Two\r\nLines", "Song\nName", Some(61))];

        assert_eq!(write(&rows), "#EXTM3U\n#EXTINF:61,Two Lines - Song Name\n/a.mp3\n");
    }

    #[test]
    fn written_playlist_reads_back() {
        let rows = [
            row(Some("/storage/emulated/0/Music/Ёлка/Прованс.mp3"), "Ёлка", "Прованс", Some(200)),
            row(Some("/storage/emulated/0/Music/a, b.mp3"), "A, B", "C - D", Some(1)),
        ];

        assert_eq!(
            read(write(&rows).as_bytes()),
            [
                line("/storage/emulated/0/Music/Ёлка/Прованс.mp3", Some("Ёлка - Прованс")),
                line("/storage/emulated/0/Music/a, b.mp3", Some("A, B - C - D"))
            ]
        );
    }
}
