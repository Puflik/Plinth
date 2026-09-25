use plinth_types::{AlbumId, ArtistId, Mbid};

/// Альбом (B1.2) — выпуск, на котором стоят записи ([`crate::model::Version`]).
/// Обложка — файл в кэше устройства, в модели её нет.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Album {
    pub id: AlbumId,
    pub title: String,
    /// Исполнитель альбома строкой, как в тегах: «Various Artists».
    pub artist_credit: String,
    pub artists: Vec<ArtistId>,
    pub year: Option<u16>,
    pub label: Option<String>,
    /// Код страны выпуска, ISO 3166-1: `GB`.
    pub country: Option<String>,
    pub disc_count: Option<u16>,
    pub mbid_release: Option<Mbid>,
}
