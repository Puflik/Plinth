use std::time::Duration;

use super::ExternalId;

/// Поисковый запрос к провайдеру. Текст обрезан по краям, число результатов
/// ограничено: провайдер не отдаёт больше, чем просили.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchQuery {
    text: String,
    limit: u32,
}

impl SearchQuery {
    pub const MAX_LIMIT: u32 = 100;

    /// `limit` приводится к 1..=[`Self::MAX_LIMIT`].
    pub fn new(text: &str, limit: u32) -> Self {
        Self { text: text.trim().to_owned(), limit: limit.clamp(1, Self::MAX_LIMIT) }
    }

    pub fn text(&self) -> &str {
        &self.text
    }

    pub fn limit(&self) -> u32 {
        self.limit
    }

    /// Пустой запрос в сеть не уходит.
    pub fn is_blank(&self) -> bool {
        self.text.is_empty()
    }
}

/// Что нашлось: отдельный трек (YouTube, SoundCloud) или собрание треков —
/// альбом, концерт, элемент Internet Archive. Треки собрания даёт `resolve`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum ResultKind {
    Track,
    Album,
}

/// Результат поиска, одинаковый у всех провайдеров (E1.2). Живёт только в
/// памяти: в каталог трек попадает, когда с ним что-то сделали.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchResult {
    /// По нему `resolve` отдаёт треки.
    pub external: ExternalId,
    pub kind: ResultKind,
    /// Непустое: нет названия у провайдера — провайдер подставляет своё имя
    /// элемента.
    pub title: String,
    /// У любительского каталога исполнителя может не быть.
    pub artist: Option<String>,
    /// Альбом трека; у собрания — `None`, его название и так в `title`.
    pub album: Option<String>,
    pub year: Option<u16>,
    pub duration: Option<Duration>,
}

#[cfg(test)]
mod tests {
    use super::SearchQuery;

    #[test]
    fn text_is_trimmed_and_blank_is_known() {
        let query = SearchQuery::new("  grateful dead \n", 20);

        assert_eq!(query.text(), "grateful dead");
        assert!(!query.is_blank());
        assert!(SearchQuery::new(" \t ", 20).is_blank());
    }

    #[test]
    fn limit_is_one_to_max() {
        assert_eq!(SearchQuery::new("x", 0).limit(), 1);
        assert_eq!(SearchQuery::new("x", 25).limit(), 25);
        assert_eq!(SearchQuery::new("x", 10_000).limit(), SearchQuery::MAX_LIMIT);
    }
}
