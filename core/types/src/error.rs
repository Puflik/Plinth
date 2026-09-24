/// Единая ошибка ядра (A1.2). Категория говорит Kotlin, что делать —
/// повторить, объяснить пользователю или отправить в отчёт о сбое; текст —
/// для лога. Наружу ошибка уходит как `CoreException` того же вида
/// (docs/adr/0010-ffi-boundary.md), поэтому в тексте нет путей и названий
/// треков: лог вырезает пути, но не содержимое библиотеки.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum CoreError {
    /// База, журнал, файловая система.
    #[error("storage: {message}")]
    Storage { message: String },
    /// Сеть и ответы провайдеров.
    #[error("network: {message}")]
    Network { message: String },
    /// Разбор тегов, ответов, форматов.
    #[error("parse: {message}")]
    Parse { message: String },
    /// Файл, трек или источник пропал или недоступен.
    #[error("unavailable: {message}")]
    Unavailable { message: String },
    /// Ошибка в самом ядре, включая перехваченную панику (A3.3).
    #[error("internal: {message}")]
    Internal { message: String },
}

impl CoreError {
    pub fn storage(message: impl Into<String>) -> Self {
        Self::Storage { message: message.into() }
    }

    pub fn network(message: impl Into<String>) -> Self {
        Self::Network { message: message.into() }
    }

    pub fn parse(message: impl Into<String>) -> Self {
        Self::Parse { message: message.into() }
    }

    pub fn unavailable(message: impl Into<String>) -> Self {
        Self::Unavailable { message: message.into() }
    }

    pub fn internal(message: impl Into<String>) -> Self {
        Self::Internal { message: message.into() }
    }
}

#[cfg(test)]
mod tests {
    use super::CoreError;

    #[test]
    fn message_names_the_category() {
        let cases = [
            (CoreError::storage("disk full"), "storage: disk full"),
            (CoreError::network("timeout"), "network: timeout"),
            (CoreError::parse("bad tag"), "parse: bad tag"),
            (CoreError::unavailable("file gone"), "unavailable: file gone"),
            (CoreError::internal("bug"), "internal: bug"),
        ];

        for (error, text) in cases {
            assert_eq!(error.to_string(), text);
        }
    }

    #[test]
    fn constructors_pick_the_variant() {
        assert_eq!(CoreError::internal("bug"), CoreError::Internal { message: "bug".to_owned() });
        assert_eq!(
            CoreError::storage(String::from("disk full")),
            CoreError::Storage { message: "disk full".to_owned() }
        );
    }
}
