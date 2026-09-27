use plinth_types::CoreError;

use crate::http::encode_component;

/// Строка конфига с подстановками `{name}` (имена — строчная латиница и
/// `_`). Разбирается при загрузке конфига: сломанный шаблон — ошибка
/// конфига, а не запроса.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct Template(Vec<Part>);

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) enum Part {
    Text(String),
    Var(String),
}

impl Template {
    pub(super) fn parse(text: &str) -> Result<Self, CoreError> {
        let mut parts = Vec::new();
        let mut rest = text;
        while let Some(open) = rest.find(['{', '}']) {
            if rest[open..].starts_with('}') {
                return Err(CoreError::parse("config template: '}' without '{'"));
            }
            let close = rest[open..].find('}').ok_or_else(|| CoreError::parse("config template: '{' without '}'"))?;
            let name = &rest[open + 1..open + close];
            if name.is_empty() || !name.bytes().all(|b| b.is_ascii_lowercase() || b == b'_') {
                return Err(CoreError::parse(format!("config template: bad name {name:?}")));
            }
            if open > 0 {
                parts.push(Part::Text(rest[..open].to_owned()));
            }
            parts.push(Part::Var(name.to_owned()));
            rest = &rest[open + close + 1..];
        }
        if !rest.is_empty() {
            parts.push(Part::Text(rest.to_owned()));
        }
        Ok(Self(parts))
    }

    pub(super) fn parts(&self) -> &[Part] {
        &self.0
    }

    /// Подстановки кодируются как часть адреса, текст шаблона — как есть:
    /// для пути.
    pub(super) fn render_encoded(&self, vars: &[(&str, &str)]) -> Result<String, CoreError> {
        self.render(vars, encode_component)
    }

    /// Всё как есть: значение параметра кодируется потом целиком.
    pub(super) fn render_raw(&self, vars: &[(&str, &str)]) -> Result<String, CoreError> {
        self.render(vars, str::to_owned)
    }

    fn render(&self, vars: &[(&str, &str)], value: impl Fn(&str) -> String) -> Result<String, CoreError> {
        let mut out = String::new();
        for part in &self.0 {
            match part {
                Part::Text(text) => out.push_str(text),
                Part::Var(name) => {
                    let (_, found) = vars
                        .iter()
                        .find(|(var, _)| var == name)
                        .ok_or_else(|| CoreError::internal(format!("config template: no value for {{{name}}}")))?;
                    out.push_str(&value(found));
                }
            }
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use plinth_types::CoreError;

    use super::{Part, Template};

    #[test]
    fn splits_text_and_names() {
        let template = Template::parse("https://archive.org/metadata/{id}/files{rest}").unwrap();

        assert_eq!(
            template.parts(),
            [
                Part::Text("https://archive.org/metadata/".to_owned()),
                Part::Var("id".to_owned()),
                Part::Text("/files".to_owned()),
                Part::Var("rest".to_owned()),
            ]
        );
        assert_eq!(Template::parse("").unwrap().parts(), []);
    }

    #[test]
    fn broken_templates_fail_to_load() {
        for bad in ["a{id", "a}b", "{}", "{Id}", "{id-2}", "{a{b}}"] {
            assert!(matches!(Template::parse(bad), Err(CoreError::Parse { .. })), "{bad}");
        }
    }

    #[test]
    fn renders_encoded_for_paths_and_raw_for_parameters() {
        let template = Template::parse("{query} AND mediatype:audio").unwrap();
        let path = Template::parse("https://a.org/m/{id}").unwrap();

        assert_eq!(template.render_raw(&[("query", "grateful dead")]).unwrap(), "grateful dead AND mediatype:audio");
        assert_eq!(path.render_encoded(&[("id", "gd77/a b")]).unwrap(), "https://a.org/m/gd77%2Fa%20b");
    }

    #[test]
    fn missing_value_is_a_bug_in_the_caller() {
        let template = Template::parse("https://a.org/m/{id}").unwrap();

        assert!(matches!(template.render_encoded(&[("query", "x")]), Err(CoreError::Internal { .. })));
    }
}
