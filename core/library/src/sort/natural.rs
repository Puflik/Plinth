//! Естественный порядок: `Track 2` раньше `Track 10`.
//!
//! Ключ не замечает регистра, лишних пробелов и диакритики: `Élan` стоит
//! среди `E`, `Ёж` — среди `Е`, как в словарях. Кроме `й`: в русском алфавите
//! это отдельная буква после `и`. Числа дополняются нулями до
//! [`NUMBER_WIDTH`] знаков и сравниваются по значению; числа длиннее
//! сравниваются как есть.

use unicode_normalization::UnicodeNormalization;
use unicode_normalization::char::is_combining_mark;

/// Сколько знаков занимает число в ключе.
const NUMBER_WIDTH: usize = 20;

/// Кратка (U+0306): на ней держится «й», её не снимаем.
const BREVE: char = '\u{0306}';

/// Без крайних и двойных пробелов, регистра и надстрочных знаков: `Beyoncé` → `beyonce`.
pub fn fold(text: &str) -> String {
    let spaced = text.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase();
    spaced.nfd().filter(|c| *c == BREVE || !is_combining_mark(*c)).nfc().collect()
}

/// Ключ естественного порядка.
pub fn key(text: &str) -> String {
    let folded = fold(text);
    let mut out = String::with_capacity(folded.len() + NUMBER_WIDTH);
    let mut digits = String::new();
    for c in folded.chars() {
        if c.is_ascii_digit() {
            digits.push(c);
            continue;
        }
        push_number(&mut out, &mut digits);
        out.push(c);
    }
    push_number(&mut out, &mut digits);
    out
}

/// Число без ведущих нулей, дополненное нулями до [`NUMBER_WIDTH`].
fn push_number(out: &mut String, digits: &mut String) {
    if digits.is_empty() {
        return;
    }
    let value = digits.trim_start_matches('0');
    let value = if value.is_empty() { "0" } else { value };
    out.extend(std::iter::repeat_n('0', NUMBER_WIDTH.saturating_sub(value.len())));
    out.push_str(value);
    digits.clear();
}

#[cfg(test)]
mod tests {
    use super::key;

    fn sorted(names: &[&str]) -> Vec<String> {
        let mut names: Vec<String> = names.iter().map(|n| (*n).to_owned()).collect();
        names.sort_by_key(|n| key(n));
        names
    }

    #[test]
    fn numbers_compare_by_value() {
        assert_eq!(sorted(&["Track 10", "Track 2", "Track 1"]), ["Track 1", "Track 2", "Track 10"]);
        assert_eq!(key("Track 02"), key("Track 2"));
        assert_eq!(key("Track 0"), key("Track 000"));
    }

    #[test]
    fn several_numbers_compare_one_by_one() {
        assert_eq!(
            sorted(&["Disc 2 Track 1", "Disc 1 Track 10", "Disc 1 Track 9"]),
            ["Disc 1 Track 9", "Disc 1 Track 10", "Disc 2 Track 1"]
        );
    }

    #[test]
    fn numbers_go_before_letters() {
        assert_eq!(sorted(&["Abba", "10 Years"]), ["10 Years", "Abba"]);
    }

    #[test]
    fn case_and_spaces_are_ignored() {
        assert_eq!(key("ABBA"), key("abba"));
        assert_eq!(key("  Track   2 "), key("Track 2"));
    }

    #[test]
    fn accented_latin_letters_sort_with_their_base_letter() {
        assert_eq!(sorted(&["Fame", "Élan", "Eagle"]), ["Eagle", "Élan", "Fame"]);
    }

    /// «Ё» — как «Е», а «Й» — своя буква после «И», как в русских словарях.
    #[test]
    fn russian_yo_and_short_i() {
        assert_eq!(sorted(&["Жук", "Ёж", "Еда"]), ["Еда", "Ёж", "Жук"]);
        assert_eq!(sorted(&["Кит", "Йога", "Иволга"]), ["Иволга", "Йога", "Кит"]);
    }

    /// Ключи сравниваются по кодовым точкам — так их сравнивает и SQLite.
    #[test]
    fn keys_compare_by_code_point() {
        assert_eq!(sorted(&["😀", "ｶ"]), ["ｶ", "😀"]);
    }

    /// Число длиннее ширины ключа сравнивается как есть, а не обрезается.
    #[test]
    fn a_huge_number_stays_whole() {
        let huge = "1".repeat(25);

        assert!(key(&huge).contains(&huge));
    }
}
