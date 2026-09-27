/// Процентное кодирование части адреса (RFC 3986): всё, кроме `A–Z a–z 0–9
/// - . _ ~`, — `%XX` по байтам UTF-8. Годится и для сегмента пути, и для
/// значения параметра: `/`, `?`, `&`, `=` и `#` тоже кодируются.
pub fn encode_component(text: &str) -> String {
    let mut encoded = String::with_capacity(text.len());
    for byte in text.bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'.' | b'_' | b'~') {
            encoded.push(char::from(byte));
        } else {
            encoded.push('%');
            encoded.push(char::from(HEX[usize::from(byte >> 4)]));
            encoded.push(char::from(HEX[usize::from(byte & 0x0f)]));
        }
    }
    encoded
}

const HEX: &[u8; 16] = b"0123456789ABCDEF";

#[cfg(test)]
mod tests {
    use super::encode_component;

    #[test]
    fn unreserved_stay_the_rest_is_percent_encoded() {
        assert_eq!(encode_component("gd1977-05-08.sbd_v~1"), "gd1977-05-08.sbd_v~1");
        assert_eq!(encode_component("grateful dead"), "grateful%20dead");
        assert_eq!(encode_component("a/b?c=d&e#f+g%"), "a%2Fb%3Fc%3Dd%26e%23f%2Bg%25");
        assert_eq!(encode_component("Кино"), "%D0%9A%D0%B8%D0%BD%D0%BE");
        assert_eq!(encode_component(""), "");
    }
}
