//! Отложенные файлы базы (B2.3, Р9ж): `<база>.corrupt-<метка>` и `<база>.migration-failed-<метка>` вместе с
//! `-wal` и `-shm`. Карантин (`integrity::quarantine`) их оставляет, открытие базы чистит: остаются новейшие
//! каждого вида. «Новейший» — по числу в имени, а не по тексту (`corrupt-100` новее `corrupt-99`). Метка нового
//! файла не ниже прежних, и чистка не стирает только что отложенное, как бы ни шли часы; нигде, кроме чистки, имя
//! как время не разбирается. Имена не по образцу `<имя файла базы>.<вид>-<цифры>` не трогаются: чужая база,
//! копия `.bak-*`, метка `.damaged`, копия, которую человек переименовал, чтобы сохранить.

use std::io;
use std::path::Path;

use plinth_types::Timestamp;

/// Виды отложенных файлов и сколько новейших каждого остаётся.
const KEPT: [(&str, usize); 2] = [("corrupt", 2), ("migration-failed", 1)];

/// Боковые файлы базы в режиме WAL: отложенный файл уходит вместе с ними.
const SIDECARS: [&str; 2] = ["-wal", "-shm"];

/// Метка в имени: после `prefix` — только цифры, и число влезает в `u64`; остальные имена не наши.
/// Боковые `-wal`/`-shm` метки не имеют (после цифр стоит суффикс): считаются и стираются они вместе с основным.
fn stamp_of(name: &str, prefix: &str) -> Option<u64> {
    let stamp = name.strip_prefix(prefix)?;
    if stamp.is_empty() || !stamp.bytes().all(|byte| byte.is_ascii_digit()) {
        return None;
    }
    stamp.parse().ok()
}

/// Каталог базы; у пути без каталога родитель пуст — это текущий (как в `backup::backups`).
fn dir_of(db: &Path) -> &Path {
    db.parent().filter(|dir| !dir.as_os_str().is_empty()).unwrap_or(Path::new("."))
}

/// Имя файла базы как текст. Нет имени или оно не UTF-8 — отложенных по образцу рядом быть не может.
fn base_name(db: &Path) -> Option<&str> {
    db.file_name()?.to_str()
}

/// Начало имени основного отложенного файла: `<имя файла базы>.<вид>-`.
fn aside_prefix(base: &str, label: &str) -> String {
    format!("{base}.{label}-")
}

/// Метка нового отложенного файла вида `label`: `max(сейчас_мс, наибольшая метка основных файлов
/// этого вида рядом с `db` + 1)`. Каталог не прочитался или у `db` нет имени-текста — `сейчас_мс`.
/// Часы могли уйти назад, а чистка сравнивает метки: новый файл не должен оказаться старше прежних.
pub(crate) fn next_stamp(db: &Path, label: &str) -> u64 {
    let now = u64::try_from(Timestamp::now().as_millis()).unwrap_or(0);
    let Some(base) = base_name(db) else { return now };
    let prefix = aside_prefix(base, label);
    let newest = std::fs::read_dir(dir_of(db))
        .into_iter()
        .flatten()
        .filter_map(Result::ok)
        .filter_map(|entry| stamp_of(entry.file_name().to_str()?, &prefix))
        .max();
    newest.map_or(now, |newest| now.max(newest.saturating_add(1)))
}

/// Стирает лишние отложенные файлы рядом с `db`: остаются новейшие каждого вида. Ошибки — `log::warn!`,
/// наружу не выходят: чистка не должна мешать открытию, а ошибка на одном файле — остальным.
pub(crate) fn prune(db: &Path) {
    let Some(base) = base_name(db) else { return };
    let dir = dir_of(db);
    let entries = match std::fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(error) => {
            log::warn!("aside: the directory was not read, nothing is cleaned: {:?}", error.kind());
            return;
        }
    };
    let names: Vec<String> =
        entries.filter_map(Result::ok).filter_map(|entry| entry.file_name().into_string().ok()).collect();
    for (label, kept) in KEPT {
        let prefix = aside_prefix(base, label);
        let mut found: Vec<(u64, &String)> =
            names.iter().filter_map(|name| Some((stamp_of(name, &prefix)?, name))).collect();
        // Пары: сначала число, потом имя. Сортировка по тексту поставила бы `corrupt-99` после `corrupt-100`.
        found.sort();
        let surplus = found.len().saturating_sub(kept);
        for (_, name) in &found[..surplus] {
            remove_aside(dir, name);
        }
    }
}

/// Стирает один отложенный файл: сначала боковые, потом основной. Боковой не стёрся (не `NotFound`) — основной
/// остаётся до следующего открытия, чтобы боковые не осиротели; второй боковой при этом всё равно пробуется.
/// В лог — имя файла и `ErrorKind`, без пути (§17.3).
fn remove_aside(dir: &Path, name: &str) {
    let mut sidecar_stays = false;
    for suffix in SIDECARS {
        match std::fs::remove_file(dir.join(format!("{name}{suffix}"))) {
            Ok(()) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => {
                log::warn!(
                    "aside: {name}{suffix} was not removed ({:?}); {name} stays until the next open",
                    error.kind()
                );
                sidecar_stays = true;
            }
        }
    }
    if sidecar_stays {
        return;
    }
    if let Err(error) = std::fs::remove_file(dir.join(name)) {
        log::warn!("aside: {name} was not removed: {:?}", error.kind());
    }
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::path::PathBuf;

    use plinth_types::Timestamp;

    use super::{next_stamp, prune, stamp_of};

    /// Каталог теста; стирается в `Drop`.
    struct Dir(PathBuf);

    impl Dir {
        fn new(name: &str) -> Self {
            let dir = std::env::temp_dir().join(format!("plinth-aside-{name}-{}", std::process::id()));
            let _ = fs::remove_dir_all(&dir);
            fs::create_dir_all(&dir).unwrap();
            Self(dir)
        }

        fn db(&self) -> PathBuf {
            self.0.join("library.db")
        }

        fn touch(&self, name: &str) {
            fs::write(self.0.join(name), name).unwrap();
        }

        fn exists(&self, name: &str) -> bool {
            self.0.join(name).exists()
        }
    }

    impl Drop for Dir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn now() -> u64 {
        u64::try_from(Timestamp::now().as_millis()).unwrap()
    }

    #[test]
    fn a_stamp_is_digits_that_fit_u64_and_nothing_else() {
        let prefix = "library.db.corrupt-";
        let ours = [
            ("library.db.corrupt-0", 0),
            ("library.db.corrupt-1760000000000", 1_760_000_000_000),
            ("library.db.corrupt-007", 7),
            ("library.db.corrupt-18446744073709551615", u64::MAX),
        ];
        for (name, stamp) in ours {
            assert_eq!(stamp_of(name, prefix), Some(stamp), "{name}");
        }
        let not_ours = [
            "library.db.corrupt-",
            "library.db.corrupt-abc",
            "library.db.corrupt-12x",
            "library.db.corrupt-5.keep",
            "library.db.corrupt--5",
            "library.db.corrupt-+5",
            "library.db.corrupt-5 ",
            "library.db.corrupt-5-wal",
            "library.db.corrupt-5-shm",
            "library.db.corrupt-18446744073709551616",
            "library.db.corrupt",
            "library.db.migration-failed-5",
            "plinth.db.corrupt-5",
        ];
        for name in not_ours {
            assert_eq!(stamp_of(name, prefix), None, "{name}");
        }
    }

    /// Выше всех основных файлов того же вида у той же базы; боковые, другой вид и чужая база в счёт не идут.
    #[test]
    fn the_next_stamp_is_above_the_older_ones_of_its_kind() {
        let dir = Dir::new("next-above");
        for name in [
            "library.db.corrupt-99999999999990",
            "library.db.corrupt-99999999999995-wal",
            "library.db.corrupt-99999999999996-shm",
            "library.db.migration-failed-99999999999997",
            "plinth.db.corrupt-99999999999998",
        ] {
            dir.touch(name);
        }

        assert_eq!(next_stamp(&dir.db(), "corrupt"), 99_999_999_999_991);
        assert_eq!(next_stamp(&dir.db(), "migration-failed"), 99_999_999_999_998);
    }

    /// Прежние метки ниже часов — новая метка и есть часы, а не «прежняя + 1».
    #[test]
    fn the_next_stamp_is_the_clock_when_nothing_is_newer() {
        let dir = Dir::new("next-clock");
        dir.touch("library.db.corrupt-1");
        let before = now();

        let stamp = next_stamp(&dir.db(), "corrupt");

        assert!((before..=now()).contains(&stamp), "{stamp}");
    }

    /// Каталог не прочитался — метка просто время, а не отказ.
    #[test]
    fn an_unreadable_directory_gives_the_clock() {
        let missing = std::env::temp_dir().join(format!("plinth-aside-missing-{}", std::process::id()));
        let _ = fs::remove_dir_all(&missing);
        let before = now();

        let stamp = next_stamp(&missing.join("library.db"), "corrupt");
        prune(&missing.join("library.db"));

        assert!((before..=now()).contains(&stamp), "{stamp}");
        assert!(!missing.exists(), "the cleaning does not make directories");
    }

    /// Старейший по числу уходит вместе с боковыми, чужое остаётся: образец имён задан базой, к которой чистка зовётся.
    #[test]
    fn the_cleaning_is_bound_to_the_name_of_its_database() {
        let dir = Dir::new("prune-bound");
        for name in [
            "library.db.corrupt-9",
            "library.db.corrupt-9-wal",
            "library.db.corrupt-10",
            "library.db.corrupt-100",
            "plinth.db.corrupt-1",
            "plinth.db.corrupt-2",
            "plinth.db.corrupt-3",
        ] {
            dir.touch(name);
        }

        prune(&dir.db());

        assert!(!dir.exists("library.db.corrupt-9") && !dir.exists("library.db.corrupt-9-wal"));
        assert!(dir.exists("library.db.corrupt-10") && dir.exists("library.db.corrupt-100"));
        for name in ["plinth.db.corrupt-1", "plinth.db.corrupt-2", "plinth.db.corrupt-3"] {
            assert!(dir.exists(name), "{name} belongs to another database");
        }
    }

    /// Боковой, который не стереть (здесь вместо него каталог), оставляет основной файл на следующее открытие,
    /// но не мешает стереть второй боковой.
    #[test]
    fn a_stuck_sidecar_keeps_the_main_file_but_not_the_other_sidecar() {
        let dir = Dir::new("prune-stuck");
        for name in ["library.db.corrupt-1", "library.db.corrupt-1-shm", "library.db.corrupt-2", "library.db.corrupt-3"]
        {
            dir.touch(name);
        }
        fs::create_dir(dir.0.join("library.db.corrupt-1-wal")).unwrap();

        prune(&dir.db());

        assert!(dir.exists("library.db.corrupt-1"), "the main file waits for its sidecar");
        assert!(dir.0.join("library.db.corrupt-1-wal").is_dir());
        assert!(!dir.exists("library.db.corrupt-1-shm"), "the other sidecar is not held back");
        assert!(dir.exists("library.db.corrupt-2") && dir.exists("library.db.corrupt-3"));
    }
}
