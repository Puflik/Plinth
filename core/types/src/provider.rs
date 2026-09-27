//! Имя провайдера (A1.2, E1). Общее для каталога (`library`: источник
//! трека у провайдера) и провайдеров (`providers`), поэтому живёт здесь.

use std::fmt;

use crate::CoreError;

/// Имя провайдера: `archive.org`, `bandcamp`. Строчные латиница, цифры,
/// точка и дефис, до 64 знаков — идёт в журнал и в ключи базы.
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct ProviderId(String);

impl ProviderId {
    pub fn new(name: &str) -> Result<Self, CoreError> {
        let valid = (1..=64).contains(&name.len())
            && name.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'.' || b == b'-');
        if valid { Ok(Self(name.to_owned())) } else { Err(CoreError::parse(format!("provider id: {name:?}"))) }
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for ProviderId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

#[cfg(test)]
mod tests {
    use super::ProviderId;
    use crate::CoreError;

    #[test]
    fn provider_id_is_a_short_lowercase_name() {
        for good in ["archive.org", "youtube", "bandcamp", "my-navidrome"] {
            assert_eq!(ProviderId::new(good).map(|p| p.to_string()), Ok(good.to_owned()));
            assert_eq!(ProviderId::new(good).map(|p| p.as_str().to_owned()), Ok(good.to_owned()));
        }
        for bad in ["", "YouTube", "you tube", "a/b", &"x".repeat(65)] {
            assert!(matches!(ProviderId::new(bad), Err(CoreError::Parse { .. })), "{bad}");
        }
    }
}
