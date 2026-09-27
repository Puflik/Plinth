//! Кодировка файла плейлиста (D4c). M3U8 — всегда UTF-8, у M3U и PLS
//! кодировки нет: их писали в той, что стояла у системы.

/// Текст файла плейлиста. Метка порядка байтов решает сразу (UTF-8,
/// UTF-16); без неё — UTF-8, если байты им читаются, иначе однобайтовая
/// кодировка Windows: CP1251, если байты верхней половины идут словами
/// (кириллица), CP1252 — если врозь (буквы с диакритикой среди латиницы).
pub fn decode(bytes: &[u8]) -> String {
    if let Some(rest) = bytes.strip_prefix(b"\xef\xbb\xbf") {
        return String::from_utf8_lossy(rest).into_owned();
    }
    if let Some(rest) = bytes.strip_prefix(b"\xff\xfe") {
        return utf16(rest, u16::from_le_bytes);
    }
    if let Some(rest) = bytes.strip_prefix(b"\xfe\xff") {
        return utf16(rest, u16::from_be_bytes);
    }
    if let Ok(text) = std::str::from_utf8(bytes) {
        return text.to_owned();
    }
    let high: fn(u8) -> char = if cyrillic(bytes) { cp1251 } else { cp1252 };
    bytes.iter().map(|&b| if b < 0x80 { char::from(b) } else { high(b) }).collect()
}

fn utf16(bytes: &[u8], unit: fn([u8; 2]) -> u16) -> String {
    let units: Vec<u16> = bytes.as_chunks::<2>().0.iter().map(|&pair| unit(pair)).collect();
    String::from_utf16_lossy(&units)
}

/// Буква CP1251: `А`–`я`, `Ё` и `ё`.
fn letter_1251(b: u8) -> bool {
    b >= 0xc0 || b == 0xa8 || b == 0xb8
}

/// Хотя бы половина таких букв стоит рядом с такой же — это слова, а не
/// одиночные буквы с диакритикой.
fn cyrillic(bytes: &[u8]) -> bool {
    let letters = bytes.iter().filter(|&&b| letter_1251(b)).count();
    let pairs = bytes.windows(2).filter(|w| letter_1251(w[0]) && letter_1251(w[1])).count();
    letters > 0 && pairs * 2 >= letters
}

/// Байты `0x80..=0xBF` в CP1251; `0xC0..=0xFF` — подряд `А`–`я`.
const CP1251_HIGH: [char; 64] = [
    'Ђ', 'Ѓ', '‚', 'ѓ', '„', '…', '†', '‡', '€', '‰', 'Љ', '‹', 'Њ', 'Ќ', 'Ћ', 'Џ', //
    'ђ', '‘', '’', '“', '”', '•', '–', '—', '\u{98}', '™', 'љ', '›', 'њ', 'ќ', 'ћ', 'џ', //
    '\u{a0}', 'Ў', 'ў', 'Ј', '¤', 'Ґ', '¦', '§', 'Ё', '©', 'Є', '«', '¬', '\u{ad}', '®', 'Ї', //
    '°', '±', 'І', 'і', 'ґ', 'µ', '¶', '·', 'ё', '№', 'є', '»', 'ј', 'Ѕ', 'ѕ', 'ї',
];

/// Байты `0x80..=0x9F` в CP1252; `0xA0..=0xFF` совпадают с Latin-1.
const CP1252_LOW: [char; 32] = [
    '€', '\u{81}', '‚', 'ƒ', '„', '…', '†', '‡', 'ˆ', '‰', 'Š', '‹', 'Œ', '\u{8d}', 'Ž', '\u{8f}', //
    '\u{90}', '‘', '’', '“', '”', '•', '–', '—', '˜', '™', 'š', '›', 'œ', '\u{9d}', 'ž', 'Ÿ',
];

fn cp1251(b: u8) -> char {
    match b {
        0xc0..=0xff => char::from_u32(0x0410 + u32::from(b - 0xc0)).unwrap_or(char::REPLACEMENT_CHARACTER),
        _ => CP1251_HIGH[usize::from(b - 0x80)],
    }
}

fn cp1252(b: u8) -> char {
    if b >= 0xa0 { char::from(b) } else { CP1252_LOW[usize::from(b - 0x80)] }
}

#[cfg(test)]
mod tests {
    use super::decode;

    /// «Ёлка — Прованс» в CP1251.
    const YOLKA_1251: &[u8] = b"\xa8\xeb\xea\xe0 \x97 \xcf\xf0\xee\xe2\xe0\xed\xf1";

    #[test]
    fn utf8_stays_and_its_bom_goes() {
        assert_eq!(decode("Ёлка — Прованс".as_bytes()), "Ёлка — Прованс");
        assert_eq!(decode(b"\xef\xbb\xbf#EXTM3U"), "#EXTM3U");
    }

    /// Блокнот Windows сохраняет «Юникод» — UTF-16 с меткой порядка байтов.
    #[test]
    fn utf16_with_a_bom_in_both_byte_orders() {
        let text = "Ёлка\r\n/a.mp3";
        let le: Vec<u8> = [0xff, 0xfe].into_iter().chain(text.encode_utf16().flat_map(u16::to_le_bytes)).collect();
        let be: Vec<u8> = [0xfe, 0xff].into_iter().chain(text.encode_utf16().flat_map(u16::to_be_bytes)).collect();

        assert_eq!(decode(&le), text);
        assert_eq!(decode(&be), text);
    }

    /// Не UTF-8 и сплошные слова из верхней половины — кириллица CP1251.
    #[test]
    fn cyrillic_words_are_cp1251() {
        assert_eq!(decode(YOLKA_1251), "Ёлка — Прованс");
        assert_eq!(decode(b"\xb9 1 \xab\xca\xe8\xed\xee\xbb \xb8\xe6"), "№ 1 «Кино» ёж");
        assert_eq!(decode(b"/Music/\xca\xe8\xed\xee/\xca\xf3\xea\xf3\xf8\xea\xe0.mp3"), "/Music/Кино/Кукушка.mp3");
    }

    /// Редкие буквы с диакритикой среди латиницы — западный CP1252.
    #[test]
    fn scattered_accents_are_cp1252() {
        assert_eq!(decode(b"Beyonc\xe9 - D\xe9j\xe0 Vu"), "Beyoncé - Déjà Vu");
        assert_eq!(decode(b"M\xf6tley Cr\xfce \x96 \x80 \x93Dr. Feelgood\x94"), "Mötley Crüe – € “Dr. Feelgood”");
    }

    /// Неопределённые байты не теряются и не ломают строку.
    #[test]
    fn undefined_bytes_become_control_characters() {
        assert_eq!(decode(b"a\x81b"), "a\u{81}b");
        assert_eq!(decode(b"\xcf\xf0\x98"), "Пр\u{98}");
    }
}
