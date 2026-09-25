//! Чтение тегов — шов между сканером (D1) и настоящим чтением (D2). Пока
//! сканер знает о файле только имя: название — имя файла без расширения.

use std::path::Path;
use std::time::Duration;

use plinth_types::CoreError;

/// Что удалось узнать из файла; чего нет — `None`.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Tags {
    pub title: Option<String>,
    pub artist: Option<String>,
    pub duration: Option<Duration>,
}

/// Читает теги файла. Ошибка — файл повреждён или формат не тот: сканер всё
/// равно добавит его под именем файла, а не сыграет — скажет плеер.
pub trait TagReader: Sync {
    fn read(&self, path: &Path) -> Result<Tags, CoreError>;
}

/// До D2: тегов не читает, название сканер возьмёт из имени файла.
pub struct FileNameOnly;

impl TagReader for FileNameOnly {
    fn read(&self, _path: &Path) -> Result<Tags, CoreError> {
        Ok(Tags::default())
    }
}
