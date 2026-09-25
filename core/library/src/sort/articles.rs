//! Ведущий артикль, которого сортировка не замечает. Список настраиваемый
//! (plan.md 13.2): у каждой коллекции свои языки.

/// Артикль — только отдельное первое слово и только если после него что-то
/// есть: `Theatre` и одинокое `The` остаются как есть.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Articles(Vec<String>);

/// Умолчание из плана: английский, немецкий, французский, испанский.
const DEFAULT: [&str; 9] = ["The", "A", "An", "Der", "Die", "Das", "Le", "La", "Los"];

impl Default for Articles {
    fn default() -> Self {
        Self::new(&DEFAULT)
    }
}

impl Articles {
    pub fn new(articles: &[&str]) -> Self {
        Self(articles.iter().map(|a| a.trim().to_lowercase()).filter(|a| !a.is_empty()).collect())
    }

    /// Название без ведущего артикля и без пробелов по краям.
    pub fn strip<'a>(&self, text: &'a str) -> &'a str {
        let trimmed = text.trim();
        let Some((first, rest)) = trimmed.split_once(char::is_whitespace) else { return trimmed };
        if self.0.contains(&first.to_lowercase()) { rest.trim_start() } else { trimmed }
    }
}

#[cfg(test)]
mod tests {
    use super::Articles;

    fn strip(text: &str) -> &str {
        // Articles::default() живёт до конца теста — ссылка на text не зависит от него.
        Articles::default().strip(text)
    }

    #[test]
    fn a_leading_article_is_cut_off() {
        assert_eq!(strip("The Beatles"), "Beatles");
        assert_eq!(strip("THE BEATLES"), "BEATLES");
        assert_eq!(strip("  The Beatles"), "Beatles");
    }

    #[test]
    fn every_default_article_is_recognised() {
        for article in ["The", "A", "An", "Der", "Die", "Das", "Le", "La", "Los"] {
            assert_eq!(strip(&format!("{article} Name")), "Name", "{article}");
        }
    }

    #[test]
    fn an_article_must_be_a_whole_word() {
        assert_eq!(strip("Theatre of Tragedy"), "Theatre of Tragedy");
        assert_eq!(strip("Anathema"), "Anathema");
        assert_eq!(strip("A-ha"), "A-ha");
    }

    #[test]
    fn only_the_first_article_is_cut_off() {
        assert_eq!(strip("The The"), "The");
    }

    #[test]
    fn a_lone_article_stays() {
        assert_eq!(strip("The"), "The");
        assert_eq!(strip("The  "), "The");
    }

    #[test]
    fn the_list_is_configurable() {
        let spanish = Articles::new(&["El", " "]);

        assert_eq!(spanish.strip("El Guincho"), "Guincho");
        assert_eq!(spanish.strip("The Beatles"), "The Beatles");
    }
}
