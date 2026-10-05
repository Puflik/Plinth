//! Р1.5 (`docs/work/r1-5.md`): миграция, упавшая на данных, не запирает
//! ядро — база заново, лайки возвращает журнал.

use std::fs;
use std::io::{Seek, SeekFrom, Write};

use plinth_types::TrackId;

use crate::session::{Core, DATABASE};
use crate::testing::Scratch;

/// Схема уже последней версии, а заголовок говорит «1»: миграция 2 снова
/// создаёт свою таблицу и падает на данных — как ошибочная миграция у человека.
#[test]
fn a_failed_migration_does_not_lock_the_person_out() {
    let dir = Scratch::new();
    let track = TrackId::new();
    Core::open(dir.path()).unwrap().like(track).unwrap();
    // WAL нет — заголовок в файле последнее слово, правку ниже ничто не перекроет.
    assert!(!dir.0.join(format!("{DATABASE}-wal")).exists());
    let mut file = fs::OpenOptions::new().write(true).open(dir.0.join(DATABASE)).unwrap();
    file.seek(SeekFrom::Start(60)).unwrap();
    file.write_all(&1_u32.to_be_bytes()).unwrap();
    drop(file);

    let opened = Core::open(dir.path());

    assert!(opened.is_ok(), "the core is locked out: {:?}", opened.as_ref().err());
    let core = opened.unwrap();
    let report = core.startup_report().unwrap();
    assert!(report.database_recovered);
    assert!(report.restored_from_journal);
    assert!(core.user_data(track).unwrap().liked);
    drop(core);
    let prefix = format!("{DATABASE}.migration-failed-");
    let aside: Vec<String> = fs::read_dir(&dir.0)
        .unwrap()
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .filter(|n| n.starts_with(&prefix))
        .collect();
    assert_eq!(aside.len(), 1, "{aside:?}");
    assert!(!Core::open(dir.path()).unwrap().startup_report().unwrap().database_recovered);
}
