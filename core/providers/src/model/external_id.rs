use std::fmt;

use plinth_types::CoreError;

/// Идентификатор у провайдера: элемент Internet Archive, файл в нём, видео
/// YouTube. Уходит в каталог и журнал (`SourceLocation::Provider`), поэтому
/// ограничен: непустой, до 1024 байт, без управляющих символов.
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct ExternalId(String);

impl ExternalId {
    pub const MAX_LEN: usize = 1024;

    /// Текст идентификатора в ошибку не попадает: у Internet Archive это
    /// бывает название концерта, а лог не должен знать библиотеку.
    pub fn new(id: &str) -> Result<Self, CoreError> {
        if id.is_empty() {
            Err(CoreError::parse("external id: empty"))
        } else if id.len() > Self::MAX_LEN {
            Err(CoreError::parse(format!("external id: {} bytes, at most {}", id.len(), Self::MAX_LEN)))
        } else if id.chars().any(char::is_control) {
            Err(CoreError::parse("external id: control characters"))
        } else {
            Ok(Self(id.to_owned()))
        }
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for ExternalId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

#[cfg(test)]
mod tests {
    use plinth_types::CoreError;

    use super::ExternalId;

    #[test]
    fn keeps_provider_ids_as_they_are() {
        for good in ["gd1977-05-08.sbd.flac16", "gd77/gd77-05-08d1t01.flac", "dQw4w9WgXcQ", "Кино — Группа крови"]
        {
            assert_eq!(ExternalId::new(good).map(|id| id.as_str().to_owned()), Ok(good.to_owned()));
        }
        assert!(ExternalId::new(&"x".repeat(ExternalId::MAX_LEN)).is_ok());
    }

    #[test]
    fn rejects_empty_huge_and_control_characters() {
        let huge = "x".repeat(ExternalId::MAX_LEN + 1);
        for bad in ["", huge.as_str(), "a\nb", "a\u{0}b", "a\tb"] {
            assert!(matches!(ExternalId::new(bad), Err(CoreError::Parse { .. })), "{bad:?}");
        }
    }

    #[test]
    fn error_does_not_leak_the_id() {
        let Err(error) = ExternalId::new("Secret concert\n") else { panic!("accepted") };

        assert!(!error.to_string().contains("Secret"), "{error}");
    }
}
