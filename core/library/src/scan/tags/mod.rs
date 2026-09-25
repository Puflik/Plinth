//! Чтение тегов (D2, [ADR 0009](../../../../../docs/adr/0009-tag-library.md)):
//! шов [`TagReader`] между сканером и файлом.
//!
//! - `reader` — теги и свойства звука через `lofty`: ID3v2, Vorbis Comments,
//!   атомы MP4;
//! - `normalize` — несколько исполнителей, album artist, сборники;
//! - `artwork` — обложка по запросу: встроенная, иначе `cover.jpg` рядом.

mod artwork;
mod normalize;
mod reader;

use std::path::Path;
use std::time::Duration;

use plinth_types::CoreError;

pub use artwork::{Artwork, artwork, embedded_artwork};
pub use normalize::{ArtistSplit, RawTags, normalized};
pub use reader::read_raw;

use crate::model::AudioSpec;

/// Что удалось узнать из файла; чего нет — `None` или пусто.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Tags {
    pub title: Option<String>,
    /// Исполнитель строкой, как его показать: «Daft Punk feat. Pharrell».
    pub artist: Option<String>,
    /// Артисты по одному, основной первым.
    pub artists: Vec<String>,
    pub album: Option<String>,
    /// Исполнитель альбома строкой: «Various Artists».
    pub album_artist: Option<String>,
    /// Артисты альбома; у сборника без своего исполнителя — пусто.
    pub album_artists: Vec<String>,
    pub compilation: bool,
    pub track: Option<u16>,
    pub disc: Option<u16>,
    pub disc_total: Option<u16>,
    pub year: Option<u16>,
    pub duration: Option<Duration>,
    /// Звук по содержимому файла; `None` — формат известен только по расширению.
    pub audio: Option<AudioSpec>,
}

/// Читает теги файла. Ошибка — файл повреждён или формат не тот: сканер всё
/// равно добавит его под именем файла, а не сыграет — скажет плеер.
pub trait TagReader: Sync {
    fn read(&self, path: &Path) -> Result<Tags, CoreError>;
}

/// Тегов не читает: название сканер возьмёт из имени файла. Для тестов
/// сканера, которым теги не важны.
pub struct FileNameOnly;

impl TagReader for FileNameOnly {
    fn read(&self, _path: &Path) -> Result<Tags, CoreError> {
        Ok(Tags::default())
    }
}

/// Настоящее чтение: теги файла, сведённые по правилам `split`.
#[derive(Debug, Clone, Default)]
pub struct FileTags {
    pub split: ArtistSplit,
}

impl TagReader for FileTags {
    fn read(&self, path: &Path) -> Result<Tags, CoreError> {
        read_raw(path).map(|raw| normalized(raw, &self.split))
    }
}
