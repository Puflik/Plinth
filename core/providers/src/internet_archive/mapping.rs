//! Файлы элемента → треки (E2.2, ответ автора): трек — исходный файл, его
//! производные (MP3, Ogg — поле `original`) — варианты того же трека.
//! Исходник, который не играется (Shorten, закрытый WAV), остаётся якорем:
//! его название и номер годятся, звук — из производных. Трек без
//! единого проигрываемого варианта не отдаётся.

use std::collections::{BTreeMap, HashMap};

use super::metadata::{File, Metadata};
use crate::model::{ExternalId, RemoteTrack, SourceOption};

/// Сколько шагов по цепочке `original` — защита от петель в данных.
const CHAIN: usize = 8;

/// Треки элемента `identifier` по номеру, без номера — в конце, по имени
/// исходника. ID трека — `{identifier}/{исходник}`, варианта —
/// `{identifier}/{файл}`.
pub(super) fn tracks(metadata: &Metadata, identifier: &str) -> Vec<RemoteTrack> {
    let by_name: HashMap<&str, &File> = metadata.files.iter().map(|file| (file.name.as_str(), file)).collect();
    let mut groups: BTreeMap<&str, Vec<&File>> = BTreeMap::new();
    for file in metadata.files.iter().filter(|file| file.is_playable()) {
        groups.entry(anchor(file, &by_name)).or_default().push(file);
    }
    let mut tracks: Vec<RemoteTrack> = groups
        .into_iter()
        .filter_map(|(anchor, variants)| track(metadata, identifier, by_name.get(anchor).copied(), anchor, variants))
        .collect();
    tracks.sort_by_key(|track| (track.number.is_none(), track.number));
    tracks
}

/// Исходник файла: вверх по `original`, пока файл есть в элементе.
fn anchor<'a>(file: &'a File, by_name: &HashMap<&str, &'a File>) -> &'a str {
    let mut current = file;
    for _ in 0..CHAIN {
        match current.original.as_deref().and_then(|original| by_name.get(original)) {
            Some(parent) if parent.name != current.name => current = parent,
            _ => break,
        }
    }
    &current.name
}

fn track(
    metadata: &Metadata,
    identifier: &str,
    anchor_file: Option<&File>,
    anchor: &str,
    variants: Vec<&File>,
) -> Option<RemoteTrack> {
    let mut sources: Vec<(SourceOption, &File)> = variants
        .into_iter()
        .filter_map(|file| {
            let (format, _) = file.format?;
            let external = ExternalId::new(&format!("{identifier}/{}", file.name)).ok()?;
            Some((SourceOption { external, format, bitrate: file.bitrate }, file))
        })
        .collect();
    if sources.is_empty() {
        return None;
    }
    sources.sort_by(|(a, _), (b, _)| b.tier().cmp(&a.tier()).then_with(|| a.external.cmp(&b.external)));
    // Описание — сначала у исходника, потом у вариантов; длина — наоборот:
    // у исходника она бывает неверной (Shorten, битый MP3).
    let described: Vec<&File> = anchor_file.into_iter().chain(sources.iter().map(|(_, file)| *file)).collect();
    let first = |pick: fn(&File) -> Option<String>| described.iter().find_map(|file| pick(file));
    let length = sources.iter().map(|(_, file)| *file).chain(anchor_file).find_map(|file| file.length);
    Some(RemoteTrack {
        external: ExternalId::new(&format!("{identifier}/{anchor}")).ok()?,
        title: first(|file| file.title.clone()).unwrap_or_else(|| stem(anchor)),
        artist: first(|file| file.artist.clone()).or_else(|| metadata.item.creator.clone()),
        album: first(|file| file.album.clone()).or_else(|| metadata.item.title.clone()),
        disc: None,
        number: described.iter().find_map(|file| file.track),
        year: metadata.item.year,
        duration: length,
        mbid: described.iter().find_map(|file| file.mbid),
        sources: sources.into_iter().map(|(source, _)| source).collect(),
    })
}

/// Имя файла без каталога и расширения — название, когда другого нет.
fn stem(name: &str) -> String {
    let file = name.rsplit('/').next().unwrap_or(name);
    file.rsplit_once('.').map_or(file, |(stem, _)| stem).to_owned()
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use plinth_types::{Bitrate, Format};

    use super::{stem, tracks};
    use crate::config::ProviderConfig;
    use crate::internet_archive::CONFIG;
    use crate::internet_archive::metadata::{Metadata, parse};

    fn metadata(body: &str) -> Metadata {
        parse(body.as_bytes(), &ProviderConfig::parse(CONFIG).unwrap()).unwrap()
    }

    fn externals(track: &crate::model::RemoteTrack) -> Vec<&str> {
        track.sources.iter().map(|source| source.external.as_str()).collect()
    }

    #[test]
    fn original_with_derivatives_is_one_track() {
        let item = metadata(
            r#"{ "metadata": { "title": "Vice Versa", "creator": "Label", "year": "2013" }, "files": [
                { "name": "01.mp3", "format": "VBR MP3", "title": "Animosity", "artist": "Intoxicated Piano",
                  "track": "01", "length": "150.77", "bitrate": "192" },
                { "name": "01.ogg", "format": "Ogg Vorbis", "original": "01.mp3", "length": "141.06" },
                { "name": "01.png", "format": "PNG", "original": "01.mp3" },
                { "name": "MIXG031.zip", "format": "ZIP" }
            ] }"#,
        );

        let found = tracks(&item, "MIXG031");

        assert_eq!(found.len(), 1);
        let track = &found[0];
        assert_eq!(track.external.as_str(), "MIXG031/01.mp3");
        assert_eq!(track.title, "Animosity");
        assert_eq!(track.artist.as_deref(), Some("Intoxicated Piano"));
        assert_eq!(track.album.as_deref(), Some("Vice Versa"), "no album on files — the item's title");
        assert_eq!((track.number, track.year), (Some(1), Some(2013)));
        assert_eq!(track.duration, Some(Duration::from_secs_f64(150.77)));
        assert_eq!(externals(track), ["MIXG031/01.mp3", "MIXG031/01.ogg"]);
        assert_eq!(track.sources[0].bitrate, Some(Bitrate::kbps(192)));
    }

    #[test]
    fn unplayable_original_anchors_its_derivatives() {
        let item = metadata(
            r#"{ "metadata": { "creator": "Grateful Dead" }, "files": [
                { "name": "d1t01.shn", "format": "Shorten", "title": "Minglewood Blues", "track": "1",
                  "length": "232.58", "private": "true" },
                { "name": "d1t01.mp3", "format": "VBR MP3", "original": "d1t01.shn", "length": "06:21", "bitrate": "185" },
                { "name": "d1t01.ogg", "format": "Ogg Vorbis", "original": "d1t01.shn", "length": "381.1" },
                { "name": "d1t01_64kb.mp3", "format": "64Kbps MP3", "original": "d1t01.mp3", "private": "true" }
            ] }"#,
        );

        let found = tracks(&item, "gd77");

        assert_eq!(found.len(), 1);
        let track = &found[0];
        assert_eq!(track.external.as_str(), "gd77/d1t01.shn");
        assert_eq!(track.title, "Minglewood Blues");
        assert_eq!(track.artist.as_deref(), Some("Grateful Dead"));
        assert_eq!(track.duration, Some(Duration::from_secs(381)), "a derivative's length beats the original's");
        assert_eq!(externals(track), ["gd77/d1t01.mp3", "gd77/d1t01.ogg"], "private files are not sources");
    }

    #[test]
    fn lossless_first_then_by_name() {
        let item = metadata(
            r#"{ "files": [
                { "name": "a.mp3", "format": "VBR MP3", "original": "z.flac", "bitrate": "233" },
                { "name": "z.flac", "format": "24bit Flac", "title": "OH DOCTOR" },
                { "name": "b.ogg", "format": "Ogg Vorbis", "original": "z.flac" }
            ] }"#,
        );

        let track = &tracks(&item, "78_oh")[0];

        assert_eq!(externals(track), ["78_oh/z.flac", "78_oh/a.mp3", "78_oh/b.ogg"]);
        assert_eq!(track.sources[0].format, Format::Flac);
    }

    #[test]
    fn broken_length_of_the_original_falls_back_to_a_derivative() {
        let item = metadata(
            r#"{ "files": [
                { "name": "1.mp3", "format": "VBR MP3", "length": "374491.55" },
                { "name": "1.ogg", "format": "Ogg Vorbis", "original": "1.mp3", "length": "1209.05" }
            ] }"#,
        );

        assert_eq!(tracks(&item, "b")[0].duration, Some(Duration::from_secs_f64(1209.05)));
    }

    #[test]
    fn tracks_go_by_number_then_by_name() {
        let item = metadata(
            r#"{ "files": [
                { "name": "z.mp3", "format": "VBR MP3" },
                { "name": "a.mp3", "format": "VBR MP3", "track": "10" },
                { "name": "0.mp3", "format": "VBR MP3" },
                { "name": "b.mp3", "format": "VBR MP3", "track": "2" }
            ] }"#,
        );

        let names: Vec<String> = tracks(&item, "x").into_iter().map(|track| track.title).collect();

        assert_eq!(names, ["b", "a", "0", "z"]);
    }

    #[test]
    fn nothing_playable_is_no_tracks() {
        let item = metadata(
            r#"{ "files": [
                { "name": "a.wav", "format": "WAVE", "private": "true" },
                { "name": "a.shn", "format": "Shorten" },
                { "name": "cover.jpg", "format": "JPEG" }
            ] }"#,
        );

        assert!(tracks(&item, "x").is_empty());
    }

    #[test]
    fn loops_in_originals_do_not_hang() {
        let item = metadata(
            r#"{ "files": [
                { "name": "a.mp3", "format": "VBR MP3", "original": "b.mp3" },
                { "name": "b.mp3", "format": "VBR MP3", "original": "a.mp3" },
                { "name": "c.mp3", "format": "VBR MP3", "original": "c.mp3" }
            ] }"#,
        );

        let found = tracks(&item, "x");

        assert_eq!(found.iter().map(|track| track.sources.len()).sum::<usize>(), 3);
    }

    #[test]
    fn stems() {
        assert_eq!(stem("disc 1/01 Кино.mp3"), "01 Кино");
        assert_eq!(stem("noext"), "noext");
        assert_eq!(stem("\"OH DOCTOR\" - H. Pearl.flac"), "\"OH DOCTOR\" - H. Pearl");
    }
}
