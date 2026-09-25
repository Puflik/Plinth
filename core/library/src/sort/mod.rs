//! Порядок названий в списках (D3.2, plan.md 13.2): естественный — `Track 2`
//! раньше `Track 10` — и без ведущего артикля: `The Beatles` под «B».
//!
//! Порядок задан ключом: база хранит ключ рядом с названием и сортирует
//! `ORDER BY` по нему, побайтово — то есть по кодовым точкам.

mod articles;
mod natural;

use std::sync::LazyLock;

pub use articles::Articles;
pub use natural::{fold, key};

/// Ключ сортировки названия: без ведущего артикля, в естественном порядке.
/// Смена списка артиклей требует пересчитать ключи, но не пересканировать файлы.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SortKeys {
    pub articles: Articles,
}

impl SortKeys {
    pub fn of(&self, text: &str) -> String {
        key(self.articles.strip(text))
    }
}

/// Ключ с артиклями по умолчанию — им база сортирует списки. Настройка
/// списка артиклей переедет в базу вместе с экраном настройки.
pub fn sort_key(text: &str) -> String {
    static DEFAULT: LazyLock<SortKeys> = LazyLock::new(SortKeys::default);
    DEFAULT.of(text)
}

#[cfg(test)]
mod tests {
    use super::{Articles, SortKeys, key};

    fn sorted(names: &[&str]) -> Vec<String> {
        let keys = SortKeys::default();
        let mut names: Vec<String> = names.iter().map(|n| (*n).to_owned()).collect();
        names.sort_by_key(|n| keys.of(n));
        names
    }

    #[test]
    fn the_article_is_ignored() {
        assert_eq!(sorted(&["Coldplay", "The Beatles", "ABBA"]), ["ABBA", "The Beatles", "Coldplay"]);
    }

    #[test]
    fn numbers_after_the_article_follow_natural_order() {
        assert_eq!(sorted(&["The 10 Bears", "The 2 Bears"]), ["The 2 Bears", "The 10 Bears"]);
    }

    #[test]
    fn case_of_the_article_does_not_matter() {
        let keys = SortKeys::default();

        assert_eq!(keys.of("the beatles"), keys.of("The Beatles"));
    }

    #[test]
    fn keys_use_the_given_articles() {
        let spanish = SortKeys { articles: Articles::new(&["El"]) };

        assert_eq!(spanish.of("El Guincho"), key("Guincho"));
        assert_eq!(spanish.of("The Beatles"), key("The Beatles"));
    }
}
