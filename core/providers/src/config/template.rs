use plinth_types::CoreError;

use crate::http::encode_component;

/// Строка конфига с подстановками `{name}` (имена — строчная латиница и
/// `_`) и `{+name}` — путь, в котором `/` остаётся разделителем (RFC 6570:
/// файл Internet Archive в подкаталоге; `%2F` там — 404). Разбирается при
/// загрузке конфига: сломанный шаблон — ошибка конфига, а не запроса.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct Template(Vec<Part>);

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) enum Part {
    Text(String),
    Var(String),
    /// `{+name}`.
    Path(String),
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
            let inner = &rest[open + 1..open + close];
            let (name, path) = inner.strip_prefix('+').map_or((inner, false), |name| (name, true));
            if name.is_empty() || !name.bytes().all(|b| b.is_ascii_lowercase() || b == b'_') {
                return Err(CoreError::parse(format!("config template: bad name {name:?}")));
            }
            if open > 0 {
                parts.push(Part::Text(rest[..open].to_owned()));
            }
            parts.push(if path { Part::Path(name.to_owned()) } else { Part::Var(name.to_owned()) });
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

    /// Подстановки кодируются как часть адреса (`{+name}` — по сегментам),
    /// текст шаблона — как есть: для пути.
    pub(super) fn render_encoded(&self, vars: &[(&str, &str)]) -> Result<String, CoreError> {
        self.render(vars, true)
    }

    /// Всё как есть: значение параметра кодируется потом целиком.
    pub(super) fn render_raw(&self, vars: &[(&str, &str)]) -> Result<String, CoreError> {
        self.render(vars, false)
    }

    fn render(&self, vars: &[(&str, &str)], encode: bool) -> Result<String, CoreError> {
        let mut out = String::new();
        for part in &self.0 {
            let (name, path) = match part {
                Part::Text(text) => {
                    out.push_str(text);
                    continue;
                }
                Part::Var(name) => (name, false),
                Part::Path(name) => (name, true),
            };
            let (_, value) = vars
                .iter()
                .find(|(var, _)| var == name)
                .ok_or_else(|| CoreError::internal(format!("config template: no value for {{{name}}}")))?;
            match (encode, path) {
                (false, _) => out.push_str(value),
                (true, false) => out.push_str(&encode_component(value)),
                (true, true) => out.push_str(&value.split('/').map(encode_component).collect::<Vec<_>>().join("/")),
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
        for bad in ["a{id", "a}b", "{}", "{+}", "{++a}", "{Id}", "{id-2}", "{a{b}}"] {
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

    #[test]
    fn path_keeps_slashes_and_encodes_segments() {
        let template = Template::parse("https://archive.org/download/{id}/{+file}").unwrap();

        assert_eq!(template.parts()[3], Part::Path("file".to_owned()));
        assert_eq!(
            template.render_encoded(&[("id", "gd/77"), ("file", "disc 1/01 Кино.mp3")]).unwrap(),
            "https://archive.org/download/gd%2F77/disc%201/01%20%D0%9A%D0%B8%D0%BD%D0%BE.mp3"
        );
        assert_eq!(
            template.render_raw(&[("id", "x"), ("file", "a/b c")]).unwrap(),
            "https://archive.org/download/x/a/b c"
        );
    }
}
