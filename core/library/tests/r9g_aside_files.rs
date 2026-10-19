//! Р9ж (`docs/work/r9g.md`, ревью R1, находка 25): отложенные базы `corrupt-*` и `migration-failed-*` не
//! копятся. На каждом открытии остаются две новейшие `corrupt` и одна `migration-failed` — каждая вместе с
//! боковыми `-wal` и `-shm`. «Новейшая» — по числу в имени, а не по тексту имени; метка новой не ниже
//! прежних, как бы ни шли часы. Имена не по образцу чистка не трогает. База в каждом тесте — `library.db`,
//! как в ядре. Файл — тесты, править его исполнитель не вправе.

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::path::{Path, PathBuf};

use plinth_library::db::{Database, IntegrityCheck, Opened};
use rusqlite::Connection;

const DB: &str = "library.db";

/// Каталог теста; стирается в `Drop`.
struct Dir(PathBuf);

impl Dir {
    fn new(name: &str) -> Self {
        let dir = std::env::temp_dir().join(format!("plinth-r9g-aside-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        Self(dir)
    }

    /// Каталог с пустой целой базой; соединение закрыто.
    fn with_db(name: &str) -> Self {
        let dir = Self::new(name);
        drop(dir.open());
        dir
    }

    fn db(&self) -> PathBuf {
        self.0.join(DB)
    }

    fn open(&self) -> Opened {
        let opened = Database::open(&self.db(), IntegrityCheck::Skip);
        assert!(opened.is_ok(), "the database did not open: {:?}", opened.as_ref().err());
        opened.unwrap()
    }

    /// Отложенный файл вида `label` с меткой `stamp` и боковыми `-wal`, `-shm` — так, как его оставил карантин.
    fn aside(&self, label: &str, stamp: &str) {
        for name in files(label, &[stamp]) {
            fs::write(self.0.join(&name), &name).unwrap();
        }
    }

    /// Файл вместо базы — мусор: первое же открытие отложит его как порчу.
    fn garbage(&self) {
        fs::write(self.db(), vec![0xA5_u8; 64 * 1024]).unwrap();
    }

    /// Все имена каталога по порядку.
    fn names(&self) -> Vec<String> {
        let mut names: Vec<String> =
            fs::read_dir(&self.0).unwrap().map(|e| e.unwrap().file_name().to_string_lossy().into_owned()).collect();
        names.sort();
        names
    }

    /// Имена отложенных файлов обоих видов, с боковыми и с похожими на них, по порядку.
    fn asides(&self) -> Vec<String> {
        let corrupt = format!("{DB}.corrupt-");
        let failed = format!("{DB}.migration-failed-");
        self.names().into_iter().filter(|n| n.starts_with(&corrupt) || n.starts_with(&failed)).collect()
    }

    /// Метки основных файлов вида `label` (имя — `library.db.<вид>-<только цифры>`), по возрастанию.
    fn stamps(&self, label: &str) -> Vec<u64> {
        let prefix = format!("{DB}.{label}-");
        let mut found: Vec<u64> = self.names().iter().filter_map(|n| n.strip_prefix(&prefix)?.parse().ok()).collect();
        found.sort_unstable();
        found
    }

    /// Все имена каталога, кроме самой базы и её боковых, по порядку.
    fn others(&self) -> Vec<String> {
        let own = [DB.to_owned(), format!("{DB}-wal"), format!("{DB}-shm")];
        self.names().into_iter().filter(|n| !own.contains(n)).collect()
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

fn sorted(mut names: Vec<String>) -> Vec<String> {
    names.sort();
    names
}

/// Имена отложенных файлов вида `label` с метками `stamps`: основной и боковые, по порядку.
fn files(label: &str, stamps: &[&str]) -> Vec<String> {
    sorted(
        stamps
            .iter()
            .flat_map(|stamp| ["", "-shm", "-wal"].map(|suffix| format!("{DB}.{label}-{stamp}{suffix}")))
            .collect(),
    )
}

fn strings(names: &[&str]) -> Vec<String> {
    names.iter().map(|name| (*name).to_owned()).collect()
}

/// Метка в имени отложенного файла, который вернуло открытие.
fn stamp_of(path: &Path, label: &str) -> u64 {
    let name = path.file_name().unwrap().to_string_lossy().into_owned();
    let stamp = name.strip_prefix(&format!("{DB}.{label}-"));
    assert!(stamp.is_some(), "{name}: not a {label} aside");
    let number = stamp.unwrap().parse::<u64>();
    assert!(number.is_ok(), "{name}: the stamp is not a number");
    number.unwrap()
}

/// Здоровая база с накопленными отложенными файлами: чистка идёт на любом открытии, а не только на том,
/// что отложило файл. Остаются две новейшие `corrupt` и одна `migration-failed`, с боковыми. Новейшие —
/// по числу: 10 и 100 новее 9, а 100 новее 99 (по тексту имени было бы наоборот).
#[test]
fn surplus_asides_go_with_their_sidecars_at_any_open() {
    let dir = Dir::with_db("surplus");
    for stamp in ["9", "10", "100"] {
        dir.aside("corrupt", stamp);
    }
    for stamp in ["99", "100"] {
        dir.aside("migration-failed", stamp);
    }

    let opened = dir.open();

    assert!(opened.recovery.is_none(), "a healthy file is opened as it is");
    drop(opened);
    let expected = sorted([files("corrupt", &["10", "100"]), files("migration-failed", &["100"])].concat());
    assert_eq!(dir.asides(), expected);
    assert_eq!(dir.stamps("corrupt"), vec![10, 100]);
}

/// Чистка берёт только имена `<база>.corrupt-<цифры>` и `<база>.migration-failed-<цифры>`: всё прочее —
/// не её. Среди прочего — копия, которую человек переименовал, чтобы сохранить (`….keep`), метка, не
/// влезающая в `u64`, копии перед обновлением, файлы другой базы. Лишние отложенные при этом стираются.
#[test]
fn names_that_are_not_ours_stay() {
    let dir = Dir::with_db("foreign");
    for stamp in ["1", "2", "3"] {
        dir.aside("corrupt", stamp);
    }
    let foreign = [
        "library.db.corrupt-",
        "library.db.corrupt-abc",
        "library.db.corrupt-12x",
        "library.db.corrupt-99999999999999999999999",
        "library.db.corrupt-5.keep",
        "library.db.corrupt",
        "library.db.migration-failed-",
        "library.db.migration-failed-x",
        "library.db.bak-0000000000001-v1",
        "plinth.db.corrupt-1",
        "plinth.db.migration-failed-1",
        "notes.txt",
    ];
    for name in foreign {
        fs::write(dir.0.join(name), name).unwrap();
    }

    drop(dir.open());

    let expected = sorted([strings(&foreign), files("corrupt", &["2", "3"])].concat());
    assert_eq!(dir.others(), expected);
    for name in foreign {
        assert_eq!(fs::read(dir.0.join(name)).unwrap(), name.as_bytes(), "{name} is as it was");
    }
}

/// Часы ушли назад: в каталоге лежат отложенные с метками «из будущего». Новый файл получает метку выше
/// всех прежних, потому он остаётся, а стирается самый старый из прежних. Иначе чистка, сравнивающая
/// метки, стёрла бы только что отложенное.
#[test]
fn a_fresh_aside_survives_a_clock_that_went_back() {
    let dir = Dir::with_db("clock-back");
    for stamp in ["99999999999990", "99999999999991"] {
        dir.aside("corrupt", stamp);
    }
    dir.garbage();

    let opened = dir.open();

    let recovery = opened.recovery.as_ref().expect("the garbage is set aside");
    assert!(recovery.quarantined.exists(), "the fresh aside is not removed by the cleaning");
    let fresh = stamp_of(&recovery.quarantined, "corrupt");
    assert!(fresh > 99_999_999_999_991, "the stamp is not below the older ones: {fresh}");
    drop(opened);
    assert_eq!(dir.stamps("corrupt"), vec![99_999_999_999_991, fresh]);
    for name in files("corrupt", &["99999999999990"]) {
        assert!(!dir.exists(&name), "{name} is the oldest and goes with its sidecars");
    }
    for name in files("corrupt", &["99999999999991"]) {
        assert!(dir.exists(&name), "{name} stays");
    }
}

/// Порча раз за разом не копит файлы: после каждого открытия остаются две новейшие — те, что отложены
/// последними.
#[test]
fn repeated_corruption_keeps_the_disk_bounded() {
    let dir = Dir::with_db("repeated");
    let mut made = Vec::new();

    for round in 1..=5_usize {
        dir.garbage();
        let opened = dir.open();
        made.push(stamp_of(&opened.recovery.as_ref().expect("set aside").quarantined, "corrupt"));
        drop(opened);
        assert_eq!(dir.stamps("corrupt").len(), round.min(2), "round {round}: {:?}", dir.names());
    }

    assert_eq!(dir.stamps("corrupt"), made[3..].to_vec(), "the two set aside last are the ones kept");
    assert!(made.windows(2).all(|pair| pair[0] < pair[1]), "stamps grow with every aside: {made:?}");
}

/// Провал миграции на данных (её таблица уже есть) — тот же карантин, вид `migration-failed`: остаётся
/// один, новый, с меткой выше прежнего («из будущего»). Прежний стёрт вместе с боковыми.
#[test]
fn a_failed_migration_keeps_only_the_newest_aside() {
    let dir = Dir::new("migration-failed");
    fs::copy(Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/db/v1.db"), dir.db()).unwrap();
    let conn = Connection::open(dir.db()).unwrap();
    conn.execute_batch("CREATE TABLE scan_file (x)").unwrap();
    drop(conn);
    dir.aside("migration-failed", "99999999999991");

    let opened = dir.open();

    let recovery = opened.recovery.as_ref().expect("the file is set aside");
    assert!(recovery.reason.contains("migration 2 (scan_file)"), "{}", recovery.reason);
    assert!(recovery.quarantined.exists());
    let fresh = stamp_of(&recovery.quarantined, "migration-failed");
    assert!(fresh > 99_999_999_999_991, "the stamp is not below the older one: {fresh}");
    drop(opened);
    assert_eq!(dir.stamps("migration-failed"), vec![fresh]);
    for name in files("migration-failed", &["99999999999991"]) {
        assert!(!dir.exists(&name), "{name} goes with its sidecars");
    }
}

/// Стереть одно не вышло (здесь вместо файла каталог — `remove_file` откажет): это предупреждение, а не
/// ошибка открытия, и чистка идёт дальше — остальное лишнее стёрто.
#[test]
fn a_removal_that_fails_does_not_stop_the_rest() {
    let dir = Dir::with_db("stuck");
    fs::create_dir(dir.0.join("library.db.corrupt-1")).unwrap();
    for stamp in ["2", "3", "4"] {
        dir.aside("corrupt", stamp);
    }

    let opened = dir.open();

    assert!(opened.recovery.is_none());
    drop(opened);
    assert!(dir.0.join("library.db.corrupt-1").is_dir(), "what could not be removed is left");
    for name in files("corrupt", &["2"]) {
        assert!(!dir.exists(&name), "{name} is surplus and goes");
    }
    for name in files("corrupt", &["3", "4"]) {
        assert!(dir.exists(&name), "{name} stays");
    }
}

/// Сначала боковые, потом основной: если боковой не стёрся (здесь вместо него каталог), основной остаётся,
/// и следующее открытие повторит — боковой без своего основного не осиротеет навсегда. Прочее лишнее при
/// этом стёрто.
#[test]
fn a_sidecar_that_cannot_be_removed_keeps_its_main_file() {
    let dir = Dir::with_db("stuck-sidecar");
    for stamp in ["1", "2", "3", "4"] {
        dir.aside("corrupt", stamp);
    }
    fs::remove_file(dir.0.join("library.db.corrupt-2-wal")).unwrap();
    fs::create_dir(dir.0.join("library.db.corrupt-2-wal")).unwrap();

    let opened = dir.open();

    assert!(opened.recovery.is_none());
    drop(opened);
    assert!(dir.exists("library.db.corrupt-2"), "the main file waits for its sidecar");
    assert!(dir.0.join("library.db.corrupt-2-wal").is_dir());
    for name in files("corrupt", &["1"]) {
        assert!(!dir.exists(&name), "{name} is surplus and goes");
    }
    for name in files("corrupt", &["3", "4"]) {
        assert!(dir.exists(&name), "{name} stays");
    }
}
