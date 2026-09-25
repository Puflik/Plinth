//! Обложка файла (D2.2, D3). Скан картинки не читает; обложку достаёт
//! отдельное чтение файла, когда её просит экран: встроенная картинка, а у
//! файла без неё — `cover.jpg` или `folder.jpg` в той же папке (ответ автора в
//! `docs/decisions.md`, «D3: разбивка»).

use std::path::Path;

use lofty::config::ParseOptions;
use lofty::file::TaggedFileExt;
use lofty::picture::PictureType;
use plinth_types::CoreError;

use super::reader::open;

/// Картинка: байты как есть, тип — если его назвал тег или расширение файла.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Artwork {
    /// `image/jpeg`, `image/png`.
    pub mime: Option<String>,
    pub data: Vec<u8>,
}

/// Картинки рядом с файлом, по старшинству. Регистр имени не важен.
const FOLDER_IMAGES: [&str; 4] = ["cover", "folder", "front", "album"];

/// Обложка файла: встроенная, иначе картинка из папки; `None` — нет ни той, ни другой.
pub fn artwork(path: &Path) -> Result<Option<Artwork>, CoreError> {
    match embedded_artwork(path)? {
        Some(embedded) => Ok(Some(embedded)),
        None => folder_image(path),
    }
}

/// `cover.jpg` и подобные рядом с файлом. Папку не прочесть — обложки нет,
/// это не ошибка: файл-то прочитался.
fn folder_image(path: &Path) -> Result<Option<Artwork>, CoreError> {
    let Some(entries) = path.parent().and_then(|dir| std::fs::read_dir(dir).ok()) else { return Ok(None) };
    let mut best: Option<(usize, String, std::path::PathBuf, &str)> = None;
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_lowercase();
        let Some((stem, extension)) = name.rsplit_once('.') else { continue };
        let mime = match extension {
            "jpg" | "jpeg" => "image/jpeg",
            "png" => "image/png",
            _ => continue,
        };
        let Some(rank) = FOLDER_IMAGES.iter().position(|image| *image == stem) else { continue };
        // Равные по старшинству — по имени, чтобы выбор не зависел от порядка в папке.
        if best.as_ref().is_none_or(|(r, n, ..)| (rank, &name) < (*r, n)) {
            best = Some((rank, name, entry.path(), mime));
        }
    }
    let Some((.., image, mime)) = best else { return Ok(None) };
    let data = std::fs::read(&image).map_err(|e| CoreError::storage(format!("artwork: {e}")))?;
    Ok(Some(Artwork { mime: Some(mime.to_owned()), data }))
}

/// Передняя обложка файла, иначе первая картинка; `None` — картинок нет.
pub fn embedded_artwork(path: &Path) -> Result<Option<Artwork>, CoreError> {
    let (tagged, _) = open(path, ParseOptions::new().read_properties(false))?;
    let primary = tagged.primary_tag_type();
    let pictures: Vec<_> = tagged
        .primary_tag()
        .into_iter()
        .chain(tagged.tags().iter().filter(|t| t.tag_type() != primary))
        .flat_map(|tag| tag.pictures())
        .collect();
    let chosen = pictures.iter().find(|p| p.pic_type() == PictureType::CoverFront).or_else(|| pictures.first());
    Ok(chosen.map(|picture| Artwork {
        mime: picture.mime_type().map(|m| m.as_str().to_owned()),
        data: picture.data().to_vec(),
    }))
}

#[cfg(test)]
mod tests {
    use lofty::config::WriteOptions;
    use lofty::picture::{MimeType, Picture, PictureType};
    use lofty::prelude::*;
    use plinth_types::{CoreError, DeviceId};

    use super::{artwork, embedded_artwork};
    use crate::scan::tags::reader::tests::{Copy, asset};

    fn picture(kind: PictureType, data: &[u8]) -> Picture {
        Picture::unchecked(data.to_vec()).pic_type(kind).mime_type(MimeType::Png).build()
    }

    fn with_pictures(file: &str, pictures: Vec<Picture>) -> Copy {
        let copy = Copy::of(file);
        let mut tagged = lofty::read_from_path(&copy.0).unwrap();
        let kind = tagged.primary_tag_type();
        if tagged.primary_tag().is_none() {
            tagged.insert_tag(lofty::tag::Tag::new(kind));
        }
        let tag = tagged.primary_tag_mut().unwrap();
        pictures.into_iter().for_each(|p| tag.push_picture(p));
        tag.save_to_path(&copy.0, WriteOptions::default()).unwrap();
        copy
    }

    /// APIC в MP3 — фикстура обложек приложения (`tools/make_artwork_fixtures.py`).
    #[test]
    fn a_cover_in_mp3() {
        let cover = embedded_artwork(&asset("artwork/plinth-cover.mp3")).unwrap().unwrap();

        assert_eq!(cover.mime.as_deref(), Some("image/jpeg"));
        assert!(cover.data.starts_with(&[0xFF, 0xD8]), "не JPEG");
    }

    #[test]
    fn no_pictures_no_cover() {
        assert_eq!(embedded_artwork(&asset("tags/plinth-untagged.mp3")).unwrap(), None);
        assert_eq!(embedded_artwork(&asset("tags/plinth-flac.flac")).unwrap(), None);
    }

    /// Передняя обложка важнее картинки, записанной раньше неё.
    #[test]
    fn the_front_cover_wins() {
        for file in ["formats/silence.flac", "formats/silence-aac.m4a", "formats/silence.mp3"] {
            let copy = with_pictures(
                file,
                vec![picture(PictureType::Artist, b"artist"), picture(PictureType::CoverFront, b"front")],
            );

            let cover = embedded_artwork(&copy.0).unwrap().unwrap();

            // В MP4 тип картинки не хранится — там берётся первая.
            let expected: &[u8] = if file.ends_with(".m4a") { b"artist" } else { b"front" };
            assert_eq!(cover.data, expected, "{file}");
        }
    }

    #[test]
    fn without_a_front_cover_the_first_picture() {
        let copy = with_pictures("formats/silence.flac", vec![picture(PictureType::Other, b"other")]);

        let cover = embedded_artwork(&copy.0).unwrap().unwrap();

        assert_eq!((cover.mime.as_deref(), cover.data.as_slice()), (Some("image/png"), &b"other"[..]));
    }

    /// Без встроенной картинки — `cover.jpg` из папки файла, регистр имени не важен.
    #[test]
    fn a_cover_from_the_folder() {
        let copy = Copy::of("formats/silence.mp3");
        std::fs::write(copy.0.with_file_name("Cover.JPG"), b"\xFF\xD8 folder").unwrap();

        let cover = artwork(&copy.0).unwrap().unwrap();

        assert_eq!((cover.mime.as_deref(), cover.data.as_slice()), (Some("image/jpeg"), &b"\xFF\xD8 folder"[..]));
    }

    /// `cover` старше `folder`, а `.png` тоже годится.
    #[test]
    fn folder_images_by_precedence() {
        let copy = Copy::of("formats/silence.mp3");
        std::fs::write(copy.0.with_file_name("folder.jpg"), b"folder").unwrap();
        std::fs::write(copy.0.with_file_name("cover.png"), b"cover").unwrap();
        std::fs::write(copy.0.with_file_name("notes.jpg"), b"notes").unwrap();

        let cover = artwork(&copy.0).unwrap().unwrap();

        assert_eq!((cover.mime.as_deref(), cover.data.as_slice()), (Some("image/png"), &b"cover"[..]));
    }

    /// Встроенная картинка важнее картинки из папки.
    #[test]
    fn the_embedded_picture_wins_over_the_folder() {
        let copy = with_pictures("formats/silence.flac", vec![picture(PictureType::CoverFront, b"front")]);
        std::fs::write(copy.0.with_file_name("cover.jpg"), b"folder").unwrap();

        assert_eq!(artwork(&copy.0).unwrap().unwrap().data, b"front");
    }

    #[test]
    fn no_picture_anywhere_no_cover() {
        let copy = Copy::of("formats/silence.mp3");

        assert_eq!(artwork(&copy.0).unwrap(), None);
    }

    #[test]
    fn a_missing_file_is_a_storage_error() {
        let missing = std::env::temp_dir().join(format!("plinth-missing-{}.flac", DeviceId::new()));

        assert!(matches!(embedded_artwork(&missing), Err(CoreError::Storage { .. })));
    }
}
