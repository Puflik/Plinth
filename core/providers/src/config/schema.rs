use std::collections::BTreeMap;
use std::time::Duration;

use plinth_types::{Bitrate, CoreError, Format, ProviderId};
use serde::Deserialize;

use super::template::{Part, Template};
use crate::http::{HttpPolicy, encode_component, is_header_name, is_header_value};
use crate::model::HealthPolicy;

/// Конфиг провайдера (E1.3): всё, что у провайдера меняется, — в данных.
/// Эндпоинты с подстановками, общие заголовки, имена полей ответа, метки
/// форматов, строки-образцы (префиксы, значения флагов), таймауты и пороги
/// здоровья. Логика разбора остаётся в коде провайдера.
///
/// Формат — JSON с номером схемы. Незнакомое поле — ошибка: опечатка во
/// встроенном конфиге ловится тестом, а новое поле в удалённом конфиге
/// (v1.0) приходит с новым номером схемы, который старое приложение честно
/// отвергает.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProviderConfig {
    pub provider: ProviderId,
    /// К каждому запросу провайдера.
    pub headers: Vec<(String, String)>,
    pub http: HttpPolicy,
    pub health: HealthPolicy,
    endpoints: BTreeMap<String, Endpoint>,
    fields: BTreeMap<String, String>,
    formats: BTreeMap<String, (Format, Option<Bitrate>)>,
    patterns: BTreeMap<String, String>,
}

/// Эндпоинт: адрес с подстановками в пути и параметры запроса. Параметры
/// идут по алфавиту — адрес одинаков от запуска к запуску, и записанный
/// ответ находится по нему.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Endpoint {
    url: Template,
    query: Vec<(String, Template)>,
}

impl ProviderConfig {
    pub const SCHEMA: u32 = 1;

    pub fn parse(json: &str) -> Result<Self, CoreError> {
        let raw: Raw = serde_json::from_str(json).map_err(|e| CoreError::parse(format!("provider config: {e}")))?;
        if raw.schema != Self::SCHEMA {
            return Err(CoreError::parse(format!(
                "provider config: schema {}, this app reads {}{}",
                raw.schema,
                Self::SCHEMA,
                if raw.schema > Self::SCHEMA { " (config is newer)" } else { "" }
            )));
        }
        let provider = ProviderId::new(&raw.provider)?;
        if raw.endpoints.is_empty() {
            return Err(CoreError::parse("provider config: no endpoints"));
        }
        let endpoints = raw
            .endpoints
            .into_iter()
            .map(|(name, endpoint)| Endpoint::parse(&name, endpoint).map(|endpoint| (name, endpoint)))
            .collect::<Result<_, _>>()?;
        if let Some((name, _)) =
            raw.headers.iter().find(|(name, value)| !is_header_name(name) || !is_header_value(value))
        {
            let shown = if is_header_name(name) { name.as_str() } else { "?" };
            return Err(CoreError::parse(format!("provider config: header {shown} is not allowed")));
        }
        if raw.fields.iter().chain(&raw.patterns).any(|(name, value)| name.is_empty() || value.is_empty()) {
            return Err(CoreError::parse("provider config: empty field or pattern"));
        }
        let formats = raw
            .formats
            .into_iter()
            .map(|(label, ours)| parse_format(&ours).map(|format| (label, format)))
            .collect::<Result<_, _>>()?;
        Ok(Self {
            provider,
            headers: raw.headers.into_iter().collect(),
            http: raw.http.policy()?,
            health: raw.health.policy()?,
            endpoints,
            fields: raw.fields,
            formats,
            patterns: raw.patterns,
        })
    }

    /// Нет такого — ошибка в коде провайдера или в его встроенном конфиге.
    pub fn endpoint(&self, name: &str) -> Result<&Endpoint, CoreError> {
        self.endpoints.get(name).ok_or_else(|| CoreError::internal(format!("provider config: no endpoint {name}")))
    }

    /// Имя поля в ответе провайдера.
    pub fn field(&self, name: &str) -> Result<&str, CoreError> {
        self.fields
            .get(name)
            .map(String::as_str)
            .ok_or_else(|| CoreError::internal(format!("provider config: no field {name}")))
    }

    /// Наш формат по метке провайдера (`"VBR MP3"`, `"64Kbps MP3"`) и
    /// битрейт, если метка его называет. Незнакомая метка — не звук или звук,
    /// который мы не играем (Shorten).
    pub fn format(&self, label: &str) -> Option<(Format, Option<Bitrate>)> {
        self.formats.get(label).copied()
    }

    /// Строка-образец: префикс идентификатора, значение флага.
    pub fn pattern(&self, name: &str) -> Result<&str, CoreError> {
        self.patterns
            .get(name)
            .map(String::as_str)
            .ok_or_else(|| CoreError::internal(format!("provider config: no pattern {name}")))
    }
}

/// `"flac"`, `"mp3"`, `"mp3@64"` — формат и, после `@`, битрейт в кбит/с.
fn parse_format(ours: &str) -> Result<(Format, Option<Bitrate>), CoreError> {
    let broken = || CoreError::parse(format!("provider config: format {ours:?}"));
    let (name, kbps) = match ours.split_once('@') {
        Some((name, kbps)) => (name, Some(kbps.parse::<u32>().ok().filter(|k| *k > 0).ok_or_else(broken)?)),
        None => (ours, None),
    };
    let format = match name {
        "flac" => Format::Flac,
        "alac" => Format::Alac,
        "wav" => Format::Wav,
        "aiff" => Format::Aiff,
        "mp3" => Format::Mp3,
        "aac" => Format::Aac,
        "vorbis" => Format::Vorbis,
        "opus" => Format::Opus,
        _ => return Err(broken()),
    };
    Ok((format, kbps.map(Bitrate::kbps)))
}

impl Endpoint {
    /// Только `https`, и хост — текстом шаблона, до первой подстановки:
    /// подставленное значение не уведёт запрос на другой сервер. Параметры —
    /// в `query`, не в адресе.
    fn parse(name: &str, raw: RawEndpoint) -> Result<Self, CoreError> {
        let broken = |why: &str| CoreError::parse(format!("provider config: endpoint {name}: {why}"));
        let url = Template::parse(&raw.url)?;
        let host_is_fixed = match url.parts().first() {
            Some(Part::Text(text)) => text.strip_prefix("https://").is_some_and(|rest| {
                let host = rest.split('/').next().unwrap_or_default();
                !host.is_empty() && (rest.contains('/') || url.parts().len() == 1)
            }),
            _ => false,
        };
        if !host_is_fixed {
            return Err(broken("url must start with https:// and a fixed host"));
        }
        let text_has = |needle: char| url.parts().iter().any(|p| matches!(p, Part::Text(t) if t.contains(needle)));
        if text_has('?') || text_has('#') || raw.url.chars().any(|c| c.is_whitespace() || c.is_control()) {
            return Err(broken("parameters go to \"query\", not into the url"));
        }
        let query = raw
            .query
            .into_iter()
            .map(|(key, value)| Template::parse(&value).map(|value| (key, value)))
            .collect::<Result<_, _>>()?;
        Ok(Self { url, query })
    }

    /// Адрес с подстановками `vars`: в пути значение кодируется как сегмент,
    /// в параметре — всё значение целиком.
    pub fn url(&self, vars: &[(&str, &str)]) -> Result<String, CoreError> {
        let mut url = self.url.render_encoded(vars)?;
        for (index, (key, value)) in self.query.iter().enumerate() {
            url.push(if index == 0 { '?' } else { '&' });
            url.push_str(&encode_component(key));
            url.push('=');
            url.push_str(&encode_component(&value.render_raw(vars)?));
        }
        Ok(url)
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Raw {
    schema: u32,
    provider: String,
    #[serde(default)]
    headers: BTreeMap<String, String>,
    endpoints: BTreeMap<String, RawEndpoint>,
    #[serde(default)]
    fields: BTreeMap<String, String>,
    #[serde(default)]
    formats: BTreeMap<String, String>,
    #[serde(default)]
    patterns: BTreeMap<String, String>,
    #[serde(default)]
    http: RawHttp,
    #[serde(default)]
    health: RawHealth,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RawEndpoint {
    url: String,
    #[serde(default)]
    query: BTreeMap<String, String>,
}

#[derive(Deserialize, Default)]
#[serde(deny_unknown_fields)]
struct RawHttp {
    timeout_ms: Option<u64>,
    attempts: Option<u32>,
    backoff_ms: Option<u64>,
}

#[derive(Deserialize, Default)]
#[serde(deny_unknown_fields)]
struct RawHealth {
    down_after: Option<u32>,
    down_for_s: Option<u64>,
}

impl RawHttp {
    /// Таймаут 1 мс – 2 мин, попыток 1–10, пауза до минуты.
    fn policy(&self) -> Result<HttpPolicy, CoreError> {
        let default = HttpPolicy::default();
        let timeout = in_range("http.timeout_ms", self.timeout_ms, 1..=120_000)?.map(Duration::from_millis);
        let attempts = in_range("http.attempts", self.attempts, 1..=10)?;
        let backoff = in_range("http.backoff_ms", self.backoff_ms, 0..=60_000)?.map(Duration::from_millis);
        Ok(HttpPolicy {
            timeout: timeout.unwrap_or(default.timeout),
            attempts: attempts.unwrap_or(default.attempts),
            backoff: backoff.unwrap_or(default.backoff),
            ..default
        })
    }
}

impl RawHealth {
    /// Отказов до `Down` 1–100, `Down` — от секунды до суток.
    fn policy(&self) -> Result<HealthPolicy, CoreError> {
        let default = HealthPolicy::default();
        let down_after = in_range("health.down_after", self.down_after, 1..=100)?;
        let down_for = in_range("health.down_for_s", self.down_for_s, 1..=86_400)?.map(Duration::from_secs);
        Ok(HealthPolicy {
            down_after: down_after.unwrap_or(default.down_after),
            down_for: down_for.unwrap_or(default.down_for),
        })
    }
}

fn in_range<T: PartialOrd + std::fmt::Display>(
    name: &str,
    value: Option<T>,
    range: std::ops::RangeInclusive<T>,
) -> Result<Option<T>, CoreError> {
    match value {
        Some(value) if !range.contains(&value) => Err(CoreError::parse(format!(
            "provider config: {name} = {value}, expected {}..={}",
            range.start(),
            range.end()
        ))),
        value => Ok(value),
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use plinth_types::{Bitrate, CoreError, Format, ProviderId};

    use super::ProviderConfig;
    use crate::http::HttpPolicy;
    use crate::model::HealthPolicy;

    const FULL: &str = r#"{
        "schema": 1,
        "provider": "archive.org",
        "headers": { "Accept": "application/json" },
        "endpoints": {
            "search": {
                "url": "https://archive.org/advancedsearch.php",
                "query": { "q": "{query} AND mediatype:audio", "rows": "{limit}", "output": "json" }
            },
            "metadata": { "url": "https://archive.org/metadata/{id}" }
        },
        "fields": { "title": "title", "artist": "creator" },
        "formats": { "Flac": "flac", "VBR MP3": "mp3", "64Kbps MP3": "mp3@64" },
        "patterns": { "mbid": "urn:mb_recording_id:" },
        "http": { "timeout_ms": 10000, "attempts": 2, "backoff_ms": 250 },
        "health": { "down_after": 4, "down_for_s": 120 }
    }"#;

    fn minimal(endpoint: &str) -> String {
        format!(r#"{{ "schema": 1, "provider": "archive.org", "endpoints": {{ "e": {endpoint} }} }}"#)
    }

    fn parse_error(json: &str) -> String {
        match ProviderConfig::parse(json) {
            Err(CoreError::Parse { message }) => message,
            other => panic!("expected a parse error, got {other:?}"),
        }
    }

    #[test]
    fn full_config_builds_addresses() {
        let config = ProviderConfig::parse(FULL).unwrap();

        let search = config.endpoint("search").unwrap().url(&[("query", "grateful dead"), ("limit", "10")]).unwrap();
        let metadata = config.endpoint("metadata").unwrap().url(&[("id", "gd77/a b")]).unwrap();

        assert_eq!(config.provider, ProviderId::new("archive.org").unwrap());
        assert_eq!(
            search,
            "https://archive.org/advancedsearch.php?output=json&q=grateful%20dead%20AND%20mediatype%3Aaudio&rows=10"
        );
        assert_eq!(metadata, "https://archive.org/metadata/gd77%2Fa%20b");
        assert_eq!(config.headers, [("Accept".to_owned(), "application/json".to_owned())]);
        assert_eq!(config.field("artist"), Ok("creator"));
    }

    #[test]
    fn http_and_health_come_from_the_config() {
        let config = ProviderConfig::parse(FULL).unwrap();

        assert_eq!(
            config.http,
            HttpPolicy {
                timeout: Duration::from_secs(10),
                attempts: 2,
                backoff: Duration::from_millis(250),
                ..HttpPolicy::default()
            }
        );
        assert_eq!(config.health, HealthPolicy { down_after: 4, down_for: Duration::from_secs(120) });
    }

    #[test]
    fn missing_sections_take_defaults() {
        let config = ProviderConfig::parse(&minimal(r#"{ "url": "https://archive.org" }"#)).unwrap();

        assert_eq!(config.http, HttpPolicy::default());
        assert_eq!(config.health, HealthPolicy::default());
        assert!(config.headers.is_empty());
        assert_eq!(config.endpoint("e").unwrap().url(&[]).unwrap(), "https://archive.org");
    }

    #[test]
    fn other_schemas_are_refused() {
        let newer = FULL.replace("\"schema\": 1", "\"schema\": 2");
        let zero = FULL.replace("\"schema\": 1", "\"schema\": 0");

        assert!(parse_error(&newer).contains("newer"), "{}", parse_error(&newer));
        assert!(!parse_error(&zero).contains("newer"));
    }

    #[test]
    fn typos_and_broken_json_are_refused() {
        parse_error(&FULL.replace("\"fields\"", "\"feilds\""));
        parse_error(&FULL.replace("\"timeout_ms\"", "\"timeout\""));
        parse_error("{ \"schema\": 1, ");
        parse_error(r#"{ "schema": 1, "provider": "archive.org", "endpoints": {} }"#);
        parse_error(&FULL.replace("\"archive.org\"", "\"Archive Org\""));
    }

    #[test]
    fn endpoints_stay_on_a_fixed_https_host() {
        for bad in [
            r#"{ "url": "http://archive.org/x" }"#,
            r#"{ "url": "{base}/x" }"#,
            r#"{ "url": "https://{host}/x" }"#,
            r#"{ "url": "https://archive.org{suffix}" }"#,
            r#"{ "url": "https:///x" }"#,
            r#"{ "url": "https://archive.org/x?q={query}" }"#,
            r#"{ "url": "https://archive.org/x#top" }"#,
            r#"{ "url": "https://archive.org/a b" }"#,
            r#"{ "url": "https://archive.org/{Bad}" }"#,
            r#"{ "url": "https://archive.org/x", "query": { "q": "{query" } }"#,
        ] {
            parse_error(&minimal(bad));
        }
    }

    #[test]
    fn headers_are_checked() {
        parse_error(&FULL.replace("\"Accept\"", "\"Bad Header\""));
        parse_error(&FULL.replace("application/json", "a\\r\\nCookie: x"));
    }

    #[test]
    fn limits_are_checked() {
        parse_error(&FULL.replace("\"attempts\": 2", "\"attempts\": 0"));
        parse_error(&FULL.replace("\"attempts\": 2", "\"attempts\": 11"));
        parse_error(&FULL.replace("\"timeout_ms\": 10000", "\"timeout_ms\": 0"));
        parse_error(&FULL.replace("\"down_after\": 4", "\"down_after\": 0"));
        parse_error(&FULL.replace("\"down_for_s\": 120", "\"down_for_s\": 0"));
    }

    #[test]
    fn unknown_endpoint_or_field_is_a_bug() {
        let config = ProviderConfig::parse(FULL).unwrap();

        assert!(matches!(config.endpoint("charts"), Err(CoreError::Internal { .. })));
        assert!(matches!(config.field("year"), Err(CoreError::Internal { .. })));
        assert!(matches!(config.endpoint("metadata").unwrap().url(&[]), Err(CoreError::Internal { .. })));
    }

    #[test]
    fn formats_and_patterns_come_from_the_config() {
        let config = ProviderConfig::parse(FULL).unwrap();

        assert_eq!(config.format("Flac"), Some((Format::Flac, None)));
        assert_eq!(config.format("VBR MP3"), Some((Format::Mp3, None)));
        assert_eq!(config.format("64Kbps MP3"), Some((Format::Mp3, Some(Bitrate::kbps(64)))));
        assert_eq!(config.format("Shorten"), None);
        assert_eq!(config.pattern("mbid"), Ok("urn:mb_recording_id:"));
        assert!(matches!(config.pattern("isrc"), Err(CoreError::Internal { .. })));
    }

    #[test]
    fn unknown_formats_and_empty_patterns_are_refused() {
        for bad in ["mp3@", "mp3@0", "mp3@x", "FLAC", "shorten"] {
            parse_error(&FULL.replace(r#""64Kbps MP3": "mp3@64""#, &format!(r#""64Kbps MP3": "{bad}""#)));
        }
        parse_error(&FULL.replace(r#""urn:mb_recording_id:""#, r#""""#));
    }
}
