/// Имя заголовка — токен RFC 9110: латиница, цифры и `!#$%&'*+-.^_`|~`.
pub(crate) fn is_header_name(name: &str) -> bool {
    !name.is_empty() && name.bytes().all(|b| b.is_ascii_alphanumeric() || b"!#$%&'*+-.^_`|~".contains(&b))
}

/// Значение без переводов строки и нуля: через него не подставить чужой заголовок.
pub(crate) fn is_header_value(value: &str) -> bool {
    !value.chars().any(|c| c == '\r' || c == '\n' || c == '\0')
}
