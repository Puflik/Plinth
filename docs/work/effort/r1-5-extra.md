# r1-5 — миграция не запирает базу, копии не копятся

> План (замер effort, extra) по `docs/work/TEMPLATE.md`, шапка ветки опущена.
> **Откуда:** `tasks.md` Р1.5; находка 5 `docs/review/v0.2-review.md:76–83`; план §17.4; ADR 0011.

## Цель

Ни упавшая копия, ни миграция, упавшая на данных, не оставляют ядро неоткрываемым: порча уходит в карантин, неприменимая база — в сторону, ядро открывается на новой, пользовательское возвращает журнал. Одно обновление — одна копия базы.

## Контекст

Целиком: `core/library/src/db/migrations/runner.rs`, `db/backup.rs`, `db/integrity.rs`. Диапазоны: `db/connection.rs:1–145`, `core/ffi/src/session.rs:67–101` (как `Opened::recovery` превращается в `catch_up` и `database_recovered`; не менять). ADR 0011 — в поправленном виде из коммита тестов.

## Решение

| Где | Ошибка | Сейчас | Станет |
|---|---|---|---|
| копия `VACUUM INTO` | порча (`is_corruption`) | `Other` — отказ на каждом запуске | карантин `.corrupt-`, база заново |
| копия | любая другая (место, путь, ввод-вывод) | отказ | `log::warn!`, недописанная копия стирается, миграция идёт без копии |
| ротация копий | `read_dir` / `remove_file` | отказ, хотя копия уже снята | `log::warn!`, не ошибка |
| миграция | порча | карантин | без изменений |
| миграция | среда (`is_environment`) | отказ | отказ, файл не трогается: повтор после освобождения места пройдёт |
| миграция | прочее: данные, логика, ошибки Rust без кода SQLite | отказ навсегда | файл в сторону `.migration-failed-<мс>`, база заново |
| база новее кода | — | отказ | без изменений |

**Копия — одна на открытие**: перед первой ожидающей миграцией существующей базы, `-v<версия при открытии>`. Промежуточные состояния выводятся из неё тем же кодом. `KEEP = 3` остаётся: теперь это три обновления, а не три шага одного.

**«Откат на бэкап» (§17.4)** складывается из четырёх частей: откат транзакции (уже есть), нетронутый файл в стороне, проекция из журнала и каталог из скана. `catch_up` в `session.rs` пересоберёт проекцию сам: у новой базы нет `JournalMark`. Человек видит то же, что при порче: `database_recovered` → `libraryRestored()`.

Отвергнуто:
- **Поднимать новейший `.bak-*`.** При провале миграции в копии те же данные — упадёт снова. При порче копия от прошлого обновления: каталог устарел, проверка копии — ещё ~0,8 с (находка 13), вторая цепочка отказов. Пользовательское всё равно из журнала.
- **Оставить отказ** (ADR 0011): человек заперт до следующей версии.
- **Откладывать на любой ошибке**: нехватка места выбросила бы здоровую базу, а отложенный файл съел бы освобождённое место.

Сигнатуры (дословно):

```rust
// db/backup.rs: ошибка SQLite как есть; не-UTF-8 путь → rusqlite::Error::InvalidPath(target).
// Упал VACUUM INTO — стереть target, если он есть. Ротация — по возможности, сбои в лог.
pub(crate) fn backup(conn: &Connection, db: &Path, from: i32) -> rusqlite::Result<PathBuf>;

// db/integrity.rs
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Aside { Corrupt, MigrationFailed } // ".corrupt-<мс>" / ".migration-failed-<мс>"
pub(crate) fn quarantine(path: &Path, why: Aside) -> Result<PathBuf, CoreError>;
/// Ошибка среды, а не данных: повтор может пройти. DiskFull, OutOfMemory, SystemIoFailure,
/// DatabaseBusy, DatabaseLocked, ReadOnly, PermissionDenied, CannotOpen,
/// FileLockingProtocolFailed, OperationInterrupted.
pub(crate) fn is_environment(error: &rusqlite::Error) -> bool;

// db/migrations/runner.rs: вариант Backup удалён, порча при копии → Sqlite(error). migrate — как была.
pub(crate) enum MigrationError {
    Newer { found: i32, known: i32 },
    Failed { version: i32, name: &'static str, error: rusqlite::Error },
    Sqlite(rusqlite::Error),
}

// db/connection.rs (приватное). Порядок разбора Failed: порча → среда (Other) → MigrationFailed.
enum Failure { Aside(Aside, String), Other(CoreError) }
fn replace(path: &Path, why: Aside, reason: String) -> Result<Opened, CoreError>;
```

Причина для `MigrationFailed` — прежний текст `migration {version} ({name}) failed: {error}`. Публичный API (`Opened`, `Recovery`, `StartupReport`) не меняется.

## Инварианты

- Журнал не трогается ни на одном пути; пользовательское после любой замены базы возвращается из журнала без потерь.
- Файл, на котором упала миграция, не изменён (транзакция откатилась) и лежит рядом целиком, с `-wal`/`-shm`.
- Ошибка среды и база новее кода не меняют файлов и не создают новую базу.
- Замена однократна: следующее открытие даёт `recovery: None` и новых отложенных файлов не создаёт.
- Недописанной `.bak-*` после `open` нет; копий за одно открытие не больше одной, всего не больше `KEEP`.
- Выпущенные `m000*.rs` и эталоны `tests/fixtures/db/*` не меняются.

## Файлы

- **Менять:** `core/library/src/db/backup.rs`, `db/migrations/runner.rs`, `db/connection.rs`, `db/integrity.rs`.
- **Не трогать:** `core/ffi/src/session.rs`, `types.rs` (их меняет Р1.4, она же поправит лог «damaged»); `IntegrityCheck`, `due`, `problems`, ветку проверки в `open_checked` (это Р1.12: тот же `connection.rs`, идёт после слияния и берёт отсюда `replace`/`quarantine`); `m000*.rs`, эталоны, `Cargo.*`; прежние тесты в изменяемых файлах.
- **Защищённые тесты** (плюс строки `#[cfg(test)] mod r1_5_tests;` в `core/library/src/db/mod.rs` и `core/ffi/src/lib.rs`):
  - `core/library/tests/r1_5_migration_recovery.rs`
  - `core/library/src/db/r1_5_tests.rs`
  - `core/ffi/src/r1_5_tests.rs`
- В том же коммите Opus правит ADR 0011 и строки «Бэкап» и «Провал миграции» в §17.4 под это решение.

## Тесты

`r1_5_migration_recovery.rs` (копия `tests/fixtures/db/v1.db` во временный каталог, `IntegrityCheck::Skip`):
1. **Порча и ожидающая миграция (б).** 8 КБ `0xFF` с отступа 8192 → `Ok`, `recovery` есть, один `.corrupt-*`, ни одной `.bak-*`, версия последняя, треков 0. *Сейчас:* `Err("…malformed")` — копия упала, а `is_corruption` не видит обёрнутую ошибку.
2. **Миграция падает на данных (в).** В копию `CREATE TABLE scan_file (x)` → `Ok`. `reason` содержит `migration 2`; `quarantined` — `*.migration-failed-*`, в нём `user_version = 1`, треки на месте. Новая база — последней версии, треков 0. Второе открытие: `recovery: None`, отложенный файл по-прежнему один. *Сейчас:* `Err("migration 2 (scan_file) failed: table scan_file already exists")`.
3. **Одна копия на обновление (г).** Открытие v1 → `.bak-*` ровно одна, имя кончается на `-v1`, внутри `user_version = 1`. После повторного открытия она по-прежнему одна. *Сейчас:* три копии (`-v1`, `-v2`, `-v3`).

`db/r1_5_tests.rs` (свои `ONE`/`TWO`, доступ к `pub(crate)`):
4. **Копия не пишется (а).** Файловая база на v1 с данными; `migrate(&mut conn, &[ONE, TWO], Some(&dir.join("missing/plinth.db")))` → `Ok(vec![2])`, версия 2, данные целы. *Сейчас:* `Err(Backup(…))`.
5. **Классификатор.** `is_environment`: `SQLITE_FULL`, `SQLITE_IOERR`, `SQLITE_BUSY` → `true`; `SQLITE_ERROR`, `SQLITE_CONSTRAINT`, `SQLITE_CORRUPT`, `Error::ExecuteReturnedResults` → `false`. *Сейчас:* не компилируется — новый API, сигнатура выше.

`core/ffi/src/r1_5_tests.rs`:
6. **Человек не заперт.** `Core::open` → `like(track)` → `drop` → проверить, что `library.db-wal` нет. Записать байты 60..64 (`user_version`) = `[0,0,0,1]`. Снова `Core::open` → `Ok`, `database_recovered` и `restored_from_journal`, лайк на месте, рядом `library.db.migration-failed-*`. Третье открытие → `database_recovered: false`. *Сейчас:* `Err("migration 2 (scan_file) failed…")`.

## Проверки

```text
cd core && cargo test --workspace && cargo clippy --workspace --all-targets -- -D warnings && cargo fmt --all -- --check
```

Kotlin не меняется — Gradle не нужен.

## Готово, когда

- зелёные шесть защищённых тестов и все прежние (`connection.rs`, `runner.rs`, `backup.rs`, `tests/migrations.rs`, `core/sync/tests/rebuild.rs`, тесты `session.rs`), прежние — без правок;
- ни одна ошибка копии, кроме порчи, не доходит до вызывающего `Database::open`;
- новых `#[allow]` и `unwrap` вне тестов нет.

## Остановиться и написать, если

- тест 1 падает не на копии (порчу встретили раньше) или `VACUUM INTO` по испорченной базе возвращает не `DatabaseCorrupt`/`NotADatabase`;
- прежние тесты зеленеют только с их правкой или с правкой `session.rs`/`types.rs`;
- код ошибки, встреченный в тестах, не ложится однозначно в список `is_environment`;
- проверки упали дважды подряд.

## Риски и что я не проверил

- **Ничего не запускал.** Не проверено: оставляет ли упавший `VACUUM INTO` файл (стирание — на всякий случай); даёт ли цель в несуществующем каталоге `CannotOpen` (тесту 4 годится любой код, кроме порчи); удаляется ли `-wal` после `drop(Core)` и видит ли SQLite правку байтов 60..64 (тест 6 проверяет предусловие). Что каждый тест падает утверждением, Opus проверяет, когда пишет.
- **Расхождение с документами.** Решение меняет ADR 0011 («база не пересоздаётся») и §17.4 («откат на бэкап») — нужно слово автора. Если нужно именно поднятие `.bak-*` — отдельная задача: новейшая копия, прошедшая `quick_check`, миграции, иначе база заново.
- **Тихая деградация.** Ошибка в миграции 0.3 больше не запирает, а пересобирает каталог у всех (скан, обложки, отпечатки). Сторож один — эталоны в CI. Человек видит общее «библиотека восстановлена», без «выгрузить лог» из §17.4 — это UI, вместе с Р1.4.
- **Не покрыто:** паника в Rust-коде миграции по-прежнему отказывает на каждом запуске; `.corrupt-*` и `.migration-failed-*` — полные копии базы, их никто не чистит; миграция, падающая на пустой базе, даёт по отложенному файлу на запуск (ловят тесты свежей базы и эталонов).
- **`is_environment` — суждение:** `SystemIoFailure` запирает ядро при умирающей памяти, но новая база там тоже не создалась бы.
- **Граница:** Р1.12 — только после слияния (общий `connection.rs`); Р1.4 — параллельно, общее лишь строки `mod` в `core/ffi/src/lib.rs`.

## Отчёт исполнителя

_Заполняет исполнитель._
