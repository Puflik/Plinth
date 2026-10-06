//! Ядро как объект (A3): база, журнал и файлы в одном каталоге данных
//! приложения. Kotlin открывает его один раз и держит всё время работы.
//!
//! - `library.db` — база: каталог и проекция журнала (B, C3);
//! - `journal/` — журнал, источник правды (ADR 0007). Его и только его
//!   зеркалит C4 и сохраняет Auto Backup;
//! - `device` — идентификатор этой установки, автор операций журнала. Лежит
//!   вне журнала: восстановленный на новом телефоне журнал не делает новую
//!   установку старой;
//! - `lock` — замок: два ядра на одних файлах — два писателя одного журнала.

use std::fs::{self, File, OpenOptions};
use std::io;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError, RwLock, TryLockError};

use plinth_library::db::{Checked, Database, IntegrityCheck};
use plinth_providers::Registry;
use plinth_sync::journal::{
    CatchUp, Journal, Op, catch_up, damaged_snapshot, describe_missing, rebuild, record_and_project, relink, start_over,
};
use plinth_types::{CoreError, DeviceId};

use crate::panic;
use crate::types::{IntegrityOutcome, StartupReport};

pub(crate) const DATABASE: &str = "library.db";
pub(crate) const JOURNAL: &str = "journal";
pub(crate) const DEVICE: &str = "device";
const LOCK: &str = "lock";

#[derive(uniffi::Object)]
pub struct Core {
    state: Mutex<State>,
    /// Скан идёт один: два разом добавили бы одни файлы дважды.
    scan: Mutex<()>,
    report: StartupReport,
    /// Файл базы: плановая проверка читает его на своём соединении (Р1.12).
    database: PathBuf,
    /// Очередь плановой проверки целостности пришла на это открытие и ещё не
    /// пройдена: [`Core::check_integrity`] снимает флаг — проверка раз за запуск.
    integrity_due: AtomicBool,
    /// Онлайн-источники (E3): реестр провайдеров поверх транспорта Kotlin;
    /// `None` — выключены. Отдельно от `state`: запрос в сеть не держит базу.
    online: RwLock<Option<Arc<Registry>>>,
    /// Замок держится, пока жив объект; закрытие файла его снимает.
    _lock: File,
}

pub(crate) struct State {
    pub(crate) db: Database,
    pub(crate) journal: Journal,
}

#[uniffi::export]
impl Core {
    /// Открывает ядро в каталоге `data_dir`, создавая его при первом запуске.
    ///
    /// Испорченная база, как и база, на которой не прошла миграция,
    /// откладывается в сторону и создаётся заново, отставшая от журнала —
    /// пересобирается из него; что из этого было, говорит
    /// [`Core::startup_report`]. Снимок журнала, который не читается, ядро не
    /// запирает: он откладывается копией рядом, журнал начинается заново с
    /// пользовательским из базы, у установки новое имя. Порчу находит и проверка прошлого запуска
    /// ([`Core::check_integrity`]): она оставляет метку, и это открытие
    /// откладывает файл. Саму плановую проверку открытие не делает — её зовут
    /// после него. Ошибка — каталог уже открыт (`Unavailable`),
    /// журнал новее приложения или его файл не прочесть, база новее приложения, сбой
    /// среды при копии или миграции (место, права, блокировка: файлы не
    /// тронуты, следующий запуск повторит), миграция не прошла на только что
    /// созданной базе (`Storage`).
    #[uniffi::constructor]
    pub fn open(data_dir: String) -> Result<Arc<Self>, CoreError> {
        panic::guard(|| Self::open_at(Path::new(&data_dir)).map(Arc::new))
    }

    pub fn startup_report(&self) -> Result<StartupReport, CoreError> {
        panic::guard(|| Ok(self.report))
    }

    /// Плановая проверка целостности базы (раз в 20 запусков), которую открытие
    /// не делает: Kotlin зовёт её в фоне после открытия. Идёт на своём
    /// соединении только для чтения и замков ядра не берёт — остальные вызовы
    /// не ждут. Не очередь этого запуска или уже проверено — `NotDue`, файл не
    /// читается. Порча — `Damaged`: ядро работает дальше, а следующее открытие
    /// откладывает базу и собирает её заново (метка рядом с файлом). Ошибка —
    /// проверка не смогла прочесть файл или записать метку; повторять её в
    /// этом запуске не нужно.
    pub fn check_integrity(&self) -> Result<IntegrityOutcome, CoreError> {
        panic::guard(|| {
            if !self.integrity_due.swap(false, Ordering::AcqRel) {
                return Ok(IntegrityOutcome::NotDue);
            }
            match Database::check_file(&self.database)? {
                Checked::Healthy => Ok(IntegrityOutcome::Healthy),
                Checked::Damaged { reason } => {
                    log::warn!("core: damage found; the database is replaced on the next open: {reason}");
                    Ok(IntegrityOutcome::Damaged)
                }
            }
        })
    }
}

impl Core {
    fn open_at(dir: &Path) -> Result<Self, CoreError> {
        fs::create_dir_all(dir).map_err(|e| io_error("create data dir", &e))?;
        let lock = lock(dir)?;
        let device = device(dir)?;
        let database = dir.join(DATABASE);
        let opened = Database::open(&database, IntegrityCheck::Scheduled)?;
        if let Some(recovery) = &opened.recovery {
            log::warn!(
                "core: the database was damaged or could not be migrated and is kept aside: {}",
                recovery.reason
            );
        }
        let (mut journal, journal_started_over) = open_journal(dir, device, &opened.db)?;
        let rebuilt = catch_up(&journal, &opened.db)? == CatchUp::Rebuilt;
        // Журнал до C4: трекам с данными — паспорта, иначе переустановка их не узнает.
        if let Err(error) = describe_missing(&mut journal, &opened.db) {
            log::error!("core: describing tracks of the journal failed: {error}");
        }
        // База пересобрана без каталога (порча, журнал из Auto Backup): сетевые
        // треки журнала заводятся по паспортам сразу — без разрешения на музыку
        // скана, который их перепривязал бы, не будет. Файлы узнает скан.
        if rebuilt && let Err(error) = relink(&mut journal, &opened.db) {
            log::error!("core: relink after a rebuild failed: {error}");
        }
        let report = StartupReport {
            database_recovered: opened.recovery.is_some(),
            // Пустой журнал «пересобирается» и при первом запуске — это не восстановление.
            restored_from_journal: rebuilt && journal.mark().seq > 0,
            journal_started_over,
        };
        log::info!("core opened: {report:?}, schema v{}", opened.db.schema_version()?);
        Ok(Self {
            state: Mutex::new(State { db: opened.db, journal }),
            scan: Mutex::new(()),
            report,
            database,
            integrity_due: AtomicBool::new(opened.integrity_due),
            online: RwLock::new(None),
            _lock: lock,
        })
    }

    /// Работа с базой и журналом под замком. Паника в прошлом вызове могла
    /// оставить документ журнала впереди файла и базы. Тогда сначала всё
    /// выравнивается по журналу в памяти: снимок на диск, проекция заново.
    pub(crate) fn with<T>(&self, work: impl FnOnce(&mut State) -> Result<T, CoreError>) -> Result<T, CoreError> {
        let mut state = match self.state.lock() {
            Ok(state) => state,
            Err(poisoned) => {
                let mut state = poisoned.into_inner();
                log::warn!("core: a call panicked mid-way; saving the journal and rebuilding the projection");
                state.journal.compact()?;
                rebuild(&state.journal, &state.db)?;
                self.state.clear_poison();
                state
            }
        };
        work(&mut state)
    }

    /// Реестр онлайн-источников; выключены — `None`.
    pub(crate) fn online(&self) -> Option<Arc<Registry>> {
        self.online.read().unwrap_or_else(PoisonError::into_inner).clone()
    }

    pub(crate) fn set_online(&self, registry: Option<Arc<Registry>>) {
        *self.online.write().unwrap_or_else(PoisonError::into_inner) = registry;
    }

    /// Право на скан; пока оно у кого-то, второй скан — `Unavailable`.
    pub(crate) fn scanning(&self) -> Result<MutexGuard<'_, ()>, CoreError> {
        match self.scan.try_lock() {
            Ok(guard) => Ok(guard),
            Err(TryLockError::WouldBlock) => Err(CoreError::unavailable("core: a scan is already running")),
            // Прошлый скан упал паникой: его записанные пачки целы, новый может идти.
            Err(TryLockError::Poisoned(poisoned)) => Ok(poisoned.into_inner()),
        }
    }

    /// Операция пользователя: в журнал, затем в базу.
    pub(crate) fn record(&self, op: &Op) -> Result<(), CoreError> {
        panic::guard(|| self.with(|state| record_and_project(&mut state.journal, &state.db, op)))
    }

    #[cfg(test)]
    pub(crate) fn panic_inside_the_lock(&self) -> Result<(), CoreError> {
        panic::guard(|| self.with(|_| panic!("on purpose, inside the lock")))
    }
}

fn io_error(what: &str, error: &io::Error) -> CoreError {
    CoreError::storage(format!("core: {what}: {error}"))
}

fn lock(dir: &Path) -> Result<File, CoreError> {
    let file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .write(true)
        .open(dir.join(LOCK))
        .map_err(|e| io_error("open lock", &e))?;
    match file.try_lock() {
        Ok(()) => Ok(file),
        Err(fs::TryLockError::WouldBlock) => Err(CoreError::unavailable("core: the data dir is already open")),
        Err(fs::TryLockError::Error(error)) => Err(io_error("lock", &error)),
    }
}

/// Журнал в каталоге данных; `true` — он начат заново. Снимок, который не
/// читается, не запирает ядро: журнал начинается с пользовательским из базы
/// `db` (`start_over`), а у установки новое имя — иначе копия в папке,
/// записанная до порчи, осталась бы «своей»: восстановление её пропускает, а
/// зеркало перезаписало бы при первом уходе в фон. Новое имя — до замены
/// снимка: обрыв между ними повторит всё, а обратный порядок оставил бы новый
/// журнал со старым именем. Прочее — ошибка как есть: снимок новее
/// приложения, файл не прочесть.
fn open_journal(dir: &Path, device: DeviceId, db: &Database) -> Result<(Journal, bool), CoreError> {
    let journal = dir.join(JOURNAL);
    match Journal::open(&journal, device) {
        Ok(opened) => Ok((opened, false)),
        Err(error) => {
            let Some(reason) = damaged_snapshot(&journal)? else { return Err(error) };
            log::warn!("core: the journal snapshot is unreadable, the journal starts over ({reason})");
            let device = renew_device(dir)?;
            start_over(&journal, device, db)?;
            Ok((Journal::open(&journal, device)?, true))
        }
    }
}

/// Идентификатор установки; нет его или не читается — новый.
fn device(dir: &Path) -> Result<DeviceId, CoreError> {
    let path = dir.join(DEVICE);
    match fs::read_to_string(&path) {
        Ok(text) => match text.trim().parse() {
            Ok(device) => return Ok(device),
            Err(error) => log::warn!("core: device id is unreadable, making a new one: {error}"),
        },
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(io_error("read device id", &error)),
    }
    renew_device(dir)
}

/// Новый идентификатор установки в файле `device`: временный файл, затем
/// переименование — в любой момент в файле целый идентификатор.
fn renew_device(dir: &Path) -> Result<DeviceId, CoreError> {
    let path = dir.join(DEVICE);
    let device = DeviceId::new();
    let tmp = dir.join(format!("{DEVICE}.tmp"));
    fs::write(&tmp, device.to_string())
        .and_then(|()| fs::rename(&tmp, &path))
        .map_err(|e| io_error("write device id", &e))?;
    Ok(device)
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::io::{Seek, SeekFrom, Write};

    use plinth_types::{CoreError, TrackId};

    use super::{Core, DATABASE, DEVICE};
    use crate::testing::Scratch;

    #[test]
    fn a_fresh_core_has_nothing_to_restore() {
        let dir = Scratch::new();

        let core = Core::open(dir.path()).unwrap();

        let report = core.startup_report().unwrap();
        assert!(!report.database_recovered);
        assert!(!report.restored_from_journal);
        for file in [DATABASE, "journal", DEVICE] {
            assert!(dir.0.join(file).exists(), "{file}");
        }
    }

    /// Устройство — эта установка: одно и то же от запуска к запуску.
    #[test]
    fn the_device_is_the_same_on_every_open() {
        let dir = Scratch::new();
        drop(Core::open(dir.path()).unwrap());
        let first = fs::read_to_string(dir.0.join(DEVICE)).unwrap();

        drop(Core::open(dir.path()).unwrap());

        assert_eq!(fs::read_to_string(dir.0.join(DEVICE)).unwrap(), first);
        assert_eq!(first.trim().len(), 36);
    }

    /// Два ядра на одних файлах — два писателя одного журнала. Второе не откроется.
    #[test]
    fn the_same_files_open_once() {
        let dir = Scratch::new();
        let first = Core::open(dir.path()).unwrap();

        let second = Core::open(dir.path());

        assert!(matches!(second, Err(CoreError::Unavailable { .. })));
        drop(first);
        assert!(Core::open(dir.path()).is_ok());
    }

    #[test]
    fn user_data_survives_a_reopen() {
        let dir = Scratch::new();
        let track = TrackId::new();
        Core::open(dir.path()).unwrap().like(track).unwrap();

        let core = Core::open(dir.path()).unwrap();

        assert!(core.user_data(track).unwrap().liked);
        assert!(!core.startup_report().unwrap().restored_from_journal);
    }

    #[test]
    fn a_deleted_database_comes_back_from_the_journal() {
        let dir = Scratch::new();
        let track = TrackId::new();
        Core::open(dir.path()).unwrap().like(track).unwrap();
        for suffix in ["", "-wal", "-shm"] {
            let _ = fs::remove_file(dir.0.join(format!("{DATABASE}{suffix}")));
        }

        let core = Core::open(dir.path()).unwrap();

        assert!(core.startup_report().unwrap().restored_from_journal);
        assert!(core.user_data(track).unwrap().liked);
    }

    /// Испорченная база уходит в карантин, лайки возвращает журнал.
    #[test]
    fn a_corrupted_database_is_recovered_without_loss() {
        let dir = Scratch::new();
        let track = TrackId::new();
        Core::open(dir.path()).unwrap().like(track).unwrap();
        let mut file = fs::OpenOptions::new().write(true).open(dir.0.join(DATABASE)).unwrap();
        file.seek(SeekFrom::Start(0)).unwrap();
        file.write_all(&[0xde; 4096]).unwrap();
        drop(file);

        let core = Core::open(dir.path()).unwrap();

        let report = core.startup_report().unwrap();
        assert!(report.database_recovered);
        assert!(report.restored_from_journal);
        assert!(core.user_data(track).unwrap().liked);
    }

    /// Паника посреди вызова не оставляет ядро сломанным: следующий вызов
    /// сворачивает журнал, пересобирает проекцию и работает.
    #[test]
    fn a_panic_mid_call_does_not_break_the_core() {
        let dir = Scratch::new();
        let core = Core::open(dir.path()).unwrap();
        let (before, after) = (TrackId::new(), TrackId::new());
        core.like(before).unwrap();

        let result = core.panic_inside_the_lock();
        core.like(after).unwrap();

        assert!(matches!(result, Err(CoreError::Internal { .. })));
        assert!(core.user_data(before).unwrap().liked);
        assert!(core.user_data(after).unwrap().liked);
    }
}
