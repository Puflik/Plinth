# r1-5 — миграции: сбой копии или миграции не запирает ядро

> Файл задачи для исполнителя (Sonnet). Пишет Opus; исполнитель меняет только
> раздел «Отчёт исполнителя». Порядок работы — `docs/work/README.md`.

- **Откуда:** `tasks.md` Р1.5; находка 5 (а–г) в `docs/review/v0.2-review.md`; план §17.4; ADR 0011. Замер effort, уровень max.

## Цель

Сбой копии или миграции на данных пользователя больше не запирает ядро: порча — карантин, миграция не прошла — файл целым в сторону, в обоих случаях новая база собирается из журнала; копия не снялась — миграции идут без неё. Одно обновление — одна законченная копия.

## Контекст

`core/library/src/db/migrations/runner.rs`, `db/backup.rs`, `db/integrity.rs`; `db/connection.rs:1-145`; `db/mod.rs:45-58`; `core/ffi/src/session.rs:68-101`; ADR 0011; `docs/plan/17-reliability.md:82-113`; `docs/review/v0.2-review.md:76-83`.

## Решение

1. **Одна копия на обновление, через временный файл.** Копия снимается один раз — перед первой ожидающей миграцией, и только если база была до этого открытия (`current > 0`); упавший шаг и так откатывает транзакция. `backup` удаляет застарелый `<база>.bak.tmp`, пишет в него `VACUUM INTO` и переименовывает в `<база>.bak-<мс:013>-v<from>`; ротация до `KEEP = 3`, её сбой — только `log::warn!`. При ошибке `.bak.tmp` удаляется. Зачем: SQLite не удаляет цель упавшего `VACUUM INTO` (`end_of_vacuum` в `sqlite3.c` 3.53.2). Сейчас при нехватке места (а) каждый запуск оставляет недописанный `.bak-*`: он съедает остаток места и в ротации считается копией.
2. **Сбой копии.** Порча (`is_corruption`) — `Err(MigrationError::Backup(e))` без миграций, дальше карантин (б). Сейчас код ошибки теряется в `CoreError`, поэтому (б) до карантина не доходит. Иная ошибка — `log::warn!` без путей (§17.3), миграции без копии (а).
3. **Провал миграции.** Порча — карантин, как сейчас. Сбой среды (`is_environmental`) — ошибка открытия, файл не тронут, следующий запуск повторит: новая база упёрлась бы в то же место, а отложенный файл держал бы его. Остальное — `Failure::Unmigratable` и `replace(path, "failed", reason)`: файл с `-wal`/`-shm` уходит в `<база>.failed-<мс>`, база создаётся заново, причина — `migration N (name) failed: …`. Не открылась и новая база — ошибка, второго откладывания нет. `session.rs` не меняется: флаг `database_recovered`, пересборка из журнала (`catch_up` → `Rebuilt`) и сообщение в Kotlin уже есть; каталог вернёт скан.
4. **«Откат на бэкап» (§17.4)** — транзакция вернула базу к состоянию до упавшего шага, и оно лежит целым в `.failed-…`; состояние до обновления — в `.bak-…`. Отвергнуто: вернуть `.bak` на место базы — та же миграция упадёт снова; поднимать старую копию при порче — каталог устарел, а задача требует пересборки из журнала.

```rust
// db/backup.rs — backups() и KEEP без изменений
#[derive(Debug)]
pub(crate) enum BackupError { Sqlite(rusqlite::Error), Io(String) }
pub(crate) fn backup(conn: &Connection, db: &Path, from: i32) -> Result<PathBuf, BackupError>;

// db/migrations/runner.rs — сигнатура migrate прежняя
pub(crate) enum MigrationError {
    Newer { found: i32, known: i32 },
    Failed { version: i32, name: &'static str, error: rusqlite::Error },
    Backup(rusqlite::Error), // было Backup(CoreError); только порча, в Failure — как Sqlite
    Sqlite(rusqlite::Error),
}

// db/integrity.rs
/// DiskFull, SystemIoFailure, CannotOpen, ReadOnly, PermissionDenied,
/// DatabaseBusy, DatabaseLocked, OutOfMemory.
pub(crate) fn is_environmental(error: &rusqlite::Error) -> bool;
/// `<имя>.<label>-<мс>` вместе с `-wal`/`-shm`; label — "corrupt" или "failed".
pub(crate) fn quarantine(path: &Path, label: &str) -> Result<PathBuf, CoreError>;

// db/connection.rs, приватное
enum Failure { Corrupt(String), Unmigratable(String), Other(CoreError) }
fn replace(path: &Path, label: &str, reason: String) -> Result<Opened, CoreError>;
```

Публичный API и FFI не меняются; в доках `Opened.recovery` и `Recovery` база «испорчена или миграция на ней не прошла».

**Границы.** Р1.12 (проверка целостности вне пути открытия) делит с Р1.5 `connection.rs` и `integrity.rs`: задачи идут по очереди, Р1.5 первой. Р1.5 не трогает `open_checked`, `IntegrityCheck`, `problems`, `count_launch` и место проверки; Р1.12 возьмёт готовые `quarantine` и `replace`. Р1.4 (снимок журнала, экран «ядро не открылось»): журнал, FFI и Kotlin здесь не трогаются; что человек видит при сбое среды, решает Р1.4.

## Инварианты

- Журнал не трогается. Файл откладывается целым, переименованием, с `-wal`/`-shm`. Удаляются только `.bak.tmp` и копии сверх `KEEP`.
- Ошибкой открытия из-за копий и миграций кончаются только три случая: база новее приложения (ADR 0011), сбой среды, провал миграции на свежей базе (его ловят эталоны в CI).
- За открытие — не больше одного откладывания. Второй запуск после восстановления — обычный: без `recovery` и новых файлов.
- `backups()` видит только законченные копии, по одной на обновление.
- Выпущенные миграции и эталоны `v1–v4` не меняются; в логах нет полных путей.

## Файлы

- **Менять:** `core/library/src/db/backup.rs`, `…/db/migrations/runner.rs`, `…/db/connection.rs`, `…/db/integrity.rs`, `…/db/mod.rs` (только доки).
- **Не трогать:** `core/ffi/**`, `core/sync/**`, `migrations/mod.rs` и `m000*.rs`, эталоны, `make_db_fixture.rs`, `tests/migrations.rs`, Kotlin, Cargo-файлы, ADR и план (правит Opus при слиянии).
- **Защищённые тесты:** `core/library/tests/r1_5_migration_recovery.rs`; `core/library/src/db/r1_5_tests.rs` и строка `#[cfg(test)] mod r1_5_tests;` в `db/mod.rs`; `core/ffi/src/r1_5_tests.rs` и такая же строка в `core/ffi/src/lib.rs`.

## Тесты

Все компилируются на текущем коде (существующий API и `rusqlite`) и падают на утверждении. В T1–T3 база — копия эталона в `plinth.db`.

| Тест | Вход → результат | Сейчас |
|---|---|---|
| T1 `an_upgrade_leaves_one_complete_backup` | `v1.db` + мусорный `plinth.db.bak.tmp` → `open(Now)`: без `recovery`; один `.bak-*`, на `-v1`, в нём `user_version` 1 и 3 трека; `.bak.tmp` нет | копий три (`-v1`, `-v2`, `-v3`), `.bak.tmp` лежит |
| T2 `damage_met_by_the_backup_is_quarantined` | `v1.db`, 8 КБ `0xFF` со смещения 8192 → `open(Skip)`: `Ok`, `recovery`, один `.corrupt-*`, ни одного имени с `.bak`; второй `open` без `recovery` | `Err(Storage "…malformed")` при каждом запуске (тест ревью) |
| T3 `a_migration_failing_on_the_data_sets_the_file_aside` | `v1.db` + `CREATE TABLE scan_file (x)` → `open(Skip)`: `Ok`; в `reason` — `migration 2 (scan_file)`; `quarantined` — `….failed-…`, в нём версия 1 и 3 трека; новая база пуста; один `.bak-…-v1`; второй `open` без `recovery` и новых файлов | `Err(Storage "migration 2 (scan_file) failed: …")` |
| T4 `an_unwritable_backup_does_not_stop_the_migration` (модуль) | база в памяти: `migrate([ONE])`, строка; `migrate([ONE, TWO], Some(<нет каталога>/plinth.db))` → `Ok(vec![2])` | `Err(Backup)` — замена (а): копия падает не от порчи |
| T5 `a_core_with_a_failing_migration_opens_from_the_journal` (ffi) | `Core::open`, `like(t)`, закрыть; `[0,0,0,1]` по смещению 60 `library.db` (`user_version`), миграция 2 упирается в `scan_file` → `Core::open`: `Ok`, оба флага отчёта, `t` в лайках | `Err(Storage)` — ядро не открывается (в) |

Исполнитель добавляет свой тест на `is_environmental` в `integrity.rs`: `SQLITE_FULL` — `true`; `SQLITE_ERROR`, `CONSTRAINT`, `CORRUPT` — `false`.

## Проверки

```text
cd core && cargo test --workspace && cargo clippy --workspace --all-targets -- -D warnings && cargo fmt --all -- --check
```

Gradle не нужен: FFI и Kotlin не меняются.

## Готово, когда

- T1–T5 и старые тесты проходят (в том числе `tests/migrations.rs`, `core/sync/tests/rebuild.rs`, тесты `session.rs`); у `is_environmental` есть тест исполнителя.
- Изменены только файлы из «Менять»; доки `Opened`, `Recovery`, `migrate`, `backup` описывают то, что делает код.
- CI ветки зелёный, «Отчёт исполнителя» заполнен.

## Остановиться и написать, если

- защищённый тест кажется неверным, нужен файл вне «Менять» или проверки упали дважды подряд;
- SQLite ведёт себя иначе, чем здесь сказано: порча в T2 — не `DatabaseCorrupt`/`NotADatabase`, `.bak.tmp` не переименовывается на Windows, `CREATE TABLE` в T3/T5 — не `SQLITE_ERROR`;
- нужен флаг в `StartupReport`, правка `session.rs`, Kotlin, выпущенной миграции или эталона;
- в `rusqlite` 0.40 нет какого-то из вариантов `ErrorCode` для `is_environmental`.

## Риски и что я не проверил

1. Решение отменяет строку ADR 0011 «сама база в этом случае не пересоздаётся» — до раздачи нужно слово автора; ADR и §17.4 правит Opus при слиянии.
2. Ничего не запускалось. T2 повторяет подтверждённое воспроизведение ревью; смещение 60 в T5 — из формата файла SQLite; `SQLITE_ERROR` на повторный `CREATE TABLE` — по памяти.
3. По исходнику проверено: упавший `VACUUM INTO` цель не удаляет и в непустой файл писать отказывается. Не проверено, переживёт ли копия отключение питания: своего fsync нет, SQLite пишет копию с `synchronous` исходной базы.
4. На сбой среды нет защищённого теста: место и ввод-вывод существующим API не сломать, а тест на занятой базе ждал бы `busy_timeout` 5 с. Классификацию ревью проверяет чтением. Цена решения: устойчивый сбой среды отказывает на каждом запуске, хотя пересборка бы его обошла.
5. После провала миграции рядом две полные копии (`.bak-…`, `.failed-…`); `.failed-`, как и `.corrupt-` сейчас, никто не чистит. Сколько копий хранить, решает автор (ADR — три).
6. Вне задачи: «предложить выгрузить лог» (§17.4) — Kotlin только сообщает о восстановлении; запускается ли потом скан, не проверял. Время первого запуска после обновления (`VACUUM INTO` десятков МБ) не мерил — пригодится Р1.12.

## Отчёт исполнителя

_Заполняет исполнитель._
