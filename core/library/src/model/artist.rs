use plinth_types::{ArtistId, Mbid};

/// Артист (B1.2). Биография и фото — обогащение в v0.7.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Artist {
    pub id: ArtistId,
    pub name: String,
    /// Для сортировки: «Beatles, The». Нет — сортируется по `name`.
    pub sort_name: Option<String>,
    pub mbid: Option<Mbid>,
    /// Другие написания: «Radiohead» / «Радиохед»; помогают склейке и поиску.
    pub aliases: Vec<String>,
    pub bio: Option<String>,
}
