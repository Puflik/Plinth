//! Поиск (`advancedsearch`, E2.1). Каждый результат — элемент: концерт,
//! альбом, пластинка; треки даёт `resolve`. Фильтр «только музыка» и поля
//! поиска — в шаблоне запроса конфига (ответ автора).

use plinth_types::CoreError;
use serde_json::Value;

use super::values;
use crate::config::ProviderConfig;
use crate::json::{get, text_at};
use crate::model::{ExternalId, ResultKind, SearchResult};

/// Текст запроса без синтаксиса Lucene: строчные буквы, цифры и апостроф,
/// остальное — пробел. `AND`, `OR`, `NOT` становятся словами, скобки и
/// кавычки из запроса не сломают шаблон `creator:({query})`.
pub(super) fn search_text(text: &str) -> String {
    let kept: String = text
        .chars()
        .flat_map(|c| {
            let keep = c.is_alphanumeric() || c == '\'';
            let lower: Vec<char> = if keep { c.to_lowercase().collect() } else { vec![' '] };
            lower
        })
        .collect();
    kept.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Результаты из ответа; элемент без идентификатора или с `/` в нём
/// пропускается, нет названия — идентификатор вместо него.
pub(super) fn results(body: &[u8], config: &ProviderConfig, limit: u32) -> Result<Vec<SearchResult>, CoreError> {
    let doc: Value = serde_json::from_slice(body).map_err(|_| CoreError::parse("archive.org search: not json"))?;
    let Some(Value::Array(docs)) = get(&doc, config.field("search_docs")?) else {
        return Err(CoreError::parse("archive.org search: no list of results"));
    };
    let identifier = config.field("identifier")?;
    let title = config.field("title")?;
    let creator = config.field("creator")?;
    let year = config.field("year")?;
    let limit = usize::try_from(limit).unwrap_or(usize::MAX);
    Ok(docs
        .iter()
        .filter_map(|doc| {
            let id = text_at(doc, identifier).filter(|id| !id.contains('/'))?;
            Some(SearchResult {
                external: ExternalId::new(&id).ok()?,
                kind: ResultKind::Album,
                title: text_at(doc, title).unwrap_or_else(|| id.clone()),
                artist: text_at(doc, creator),
                album: None,
                year: text_at(doc, year).as_deref().and_then(values::year),
                duration: None,
            })
        })
        .take(limit)
        .collect())
}

#[cfg(test)]
mod tests {
    use plinth_types::CoreError;

    use super::{results, search_text};
    use crate::config::ProviderConfig;
    use crate::internet_archive::CONFIG;

    fn config() -> ProviderConfig {
        ProviderConfig::parse(CONFIG).unwrap()
    }

    #[test]
    fn query_text_loses_lucene_syntax() {
        assert_eq!(search_text("  Grateful   Dead "), "grateful dead");
        assert_eq!(search_text("AC/DC"), "ac dc");
        assert_eq!(search_text("title:(x) OR \"y\" AND -z*"), "title x or y and z");
        assert_eq!(search_text("Guns N' Roses"), "guns n' roses");
        assert_eq!(search_text("Кино — Группа крови"), "кино группа крови");
        assert_eq!(search_text(" ()[]{}:!* "), "");
    }

    #[test]
    fn results_survive_missing_and_odd_fields() {
        let body = br#"{ "response": { "numFound": 5, "docs": [
            { "identifier": "MIXG031", "title": "[MIXG031] Intoxicated Piano - Vice Versa", "year": 2013 },
            { "identifier": "78_oh", "title": "\"OH DOCTOR\"", "creator": ["NAOMI BROWN And Her Piano", "H. Pearl"] },
            { "identifier": "untitled", "year": "0" },
            { "title": "no identifier" },
            { "identifier": "a/b", "title": "slash" },
            "not an object"
        ] } }"#;

        let found = results(body, &config(), 10).unwrap();

        let titles: Vec<&str> = found.iter().map(|r| r.title.as_str()).collect();
        assert_eq!(titles, ["[MIXG031] Intoxicated Piano - Vice Versa", "\"OH DOCTOR\"", "untitled"]);
        assert_eq!(found[0].artist, None);
        assert_eq!(found[0].year, Some(2013));
        assert_eq!(found[1].artist.as_deref(), Some("NAOMI BROWN And Her Piano, H. Pearl"));
        assert_eq!(found[2].year, None);
    }

    #[test]
    fn no_more_than_the_limit() {
        let body =
            br#"{ "response": { "docs": [ { "identifier": "a" }, { "identifier": "b" }, { "identifier": "c" } ] } }"#;

        assert_eq!(results(body, &config(), 2).unwrap().len(), 2);
    }

    #[test]
    fn empty_list_is_nothing_found_but_no_list_is_a_broken_answer() {
        assert_eq!(results(br#"{ "response": { "numFound": 0, "docs": [] } }"#, &config(), 10), Ok(Vec::new()));
        for bad in [
            &br#"{ "response": {} }"#[..],
            br#"{ "error": "x" }"#,
            b"<html></html>",
            br#"{ "response": { "docs": 5 } }"#,
        ] {
            assert!(matches!(results(bad, &config(), 10), Err(CoreError::Parse { .. })), "{bad:?}");
        }
    }
}
