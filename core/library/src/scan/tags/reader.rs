//! Теги и свойства звука через `lofty` (ADR 0009): ID3v2 (и ID3v1, APE),
//! Vorbis Comments во FLAC, Ogg и Opus, атомы iTunes в MP4.

use std::fs::File;
use std::io::BufReader;
use std::path::Path;

use lofty::config::ParseOptions;
use lofty::file::{FileType, TaggedFile};
use lofty::mp4::{Mp4Codec, Mp4File};
use lofty::prelude::*;
use lofty::probe::Probe;
use lofty::properties::FileProperties;
use lofty::tag::Tag;
use plinth_types::{Bitrate, CoreError, Format};

use super::RawTags;
use crate::model::AudioSpec;

/// Теги файла как есть. Картинки не читаются: скан держит теги всех новых
/// файлов в памяти до записи.
pub fn read_raw(path: &Path) -> Result<RawTags, CoreError> {
    let options = ParseOptions::new().read_cover_art(false);
    let (tagged, format) = open(path, options)?;
    let props = tagged.properties();
    let mut raw = RawTags {
        duration: Some(props.duration()).filter(|d| !d.is_zero()),
        audio: Some(audio(format, props)),
        ..RawTags::default()
    };
    // Главный тег формата первым: ID3v2 раньше ID3v1, где поля обрезаны до 30 байт.
    let primary = tagged.primary_tag_type();
    let tags = tagged.primary_tag().into_iter().chain(tagged.tags().iter().filter(|t| t.tag_type() != primary));
    for tag in tags {
        fill(&mut raw, tag);
    }
    Ok(raw)
}

/// Разобранный файл и формат звука. Формат — по содержимому, расширение —
/// запасной путь: `.ogg` бывает и с Opus внутри.
pub(super) fn open(path: &Path, options: ParseOptions) -> Result<(TaggedFile, Format), CoreError> {
    let file = File::open(path).map_err(|e| CoreError::storage(format!("tags: {e}")))?;
    let mut probe =
        Probe::new(BufReader::new(file)).guess_file_type().map_err(|e| CoreError::storage(format!("tags: {e}")))?;
    if probe.file_type().is_none()
        && let Some(by_name) = path.extension().and_then(FileType::from_ext)
    {
        probe = probe.set_file_type(by_name);
    }
    if probe.file_type() == Some(FileType::Mp4) {
        let mp4 = Mp4File::read_from(&mut probe.into_inner(), options).map_err(|e| unreadable(&e))?;
        let format = match mp4.properties().codec() {
            Some(Mp4Codec::ALAC) => Format::Alac,
            Some(Mp4Codec::FLAC) => Format::Flac,
            Some(Mp4Codec::MP3) => Format::Mp3,
            _ => Format::Aac,
        };
        return Ok((mp4.into(), format));
    }
    let tagged = probe.options(options).read().map_err(|e| unreadable(&e))?;
    let format = match tagged.file_type() {
        FileType::Aac => Format::Aac,
        FileType::Aiff => Format::Aiff,
        FileType::Flac => Format::Flac,
        FileType::Mpeg => Format::Mp3,
        FileType::Opus => Format::Opus,
        FileType::Vorbis => Format::Vorbis,
        FileType::Wav => Format::Wav,
        _ => Format::Other,
    };
    Ok((tagged, format))
}

fn unreadable(error: &lofty::error::FileParseError) -> CoreError {
    CoreError::parse(format!("tags: {error}"))
}

fn audio(format: Format, props: &FileProperties) -> AudioSpec {
    AudioSpec {
        format,
        bitrate: props.audio_bitrate().filter(|b| *b > 0).map(Bitrate::kbps),
        sample_rate_hz: props.sample_rate().filter(|r| *r > 0),
        bit_depth: props.bit_depth().filter(|d| *d > 0),
    }
}

/// Дописывает из тега то, чего ещё нет.
fn fill(raw: &mut RawTags, tag: &Tag) {
    let strings = |key| tag.get_strings(key).map(str::to_owned).collect::<Vec<_>>();
    set(&mut raw.title, tag.title().map(|v| v.into_owned()));
    set_all(&mut raw.artist, strings(ItemKey::TrackArtist));
    set_all(&mut raw.artists, strings(ItemKey::TrackArtists));
    set(&mut raw.album, tag.album().map(|v| v.into_owned()));
    set_all(&mut raw.album_artist, strings(ItemKey::AlbumArtist));
    set_all(&mut raw.sort_artist, strings(ItemKey::TrackArtistSortOrder));
    set_all(&mut raw.sort_album_artist, strings(ItemKey::AlbumArtistSortOrder));
    set(&mut raw.track, tag.track());
    set(&mut raw.disc, tag.disk());
    set(&mut raw.disc_total, tag.disk_total());
    set(&mut raw.year, tag.date().map(|date| u32::from(date.year)));
    for key in [ItemKey::Year, ItemKey::ReleaseDate, ItemKey::OriginalReleaseDate] {
        set(&mut raw.year, tag.get_string(key).and_then(year));
    }
    set(&mut raw.compilation, tag.get_string(ItemKey::FlagCompilation).and_then(flag));
}

fn set<T>(slot: &mut Option<T>, value: Option<T>) {
    if slot.is_none() {
        *slot = value;
    }
}

fn set_all(slot: &mut Vec<String>, values: Vec<String>) {
    if slot.iter().all(|v| v.trim().is_empty()) {
        *slot = values;
    }
}

/// Год из начала даты: «1997», «1997-05-21».
fn year(date: &str) -> Option<u32> {
    date.trim().get(..4).and_then(|y| y.parse().ok())
}

fn flag(value: &str) -> Option<bool> {
    match value.trim() {
        "1" => Some(true),
        "0" => Some(false),
        other if other.eq_ignore_ascii_case("true") => Some(true),
        other if other.eq_ignore_ascii_case("false") => Some(false),
        _ => None,
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use std::path::{Path, PathBuf};
    use std::time::Duration;

    use lofty::config::WriteOptions;
    use lofty::prelude::*;
    use lofty::tag::{ItemValue, TagItem};
    use plinth_types::{CoreError, DeviceId, Format};

    use super::read_raw;
    use crate::scan::tags::RawTags;

    /// Фикстуры приложения (`tools/make_tag_fixtures.py`,
    /// `tools/make_format_fixtures.py`): их записал ffmpeg, а не lofty.
    pub(crate) fn asset(name: &str) -> PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR")).join("../../app/src/androidTest/assets").join(name)
    }

    /// Копия фикстуры во временной папке — чтобы дописать ей теги.
    pub(crate) struct Copy(pub PathBuf);

    impl Copy {
        pub(crate) fn of(name: &str) -> Self {
            let dir = std::env::temp_dir().join(format!("plinth-tags-{}", DeviceId::new()));
            std::fs::create_dir_all(&dir).unwrap();
            let path = dir.join(Path::new(name).file_name().unwrap());
            std::fs::copy(asset(name), &path).unwrap();
            Self(path)
        }

        /// Дописывает значения в главный тег файла и сохраняет.
        pub(crate) fn tag(self, items: &[(ItemKey, &str)]) -> Self {
            let mut file = lofty::read_from_path(&self.0).unwrap();
            let kind = file.primary_tag_type();
            if file.primary_tag().is_none() {
                file.insert_tag(lofty::tag::Tag::new(kind));
            }
            let tag = file.primary_tag_mut().unwrap();
            for (key, value) in items {
                tag.push(TagItem::new(*key, ItemValue::Text((*value).to_owned())));
            }
            tag.save_to_path(&self.0, WriteOptions::default()).unwrap();
            self
        }
    }

    impl Drop for Copy {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(self.0.parent().unwrap());
        }
    }

    fn strings(values: &[&str]) -> Vec<String> {
        values.iter().map(|v| (*v).to_owned()).collect()
    }

    /// ID3v2.3 в MP3, кириллица в UTF-16.
    #[test]
    fn id3v2_in_mp3() {
        let raw = read_raw(&asset("tags/plinth-mp3.mp3")).unwrap();

        assert_eq!(raw.title.as_deref(), Some("Тишина"));
        assert_eq!(raw.artist, strings(&["Plinth"]));
        assert_eq!(raw.album.as_deref(), Some("Fixtures"));
        assert_eq!(raw.album_artist, strings(&["Plinth Various"]));
        assert_eq!((raw.track, raw.disc), (Some(3), Some(2)));
        let audio = raw.audio.unwrap();
        assert_eq!((audio.format, audio.sample_rate_hz), (Format::Mp3, Some(44_100)));
        assert!(audio.bitrate.is_some_and(|b| (30..=34).contains(&b.as_kbps())), "{:?}", audio.bitrate);
        assert!(raw.duration.is_some_and(|d| d.abs_diff(Duration::from_secs(1)) < Duration::from_millis(100)));
    }

    #[test]
    fn vorbis_comments_in_flac() {
        let raw = read_raw(&asset("tags/plinth-flac.flac")).unwrap();

        assert_eq!(raw.title.as_deref(), Some("FLAC Silence"));
        assert_eq!(raw.artist, strings(&["Plinth"]));
        assert_eq!(raw.album_artist, strings(&["Plinth Various"]));
        assert_eq!((raw.track, raw.disc), (Some(1), Some(1)));
        let audio = raw.audio.unwrap();
        assert_eq!((audio.format, audio.sample_rate_hz, audio.bit_depth), (Format::Flac, Some(44_100), Some(16)));
    }

    #[test]
    fn mp4_atoms_in_m4a() {
        let raw = read_raw(&asset("tags/plinth-m4a.m4a")).unwrap();

        assert_eq!(raw.title.as_deref(), Some("M4A Silence"));
        assert_eq!(raw.artist, strings(&["The Plinth"]));
        assert_eq!(raw.album.as_deref(), Some("Fixtures"));
        assert_eq!(raw.album_artist, strings(&["Plinth Various"]));
        assert_eq!((raw.track, raw.disc), (Some(2), Some(1)));
        assert_eq!(raw.audio.map(|a| a.format), Some(Format::Aac));
    }

    /// Контейнер M4A один, а кодеки разные: ALAC — lossless, AAC — нет.
    #[test]
    fn alac_and_aac_in_m4a_differ() {
        let alac = read_raw(&asset("formats/silence-alac.m4a")).unwrap().audio.unwrap();
        let aac = read_raw(&asset("formats/silence-aac.m4a")).unwrap().audio.unwrap();

        assert_eq!(alac.format, Format::Alac);
        assert_eq!(aac.format, Format::Aac);
        assert_eq!(alac.bit_depth, Some(16));
    }

    /// Формат — по содержимому: у каждого из семи форматов v0.1.
    #[test]
    fn format_comes_from_the_contents() {
        for (file, format) in [
            ("formats/silence.mp3", Format::Mp3),
            ("formats/silence.flac", Format::Flac),
            ("formats/silence.ogg", Format::Vorbis),
            ("formats/silence.opus", Format::Opus),
            ("formats/silence.wav", Format::Wav),
        ] {
            let raw = read_raw(&asset(file)).unwrap();

            assert_eq!(raw.audio.map(|a| a.format), Some(format), "{file}");
            assert!(raw.duration.is_some(), "{file}");
        }
    }

    /// Файл без тегов — не ошибка: тегов просто нет, звук известен.
    #[test]
    fn an_untagged_file_has_only_audio() {
        let raw = read_raw(&asset("tags/plinth-untagged.mp3")).unwrap();

        assert_eq!(RawTags { audio: None, duration: None, ..raw.clone() }, RawTags::default());
        assert_eq!(raw.audio.map(|a| a.format), Some(Format::Mp3));
    }

    /// ID3v1 в конце файла — запасной: поля там обрезаны до 30 байт, а
    /// кириллица заменена на «?». Так ловится ловушка из ADR 0009.
    #[test]
    fn id3v2_wins_over_id3v1() {
        let copy = Copy::of("tags/plinth-mp3.mp3");
        let mut file = lofty::read_from_path(&copy.0).unwrap();
        let mut v1 = lofty::tag::Tag::new(lofty::tag::TagType::Id3v1);
        v1.set_title("??????".to_owned());
        v1.set_artist("Other".to_owned());
        v1.insert_text(ItemKey::Year, "1999".to_owned());
        file.insert_tag(v1);
        file.save_to_path(&copy.0, WriteOptions::default()).unwrap();

        let raw = read_raw(&copy.0).unwrap();

        assert_eq!((raw.title.as_deref(), raw.artist.as_slice()), (Some("Тишина"), &strings(&["Plinth"])[..]));
        assert_eq!(raw.year, Some(1999), "чего нет в ID3v2, берётся из ID3v1");
    }

    /// Несколько значений: в ID3v2.4 — через NUL в одном кадре, в Vorbis — отдельные поля.
    #[test]
    fn several_artist_values() {
        let mp3 =
            Copy::of("formats/silence.mp3").tag(&[(ItemKey::TrackArtist, "Alpha"), (ItemKey::TrackArtist, "Beta")]);
        let flac =
            Copy::of("formats/silence.flac").tag(&[(ItemKey::TrackArtist, "Alpha"), (ItemKey::TrackArtist, "Beta")]);

        assert_eq!(read_raw(&mp3.0).unwrap().artist, strings(&["Alpha", "Beta"]));
        assert_eq!(read_raw(&flac.0).unwrap().artist, strings(&["Alpha", "Beta"]));
    }

    /// `ARTISTS` (Picard) — отдельный список рядом со строкой исполнителя.
    #[test]
    fn an_artists_list() {
        let flac = Copy::of("formats/silence.flac").tag(&[
            (ItemKey::TrackArtist, "A & B"),
            (ItemKey::TrackArtists, "A"),
            (ItemKey::TrackArtists, "B"),
        ]);

        let raw = read_raw(&flac.0).unwrap();

        assert_eq!((raw.artist, raw.artists), (strings(&["A & B"]), strings(&["A", "B"])));
    }

    /// Флаг сборника: `TCMP` в ID3v2, `COMPILATION` в Vorbis, `cpil` в MP4.
    #[test]
    fn compilation_flag_in_each_container() {
        for file in ["formats/silence.mp3", "formats/silence.flac", "formats/silence-aac.m4a"] {
            let on = Copy::of(file).tag(&[(ItemKey::FlagCompilation, "1")]);
            let off = Copy::of(file).tag(&[(ItemKey::FlagCompilation, "0")]);

            assert_eq!(read_raw(&on.0).unwrap().compilation, Some(true), "{file}");
            assert_eq!(read_raw(&off.0).unwrap().compilation, Some(false), "{file}");
        }
        assert_eq!(read_raw(&asset("formats/silence.flac")).unwrap().compilation, None);
    }

    /// Имена для сортировки: `TSOP` и `TSO2` в ID3v2, `ARTISTSORT` и
    /// `ALBUMARTISTSORT` в Vorbis, `soar` и `soaa` в MP4.
    #[test]
    fn sort_names_in_each_container() {
        for file in ["formats/silence.mp3", "formats/silence.flac", "formats/silence-aac.m4a"] {
            let copy = Copy::of(file).tag(&[
                (ItemKey::TrackArtistSortOrder, "Bowie, David"),
                (ItemKey::AlbumArtistSortOrder, "Queen & Bowie, David"),
            ]);

            let raw = read_raw(&copy.0).unwrap();

            assert_eq!(raw.sort_artist, strings(&["Bowie, David"]), "{file}");
            assert_eq!(raw.sort_album_artist, strings(&["Queen & Bowie, David"]), "{file}");
        }
        assert!(read_raw(&asset("formats/silence.flac")).unwrap().sort_artist.is_empty());
    }

    /// В ID3v2.3 кадра `TSOP` официально нет, но Picard и Mp3tag его пишут.
    /// lofty в v2.3 его не сохраняет, поэтому тег собран вручную.
    #[test]
    fn a_sort_name_in_id3v23() {
        let copy = Copy::of("tags/plinth-untagged.mp3");
        let untagged = std::fs::read(&copy.0).unwrap();
        let old_tag = 10 + untagged[6..10].iter().fold(0_usize, |size, byte| size << 7 | usize::from(*byte));
        let mut file = id3v23(&[("TPE1", "David Bowie"), ("TSOP", "Bowie, David")]);
        file.extend_from_slice(&untagged[old_tag..]);
        std::fs::write(&copy.0, file).unwrap();

        let raw = read_raw(&copy.0).unwrap();

        assert_eq!((raw.artist, raw.sort_artist), (strings(&["David Bowie"]), strings(&["Bowie, David"])));
    }

    /// Тег ID3v2.3 из текстовых кадров в Latin-1.
    fn id3v23(frames: &[(&str, &str)]) -> Vec<u8> {
        let mut body = Vec::new();
        for (id, text) in frames {
            body.extend_from_slice(id.as_bytes());
            body.extend_from_slice(&u32::try_from(text.len() + 1).unwrap().to_be_bytes());
            body.extend_from_slice(&[0, 0, 0]); // флаги кадра и кодировка
            body.extend_from_slice(text.as_bytes());
        }
        let size = u32::try_from(body.len()).unwrap();
        let mut tag = b"ID3\x03\x00\x00".to_vec();
        tag.extend([21, 14, 7, 0].map(|shift| u8::try_from(size >> shift & 0x7f).unwrap()));
        tag.extend(body);
        tag
    }

    #[test]
    fn year_and_disc_total() {
        let flac = Copy::of("formats/silence.flac").tag(&[
            (ItemKey::RecordingDate, "1997-05-21"),
            (ItemKey::DiscNumber, "2"),
            (ItemKey::DiscTotal, "3"),
        ]);

        let raw = read_raw(&flac.0).unwrap();

        assert_eq!((raw.year, raw.disc, raw.disc_total), (Some(1997), Some(2), Some(3)));
    }

    #[test]
    fn not_audio_is_a_parse_error() {
        let copy = Copy::of("formats/silence.mp3");
        std::fs::write(&copy.0, b"just text, not audio").unwrap();

        assert!(matches!(read_raw(&copy.0), Err(CoreError::Parse { .. })));
    }

    #[test]
    fn a_missing_file_is_a_storage_error() {
        let missing = std::env::temp_dir().join(format!("plinth-missing-{}.mp3", DeviceId::new()));

        assert!(matches!(read_raw(&missing), Err(CoreError::Storage { .. })));
    }
}
