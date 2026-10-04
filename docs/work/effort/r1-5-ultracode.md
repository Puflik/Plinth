# r1-5 — Миграция не запирает приложение

> Замер effort: уровень `ultracode`.

- **Откуда:** `tasks.md` Р1.5; находка 5 в `docs/review/v0.2-review.md` (сценарии а–г); план §17.4 «Провал миграции»; ADR 0011.

## Цель

Ни упавшая копия, ни упавшая миграция не делают ядро неоткрываемым. Порча уходит в карантин. Если упала миграция, файл целым откладывается в сторону, а новая база собирается из журнала. После обновления на диске остаётся одна копия старой версии, а не по копии на каждый шаг.

## Контекст

- `core/library/src/db/migrations/runner.rs`, `db/backup.rs`, `db/integrity.rs` — целиком; `db/connection.rs:1–145`.
- `core/ffi/src/session.rs:50–101`: здесь `Opened::recovery` превращается в `database_recovered`, а `catch_up` пересобирает проекцию. Kotlin на это показывает `libraryRestored()` (`CoreInitializer.kt:28`) — его не менять.
- ADR 0011; `docs/plan/17-reliability.md:82–113`.

## Решение

1. **Одна копия за открытие** — перед первой ожидающей миграцией существующей базы: `<база>.bak-<мс>-v<версия до обновления>`. Перед каждым шагом копия больше не снимается. При переходе с v1 промежуточные v2 и v3 у человека на диске не лежали, а занимали все три места `KEEP` (г). `KEEP = 3` не меняется: теперь это три последних обновления.
2. **Ошибка копии не блокирует запуск.** `backup` отдаёт ошибку SQLite как есть, вместе с кодом:
   ```rust
   pub(crate) fn backup(conn: &Connection, db: &Path, from: i32) -> rusqlite::Result<PathBuf>
   ```
   Путь не в UTF-8 → `rusqlite::Error::InvalidPath`. Если `VACUUM INTO` упал, недописанный файл копии удаляется (`let _ = remove_file`). Ошибка ротации старых копий уходит в `log::warn!` и ошибкой не считается. В `migrate`: `is_corruption(&e)` → `Err(MigrationError::Sqlite(e))`; существующая ветка `From` переведёт это в `Failure::Corrupt`, то есть в карантин (б). Любая другая ошибка → `log::warn!`, миграции идут без копии (а): каждая в своей транзакции, а незаменимое хранится в журнале. Вариант `MigrationError::Backup` удаляется. Сигнатура `migrate` не меняется.
3. **Упала миграция — файл в сторону, база заново** (в). В `connection.rs`:
   ```rust
   enum Failure {
       Corrupt(String),
       /// Миграция упала и откатилась: файл цел, но этой версией не открывается.
       Migration(String),
       Other(CoreError),
   }
   fn replace(path: &Path, label: &str, reason: String) -> Result<Opened, CoreError>
   ```
   `MigrationError::Failed` без порчи → `Failure::Migration(format!("migration {version} ({name}) failed: {error}"))`. В `Database::open`: `Corrupt` → `replace(path, "corrupt", reason)`, `Migration` → `replace(path, "failed", reason)`. В `integrity.rs`:
   ```rust
   /// `<path>.<label>-<мс>` вместе с `-wal` и `-shm`; не удаляет.
   pub(crate) fn quarantine(path: &Path, label: &str) -> Result<PathBuf, CoreError>
   ```
   `Recovery` и `StartupReport` остаются прежними. `reason` несёт «migration N (…) failed», `database_recovered = true`. `catch_up` пересобирает проекцию: у новой базы нет `JournalMark`. Каталог вернёт скан.
   **Это и есть «откат на бэкап» из §17.4:** транзакция вернула файл к версии до миграции, файл отложен целым (`.failed-*`). Его можно разобрать вручную или доделать следующей версией.
4. **Остаётся как было.** `Newer` — ошибка, файл не трогается (ADR). `MigrationError::Sqlite` без порчи — сбой среды, а не данных (чтение `user_version`, начало транзакции): это ошибка, файл на месте. Новой базе копия не нужна.
5. ADR 0011 правит Opus в коммите тестов: фразы «Сама база не пересоздаётся» и «Худший случай — ошибка открытия» заменяются описанием п. 3.

**Отвергнуто.**
- *Вернуть `.bak-*` на место.* Копия той же версии, что откатившийся файл, — та же миграция упадёт снова, и так на каждом запуске.
- *Откладывать файл при любой `MigrationError`.* Тогда сбой ввода-вывода на старте зря выбросил бы каталог.
- *Новое поле причины в `StartupReport`.* Меняет FFI и Kotlin, а человеку хватает имеющегося сообщения; причина пишется в лог.
- *Искать «malformed» в тексте ошибки.* Порча везде определяется по коду SQLite.

## Инварианты

- Порча на любом шаге открытия, включая копию, → `.corrupt-*` и новая база. Упавшая миграция → `.failed-*`. Удаляются только недописанная копия и копии сверх `KEEP`.
- Отложенный файл — это состояние до миграции: `user_version` прежний, данные на месте.
- Следующее открытие после восстановления проходит без восстановления, петли нет.
- Журнал не трогается: лайки и плейлисты возвращаются из него.
- `Newer` и сбой среды остаются ошибкой, файл на месте.
- Корпусной тест `every_released_database_opens_and_keeps_its_data` требует `recovery.is_none()`. Миграция, падающая на эталоне, по-прежнему ловится в CI и не превращается в тихую пересборку.
- FFI-поверхность не меняется, Kotlin не трогается.

## Файлы

- **Менять:** `core/library/src/db/backup.rs`, `db/migrations/runner.rs`, `db/connection.rs`, `db/integrity.rs`; в `core/ffi/src/session.rs` и `core/ffi/src/types.rs` — только doc-комментарии и строку лога: провал миграции больше не ошибка открытия, «испорчена» → «испорчена или не мигрировала».
- **Не трогать:** `m000*.rs` (заморожены) и `MIGRATIONS`; `IntegrityCheck`, `open_checked`, `problems` (это Р1.12); `core/sync`; Kotlin; эталоны `tests/fixtures/db/*`; ADR.
- **Защищённые тесты:**
  - `core/library/tests/r1_5_migration_recovery.rs`
  - `core/library/src/db/migrations/r1_5_tests.rs` и строка `#[cfg(test)] mod r1_5_tests;` в `migrations/mod.rs`
  - `core/ffi/src/r1_5_tests.rs` и строка в `ffi/src/lib.rs`: крейт собирается только как `cdylib`, внешние тесты его не видят

## Тесты

Каждый тест работает на копии эталона в своём временном каталоге. Подсматривают тесты через `rusqlite` только для чтения: это зависимость крейта, внешнему тесту она видна. Тесты используют только существующие API, поэтому на текущем коде компилируются и падают на утверждении (`assert!(r.is_ok(), …)`, а не `unwrap`).

1. **`a_damaged_old_database_goes_to_quarantine` (б).**
   *Вход:* `v1.db`, 8 КБ `0xFF` с отступа 8192 → `Database::open(Skip)`.
   *Ожидается:* `Ok`; `recovery` есть; имя `quarantined` содержит `.corrupt-`; версия схемы последняя; файлов `.bak-` нет; второе открытие → `recovery: None`.
   *Сейчас:* `Err(Storage "database disk image is malformed")`: код порчи теряется в `CoreError` копии (воспроизвело ревью).
2. **`a_failed_migration_sets_the_file_aside` (в).**
   *Вход:* `v4.db`, затем `PRAGMA user_version = 3`. m0004 повторяет `ADD COLUMN` и падает с «duplicate column name» — это не порча.
   *Ожидается:* `Ok`; `reason` содержит `migration 4`; `quarantined` содержит `.failed-`; отложенный файл: `user_version = 3`, 3 трека; новая база — последней версии, треков 0; второе открытие → `None`.
   *Сейчас:* `Err(Storage "migration 4 (sort_credit) failed…")`. m0004 заморожена, так что миграции 0.3 этот сценарий не сломают.
3. **`an_update_keeps_one_backup_of_the_old_version` (г).**
   *Вход:* `v1.db` → `open`.
   *Ожидается:* ровно один `plinth.db.bak-*`, имя кончается на `-v1`.
   *Сейчас:* их три (`-v1`, `-v2`, `-v3`).
4. **`a_backup_that_cannot_be_written_does_not_stop_the_migration` (а; модуль `migrations/r1_5_tests.rs`).**
   *Вход:* база в памяти, своя миграция `ONE`, строка в ней; затем `migrate(&mut conn, &[ONE, TWO], Some(<temp>/нет-каталога/plinth.db))`. `VACUUM INTO` в несуществующий каталог даёт `SQLITE_CANTOPEN` — та же ветка, что при нехватке места.
   *Ожидается:* `Ok(vec![2])`, версия 2, строка цела.
   *Сейчас:* `Err(MigrationError::Backup(..))`.
5. **`a_failed_migration_does_not_lock_the_person_out` (`ffi/src/r1_5_tests.rs`).**
   *Вход:* `Core::open` → `like(track)` → drop: при закрытии WAL сбрасывается, как в `a_corrupted_database_is_recovered_without_loss`. Байты 60..64 `library.db` (`user_version`, big-endian) = `[0,0,0,3]`.
   *Ожидается:* `Core::open` → `Ok`; `database_recovered` и `restored_from_journal`; лайк на месте.
   *Сейчас:* `Err(Storage "migration 4 …")`.

## Проверки

```text
cd core && cargo test -p plinth-library -p plinth-ffi && cargo clippy --workspace --all-targets -- -D warnings && cargo fmt --all -- --check
```

FFI-типы не меняются, поэтому Gradle не нужен.

## Готово, когда

- Пять защищённых тестов зелёные. Старые тесты `connection.rs`, `runner.rs`, `backup.rs`, `tests/migrations.rs`, `session.rs` не правлены и зелёные (среди них `newer_schema_is_an_error_not_a_reset` и `backup_is_taken_before_migrating_an_existing_database`).
- Варианта `MigrationError::Backup` нет; `backup` возвращает `rusqlite::Result`; из `migrate` выходит только ошибка копии с кодом порчи.
- clippy и fmt чистые.

## Остановиться и написать, если

- защищённый тест кажется неверным; нужен файл вне «Менять»; проверки упали дважды;
- тест 1 не уходит в карантин, потому что код ошибки `VACUUM INTO` не `DatabaseCorrupt`/`NotADatabase`. `is_corruption` разбором текста не расширять;
- недописанную копию не удаётся удалить (на Windows файл занят);
- кажется, что `Newer` или сбой среды тоже нужно откладывать в сторону;
- `session.rs` требует больше, чем текст (например, `catch_up` не пересобирает базу после `.failed-`).

## Риски и что я не проверил

- **Ничего не запускал** (условие замера). Что тесты 2, 4 и 5 падают на текущем коде, выведено из кода, а не увидено; тесты 1 и 3 воспроизвело ревью. Красный прогон Opus обязан увидеть сам, при коммите тестов.
- **Не проверял, оставляет ли `VACUUM INTO` недописанный файл при ошибке.** Если не оставляет, утверждение «`.bak-` нет» в тесте 1 проходит само собой.
- **Код ошибки `VACUUM INTO` на испорченном источнике.** В ревью текст «malformed», значит `SQLITE_CORRUPT`, но в код встроенной SQLite я не смотрел.
- **Нехватка места.** Отложенный файл и копия — это два размера базы на заполненном диске. Новая база мала, но скан может снова упереться в место. `.corrupt-*` и `.failed-*` никто не чистит; это вне задачи — кандидат в Р1.11.
- **Ошибочная миграция 0.3, проскочившая CI,** теперь обходится каждому пересканированием, а не отказом запуска. Данные человека целы. Но сообщение «библиотека восстановлена» говорит меньше, чем «миграция N упала»: причина есть только в логе. Подходит ли текст `libraryRestored()` к этому случаю, я не смотрел.
- **Граница с Р1.12.** Обе задачи меняют `connection.rs`: Р1.12 — `open_checked`/`IntegrityCheck`, Р1.5 — `Failure`, `open`, `replace`; карантин из Р1.12 пойдёт через новую `quarantine(path, label)`. Параллельно их вести нельзя. Р1.5 идёт первой: её срок — до первой миграции 0.3. Р1.12 ветвится от `main` после слияния.
- **Чистота замера.** Поиск `databaseRecovered` по репозиторию задел три строки чужого `r1-5-xhigh.md`; решение к этому моменту уже было составлено, сам файл я не открывал.
