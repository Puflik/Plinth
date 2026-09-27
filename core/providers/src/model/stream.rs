use plinth_types::CoreError;

use crate::http::{is_header_name, is_header_value};

/// Что отдать плееру (plan.md 6.1: URL + заголовки): Media3 сам качает
/// поток по адресу. Адрес берётся перед каждым воспроизведением и нигде не
/// хранится — у YouTube он живёт часы.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StreamRequest {
    url: String,
    headers: Vec<(String, String)>,
}

impl StreamRequest {
    /// Только `http` и `https` (домашний сервер в сети — `http`), без
    /// пробелов и управляющих символов; заголовки — без переводов строки:
    /// их отдаёт провайдер, и подставить через них чужой заголовок нельзя.
    /// Адрес и значения в ошибку не попадают — в адресе бывает запрос.
    pub fn new(url: &str, headers: Vec<(String, String)>) -> Result<Self, CoreError> {
        if !is_web_url(url) {
            return Err(CoreError::parse("stream url: not an http(s) address"));
        }
        if let Some((name, _)) = headers.iter().find(|(name, value)| !is_header_name(name) || !is_header_value(value)) {
            let shown = if is_header_name(name) { name.as_str() } else { "?" };
            return Err(CoreError::parse(format!("stream header {shown}: not allowed")));
        }
        Ok(Self { url: url.to_owned(), headers })
    }

    pub fn url(&self) -> &str {
        &self.url
    }

    pub fn headers(&self) -> &[(String, String)] {
        &self.headers
    }
}

fn is_web_url(url: &str) -> bool {
    let lower = url.get(..8).map(str::to_ascii_lowercase).unwrap_or_default();
    let rest = if lower.starts_with("https://") {
        &url[8..]
    } else if lower.starts_with("http://") {
        &url[7..]
    } else {
        return false;
    };
    let host = rest.split(['/', '?', '#']).next().unwrap_or_default();
    !host.is_empty() && !url.chars().any(|c| c.is_whitespace() || c.is_control())
}

#[cfg(test)]
mod tests {
    use plinth_types::CoreError;

    use super::StreamRequest;

    fn header(name: &str, value: &str) -> (String, String) {
        (name.to_owned(), value.to_owned())
    }

    #[test]
    fn web_addresses_with_headers_pass() {
        let request = StreamRequest::new(
            "https://archive.org/download/gd77/gd77d1t01.flac",
            vec![header("User-Agent", "Plinth/0.2"), header("Range", "bytes=0-")],
        )
        .unwrap();

        assert_eq!(request.url(), "https://archive.org/download/gd77/gd77d1t01.flac");
        assert_eq!(request.headers(), [header("User-Agent", "Plinth/0.2"), header("Range", "bytes=0-")]);
        assert!(StreamRequest::new("http://192.168.1.5:4533/rest/stream?id=1", Vec::new()).is_ok());
        assert!(StreamRequest::new("HTTPS://Example.org", Vec::new()).is_ok());
    }

    #[test]
    fn other_schemes_and_broken_addresses_fail() {
        for bad in [
            "",
            "file:///sdcard/Music/a.flac",
            "content://media/1",
            "javascript:alert(1)",
            "https://",
            "https:///path",
            "https://archive.org/a b.flac",
            "https://archive.org/a\nb",
            "archive.org/download/x",
        ] {
            assert!(matches!(StreamRequest::new(bad, Vec::new()), Err(CoreError::Parse { .. })), "{bad:?}");
        }
    }

    #[test]
    fn header_injection_fails() {
        for bad in [
            header("X-A", "1\r\nCookie: stolen"),
            header("X-A", "1\n"),
            header("", "1"),
            header("X A", "1"),
            header("X:A", "1"),
        ] {
            assert!(
                matches!(StreamRequest::new("https://a.org", vec![bad.clone()]), Err(CoreError::Parse { .. })),
                "{bad:?}"
            );
        }
    }

    #[test]
    fn error_does_not_leak_the_address() {
        let Err(error) = StreamRequest::new("ftp://archive.org/secret query", Vec::new()) else { panic!("accepted") };

        assert!(!error.to_string().contains("secret"), "{error}");
    }
}
