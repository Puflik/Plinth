//! Метаданные элемента (`/metadata/{id}`, E2.2): описание элемента и его
//! файлы. Все поля необязательны; файл без имени пропускается, остальное
//! становится `None`. Несуществующий элемент archive.org отдаёт как `{}` со
//! статусом 200 — это `Unavailable`, а не пустой элемент.

use std::time::Duration;

use plinth_types::{Bitrate, CoreError, Format, Mbid};
use serde_json::Value;

use super::values;
use crate::config::ProviderConfig;
use crate::json::{get, text, text_at};

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub(super) struct Item {
    pub title: Option<String>,
    pub creator: Option<String>,
    pub year: Option<u16>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct File {
    pub name: String,
    /// Наш формат по метке; `None` — не звук или звук, который не играем.
    pub format: Option<(Format, Option<Bitrate>)>,
    /// Из какого файла сделан этот (у производных).
    pub original: Option<String>,
    /// Закрыт для скачивания: метаданные годятся, играть нельзя.
    pub private: bool,
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub track: Option<u16>,
    pub length: Option<Duration>,
    pub bitrate: Option<Bitrate>,
    pub mbid: Option<Mbid>,
}

impl File {
    /// Можно отдать плееру.
    pub fn is_playable(&self) -> bool {
        self.format.is_some() && !self.private
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct Metadata {
    pub item: Item,
    pub files: Vec<File>,
}

pub(super) fn parse(body: &[u8], config: &ProviderConfig) -> Result<Metadata, CoreError> {
    let doc: Value = serde_json::from_slice(body).map_err(|_| CoreError::parse("archive.org metadata: not json"))?;
    let Value::Object(fields) = &doc else {
        return Err(CoreError::parse("archive.org metadata: not an object"));
    };
    if fields.is_empty() {
        return Err(CoreError::unavailable("archive.org: no such item"));
    }
    let names = Names::of(config)?;
    let item = get(&doc, config.field("item")?).map_or_else(Item::default, |item| Item {
        title: text_at(item, names.title),
        creator: text_at(item, names.creator),
        year: text_at(item, names.year).or_else(|| text_at(item, names.date)).as_deref().and_then(values::year),
    });
    let files = match get(&doc, config.field("files")?) {
        Some(Value::Array(files)) => files.iter().filter_map(|file| names.file(file, config)).collect(),
        _ => Vec::new(),
    };
    Ok(Metadata { item, files })
}

/// Имена полей из конфига — один раз на ответ.
struct Names<'a> {
    title: &'a str,
    creator: &'a str,
    year: &'a str,
    date: &'a str,
    file_name: &'a str,
    file_format: &'a str,
    file_original: &'a str,
    file_private: &'a str,
    file_title: &'a str,
    file_artist: &'a str,
    file_album: &'a str,
    file_track: &'a str,
    file_length: &'a str,
    file_bitrate: &'a str,
    file_ids: &'a str,
    private: &'a str,
    mbid: &'a str,
}

impl<'a> Names<'a> {
    fn of(config: &'a ProviderConfig) -> Result<Self, CoreError> {
        Ok(Self {
            title: config.field("title")?,
            creator: config.field("creator")?,
            year: config.field("year")?,
            date: config.field("date")?,
            file_name: config.field("file_name")?,
            file_format: config.field("file_format")?,
            file_original: config.field("file_original")?,
            file_private: config.field("file_private")?,
            file_title: config.field("file_title")?,
            file_artist: config.field("file_artist")?,
            file_album: config.field("file_album")?,
            file_track: config.field("file_track")?,
            file_length: config.field("file_length")?,
            file_bitrate: config.field("file_bitrate")?,
            file_ids: config.field("file_ids")?,
            private: config.pattern("private")?,
            mbid: config.pattern("mbid")?,
        })
    }

    fn file(&self, file: &Value, config: &ProviderConfig) -> Option<File> {
        let name = text_at(file, self.file_name)?;
        let format = text_at(file, self.file_format).and_then(|label| config.format(&label));
        Some(File {
            name,
            format,
            original: text_at(file, self.file_original),
            private: text_at(file, self.file_private).is_some_and(|flag| flag == self.private),
            title: text_at(file, self.file_title),
            artist: text_at(file, self.file_artist),
            album: text_at(file, self.file_album),
            track: text_at(file, self.file_track).as_deref().and_then(values::track_number),
            length: text_at(file, self.file_length).as_deref().and_then(values::duration),
            bitrate: format
                .and_then(|(_, fixed)| fixed)
                .or_else(|| text_at(file, self.file_bitrate).as_deref().and_then(values::bitrate)),
            mbid: self.mbid(file),
        })
    }

    /// `external-identifier` — строка или массив `urn:…`; MBID записи — тот,
    /// что с префиксом из конфига.
    fn mbid(&self, file: &Value) -> Option<Mbid> {
        let ids = get(file, self.file_ids)?;
        let all: Vec<&Value> = match ids {
            Value::Array(items) => items.iter().collect(),
            single => vec![single],
        };
        all.into_iter().filter_map(text).find_map(|id| id.strip_prefix(self.mbid)?.parse().ok())
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use plinth_types::{Bitrate, CoreError, Format};

    use super::{Item, parse};
    use crate::config::ProviderConfig;
    use crate::internet_archive::CONFIG;

    fn config() -> ProviderConfig {
        ProviderConfig::parse(CONFIG).unwrap()
    }

    #[test]
    fn empty_object_is_a_missing_item() {
        assert!(matches!(parse(b"{}", &config()), Err(CoreError::Unavailable { .. })));
    }

    #[test]
    fn garbage_is_a_parse_error() {
        for bad in [&b"<html>Service Unavailable</html>"[..], b"[1, 2]", b"\"text\"", b""] {
            assert!(matches!(parse(bad, &config()), Err(CoreError::Parse { .. })), "{bad:?}");
        }
    }

    #[test]
    fn item_with_nothing_but_files_survives() {
        let metadata =
            parse(br#"{ "files": [ { "name": "a.mp3", "format": "VBR MP3" }, { "format": "Flac" } ] }"#, &config())
                .unwrap();

        assert_eq!(metadata.item, Item::default());
        assert_eq!(metadata.files.len(), 1, "a file without a name is skipped");
        assert_eq!(metadata.files[0].format, Some((Format::Mp3, None)));
        assert_eq!(metadata.files[0].title, None);
    }

    #[test]
    fn fields_of_items_and_files() {
        let body = br#"{
            "metadata": { "title": "Show", "creator": ["A", "B"], "date": "2005-02-24 00:00:00" },
            "files": [
                { "name": "t01.flac", "format": "24bit Flac", "title": "One", "artist": "Band", "creator": "Uploader",
                  "album": "Live", "track": "1/14", "length": "374491.55", "private": "true",
                  "external-identifier": ["urn:acoustid:x", "urn:mb_recording_id:b1a9c0e9-d987-4042-ae91-78d6a3267d69"] },
                { "name": "t01_64kb.mp3", "format": "64Kbps MP3", "original": "t01.flac", "length": "06:21", "bitrate": "63" },
                { "name": "t01.ogg", "format": "Ogg Vorbis", "bitrate": "abc", "track": 7, "external-identifier": "urn:acoustid:y" },
                { "name": "t01.png", "format": "PNG", "private": "false" }
            ]
        }"#;

        let metadata = parse(body, &config()).unwrap();
        let [flac, mp3, ogg, png] = &metadata.files[..] else { panic!("{:?}", metadata.files) };

        assert_eq!(
            metadata.item,
            Item { title: Some("Show".to_owned()), creator: Some("A, B".to_owned()), year: Some(2005) }
        );
        assert_eq!(flac.format, Some((Format::Flac, None)));
        assert!(flac.private && !flac.is_playable());
        assert_eq!(flac.artist.as_deref(), Some("Band"), "artist goes before creator");
        assert_eq!((flac.track, flac.length), (Some(1), None));
        assert_eq!(flac.mbid.map(|m| m.to_string()).as_deref(), Some("b1a9c0e9-d987-4042-ae91-78d6a3267d69"));
        assert_eq!(mp3.original.as_deref(), Some("t01.flac"));
        assert_eq!(mp3.bitrate, Some(Bitrate::kbps(64)), "the label's bitrate wins");
        assert_eq!(mp3.length, Some(Duration::from_secs(381)));
        assert!(mp3.is_playable());
        assert_eq!((ogg.bitrate, ogg.track, ogg.mbid), (None, Some(7), None));
        assert_eq!(png.format, None);
        assert!(!png.private && !png.is_playable());
    }
}
