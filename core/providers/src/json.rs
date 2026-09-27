//! Чтение ответов провайдеров (E2.5): все поля необязательны, тип поля не
//! гарантирован. Путь к полю — из конфига: `response.docs` — вглубь по
//! точкам, `artist|creator` — первое непустое из вариантов.

use serde_json::Value;

/// Поле по пути из конфига; пустое (`null`, пустая строка, пустой массив) —
/// как нет его: `artist|creator` возьмёт следующий вариант.
pub(crate) fn at<'a>(value: &'a Value, path: &str) -> Option<&'a Value> {
    path.split('|').find_map(|alternative| {
        let found = alternative.split('.').try_fold(value, |current, key| current.get(key))?;
        (!is_empty(found)).then_some(found)
    })
}

/// Поле по пути как есть, кроме `null`, — для строения ответа: пустой
/// список результатов — тоже ответ.
pub(crate) fn get<'a>(value: &'a Value, path: &str) -> Option<&'a Value> {
    path.split('|').find_map(|alternative| {
        alternative.split('.').try_fold(value, |current, key| current.get(key)).filter(|found| !found.is_null())
    })
}

fn is_empty(value: &Value) -> bool {
    match value {
        Value::Null => true,
        Value::String(text) => text.trim().is_empty(),
        Value::Array(items) => items.is_empty(),
        _ => false,
    }
}

/// Текст поля: строка без пробелов по краям, число — как написано, массив
/// строк — через запятую (у Internet Archive `creator` бывает и так).
/// Пустое — `None`.
pub(crate) fn text(value: &Value) -> Option<String> {
    let text = match value {
        Value::String(text) => text.trim().to_owned(),
        Value::Number(number) => number.to_string(),
        Value::Array(items) => items.iter().filter_map(text).collect::<Vec<_>>().join(", "),
        _ => return None,
    };
    (!text.is_empty()).then_some(text)
}

/// Текст по пути.
pub(crate) fn text_at(value: &Value, path: &str) -> Option<String> {
    at(value, path).and_then(text)
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::{at, get, text, text_at};

    #[test]
    fn walks_dotted_paths() {
        let doc = json!({ "response": { "docs": [1, 2] }, "empty": null });

        assert_eq!(at(&doc, "response.docs"), Some(&json!([1, 2])));
        assert_eq!(at(&doc, "response.missing"), None);
        assert_eq!(at(&doc, "empty"), None);
        assert_eq!(at(&json!("not an object"), "response"), None);
    }

    #[test]
    fn takes_the_first_filled_alternative() {
        let file = json!({ "artist": "  ", "creator": "H. Pearl", "title": "Oh Doctor" });

        assert_eq!(text_at(&file, "artist|creator"), Some("H. Pearl".to_owned()));
        assert_eq!(text_at(&file, "creator|artist"), Some("H. Pearl".to_owned()));
        assert_eq!(text_at(&file, "album|genre"), None);
        assert_eq!(text_at(&json!({ "artist": "A", "creator": "B" }), "artist|creator"), Some("A".to_owned()));
    }

    #[test]
    fn text_of_strings_numbers_and_arrays() {
        assert_eq!(text(&json!("  Piano  ")), Some("Piano".to_owned()));
        assert_eq!(text(&json!(2013)), Some("2013".to_owned()));
        assert_eq!(
            text(&json!(["NAOMI BROWN And Her Piano", "", "H. Pearl"])),
            Some("NAOMI BROWN And Her Piano, H. Pearl".to_owned())
        );
        for empty in [json!(""), json!("   "), json!([]), json!(null), json!(true), json!({ "a": 1 })] {
            assert_eq!(text(&empty), None, "{empty}");
        }
    }

    #[test]
    fn objects_are_found_even_though_they_are_not_text() {
        let doc = json!({ "metadata": { "title": "x" }, "files": [{ "name": "a" }] });

        assert!(at(&doc, "metadata").is_some());
        assert!(at(&doc, "files").is_some());
    }

    #[test]
    fn get_keeps_empty_lists() {
        let doc = json!({ "response": { "docs": [] }, "gone": null });

        assert_eq!(get(&doc, "response.docs"), Some(&json!([])));
        assert_eq!(at(&doc, "response.docs"), None);
        assert_eq!(get(&doc, "gone"), None);
        assert_eq!(get(&doc, "missing|response.docs"), Some(&json!([])));
    }
}
