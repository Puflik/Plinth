//! Нормализация названий для поиска и индексов (B2.1): `title_normalized`,
//! имя артиста. Правила не должны часто меняться: при смене нормализованные
//! колонки пересчитываются миграцией. Идентичность от них не зависит
//! (ID не выводятся из текста).

use unicode_normalization::UnicodeNormalization;
use unicode_normalization::char::is_combining_mark;

/// Строчные буквы; латинская диакритика снимается (é → e), «ё» → «е»,
/// «й» остаётся. Знаки препинания выбрасываются, пробелы схлопываются.
pub fn normalize(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut pending_space = false;
    for c in text.chars().flat_map(char::to_lowercase) {
        if c.is_whitespace() {
            pending_space = !out.is_empty();
            continue;
        }
        let letters: Vec<char> = match c {
            'ё' => vec!['е'],
            'й' => vec!['й'],
            _ => std::iter::once(c).nfkd().filter(|c| !is_combining_mark(*c)).collect(),
        };
        for letter in letters.into_iter().filter(|c| c.is_alphanumeric()) {
            if pending_space {
                out.push(' ');
                pending_space = false;
            }
            out.push(letter);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::normalize;

    #[test]
    fn case_spaces_and_punctuation_do_not_matter() {
        assert_eq!(normalize("  The   Beatles "), "the beatles");
        assert_eq!(normalize("Don't Stop Me Now!"), "dont stop me now");
        assert_eq!(normalize("AC/DC"), "acdc");
    }

    #[test]
    fn latin_accents_fold() {
        assert_eq!(normalize("Beyoncé"), "beyonce");
        assert_eq!(normalize("Sigur Rós — Hoppípolla"), "sigur ros hoppipolla");
    }

    /// «ё» ищется как «е», а «й» остаётся «й»: это разные буквы, не диакритика.
    #[test]
    fn cyrillic_keeps_its_letters() {
        assert_eq!(normalize("Ёлка"), "елка");
        assert_eq!(normalize("Мой рок-н-ролл"), "мой рокнролл");
        assert_eq!(normalize("ЙОД"), "йод");
    }

    #[test]
    fn digits_and_other_scripts_stay() {
        assert_eq!(normalize("Blink-182"), "blink182");
        assert_eq!(normalize("東京事変"), "東京事変");
    }
}
