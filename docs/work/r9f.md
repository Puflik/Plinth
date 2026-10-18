# r9f — вести о восстановлении: отчёт любого открытия, постоянный след, нечитаемая копия; копии рядом — вне Auto Backup

> Файл задачи для исполнителя (Sonnet). Пишет планировщик (Sonnet max); исполнитель
> меняет только раздел «Отчёт исполнителя». Порядок работы — `docs/work/README.md`.

- **Ветка:** `work/r9f` · **Ворктри:** `C:\claude\Plinth-r9f`
- **Основа:** `main` на `8a8bcd2` · **Коммит тестов:** `74c7aca` (на нём все защищённые тесты; пришли двумя коммитами:
  `c88959b` — первый в ветке, Rust; `74c7aca` — Kotlin, поправки `mod tests` в `store.rs` и этот файл)
- **Откуда:** Р9е в `tasks.md`; находки №7, 8, 9 в `docs/review/r1-review.md` (строки 99–121); «Заодно» из той же строки
  `tasks.md`: из Р9б — `docs/work/r9b.md` («Не проверено», последние абзацы) и `docs/decisions.md` («Р9а и Р9б сделаны»:
  часы назад, обрывок копии); из Р9д — `docs/work/r9e.md`, «Замечено, не в этой задаче».

Задача с данными пользователя (журнал, копия в папке, восстановление): **план до исполнителя читает Opus**.

## Цель

Человек узнаёт о восстановлении, как бы ни открылось ядро и что бы ни случилось с экраном: отчёт читается у того открытия,
которое удалось (не только у первой попытки); весть «журнал начат заново» не пропадает от поворота экрана, а когда
вернуть данные может только копия в папке, на экране копии остаётся напоминание, пока данные не вернулись или человек от
них не отказался; копия в папке, которую ядро не читает, не выглядит как «данных нет». Заодно: копии рядом с журналом
(`*.damaged-*`, `tail.foreign-*`) — в `journal/aside/` и вне Auto Backup; свежая копия не стирается чисткой, а копия,
оборвавшаяся на полном диске, не оставляет обрывка; `SafMirrorFolder` не удаляет прежнюю копию, пока новая не записана целиком.

## Контекст

Прочитать перед работой (только это):

- `docs/review/r1-review.md`: №7, 8, 9 (строки 99–121).
- Rust, `core/sync/src/journal/store.rs`: шапка 77–97 (копии рядом), `Store::open` (172–211), `reset_tail`/`copy_aside`
  (319–348), `keep_snapshot_aside` (≈456), `keep_aside`/`prune_aside`/`ASIDE_KEPT` (≈651–687); `start_over.rs` — как зовётся
  `keep_snapshot_aside`.
- Rust, `core/ffi/src/session.rs`: `Core` (структура), `open_at`, `open_journal` (≈222), `device`/`renew_device`/`write_durably`;
  `core/ffi/src/api/mirror_api.rs` — весь.
- Kotlin: `ffi/PlinthCore.kt` (весь), `ffi/CoreInitializer.kt`, `ffi/CoreErrors.kt`, `core/ErrorPresenter.kt`,
  `ui/common/ErrorNotices.kt` и `ErrorNoticesViewModel.kt`, `backup/JournalMirror.kt`, `CoreJournalMirror.kt`, `ffi/CoreMirror.kt`,
  `backup/MirrorFolder.kt`, `SafMirrorFolder.kt`, `ui/backup/BackupViewModel.kt` и `BackupContent.kt`; где зовётся
  `backupSection` (`grep -rn backupSection app/src/main`).
- Ресурсы: `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`, строки `notice_*`, `backup_*` в `values/` и `values-ru/`.
- `docs/adr/0007-journal-as-source-of-truth.md` — раздел о `start_over` и копиях (Р1.4, Р9д).
- Образец «как раньше делали такое»: `docs/work/r9e.md` (раздел «Решение»: пометка в журнале, прежние копии).

## Решение

### Что не так сейчас (кратко, подробности — в находках)

1. **№7.** Отчёт об открытии (`StartupReport`) читает только `CoreInitializer.start()` из своей единственной попытки; открытие,
   удавшееся позже (`reopen()` с «Повторить», `core()` из любого вызова API), отчёт выбрасывает.
2. **№8.** `ErrorPresenter.present` ставит «сказано» до показа; поворот экрана отменяет строку, при новом сборе `present` уже
   возвращает `null`. Постоянного следа нет.
3. **№9.** `unreadable` доходит до `BackupViewModel`, но вопрос показывается только при `news`, а файл больше 64 МБ
   `SafMirrorFolder.read` выбрасывает до ядра.
4. **Копии рядом.** `tail.damaged-*`, `tail.foreign-*`, `snapshot.damaged-*` лежат в `journal/` и уходят в Auto Backup (квота 25 МБ);
   чистка `prune_aside` сравнивает метки времени, и после ухода часов назад свежая копия — «старейшая» и стирается, а хвост
   перезаписывается; `fs::copy` на полном диске оставляет недописанный файл под настоящим именем.
5. **`SafMirrorFolder.writeInto`** удаляет прежний файл копии раньше, чем проверено, что временный записан целиком.

### 1. Ядро, `plinth-sync` — `journal/store.rs`: папка `journal/aside/`

- `pub(crate) const ASIDE_DIR: &str = "aside";` Копии рядом — `journal/aside/<префикс><метка>`, префиксы прежние:
  `tail.damaged-`, `tail.foreign-`, `snapshot.damaged-` (сколько хранить — `ASIDE_KEPT` без изменений: 2 / 1 / 1).
- Одна функция записи копии (вместо `fs::copy` в `keep_aside` и `keep_snapshot_aside`):
  `fn keep_copy(dir: &Path, source: &Path, prefix: &str, cut: bool) -> Result<(), CoreError>`.
  1. `create_dir_all(dir/aside)` (папка заводится первой копией; **открытие без копий её не создаёт**).
  2. **Метка** = `max(сейчас_мс, наибольшая метка того же префикса в aside/ + 1)`. Метка по-прежнему «время», но не ниже прежних:
     ушли часы назад — копия всё равно новейшая, и чистка (она сравнивает метки) стирает старейшую из прежних, а не эту.
  3. Запись целиком во временный `aside/<префикс><метка>.tmp` (`write_all` + `sync_all`), затем `rename` в `aside/<префикс><метка>`,
     затем `sync_dir(aside)`. **Любая ошибка** шагов 3 — временный файл стирается (best effort), ошибка уходит наверх как сейчас
     (`io_error(...)`): хвост не тронут, повтор при следующем сбросе. Обрывка под настоящим именем быть не может.
  - `cut: bool` — только для проверки: в сборке без `cfg(test)` всегда `false`; при `true` в временный файл пишется первая
    половина байтов, затем ошибка («как `fs::copy` на полном диске»). Остальной код `cut` не знает.
- `prune_aside(dir)` (зовётся как раньше: из `Store::open` на каждом открытии и после каждой копии) теперь:
  1. **переносит** файлы из `dir/` (где их клали сборки до Р9е) в `aside/`, если имя — по образцу `<префикс><только цифры, влезает в u64>`;
     `aside/` заводится только если есть что переносить; не вышло — `log::warn!`, открытие не отказывает;
  2. стирает в `aside/` недописанные `<префикс><цифры>.tmp` (остаток запуска, оборвавшегося посреди копии);
  3. оставляет новейшие по метке (как раньше). Имена не по образцу (`notes.txt`, `tail.damaged`, `tail.damaged-abc`, метка не
     влезает в u64) — не наши, не трогаются, ни в `journal/`, ни в `aside/`.
- Проверочный крючок (только `#[cfg(test)]`, как `fail_next_aside`): `pub(crate) fn fail_next_aside_midway(&mut self)` — следующая
  копия в `copy_aside` вызовет `keep_copy` с `cut = true`. Поле `fail_next_aside_midway: bool` рядом с `fail_next_aside`.
- Шапка модуля (раздел «Открытие не требует места») — поправить: копии в `journal/aside/`, вне Auto Backup (`<exclude>`), метка
  не ниже прежних.
- **`mod tests` в самом `store.rs` я уже поправил** (хелпер `aside()` смотрит в `aside/`, тест чистки кладёт копии в `aside/`) —
  в нём ничего не менять.

Отвергнуто: (а) «чистка не трогает только что сделанную» — после двух ушедших назад часов в `aside/` остаётся «старейшая по
времени, новейшая по делу» копия, а чистка всё равно сравнивает метки; монотонная метка чинит порядок раз и навсегда;
(б) время изменения файла — часы те же; (в) подпапки по причине (`aside/tail/…`) — лишнее.

### 2. Ядро, `plinth-ffi`: пометка `restore-pending`

Что: файл `<каталог данных>/restore-pending` (рядом с `device`, **вне `journal/`** — Auto Backup и перенос на новый телефон
его не уносят). Есть файл — журнал начат заново пустым и данные не вернулись.

- `session.rs`: `pub(crate) const RESTORE_PENDING: &str = "restore-pending";` поле `Core::dir: PathBuf` (каталог данных).
  В `open_journal` после `start_over(...)` (возвращает `carried: usize`): `carried == 0` — `mark_restore_pending(dir)`:
  `write_durably(restore-pending.tmp → restore-pending, b"1")`; **не вышло — `log::warn!` и открытие продолжается** (пометка —
  подсказка экрану, а не условие работы). `carried > 0` — пометки нет (данные переехали из базы).
- `api/mirror_api.rs`, в экспортируемом `impl Core`:
  `pub fn restore_pending(&self) -> Result<bool, CoreError>` — `dir/restore-pending` **`is_file()`** (каталог на её месте — не пометка);
  `pub fn dismiss_restore_pending(&self) -> Result<(), CoreError>` — стирает файл; нет файла — `Ok` (повтор безвреден).
  Оба — внутри `panic::guard`, как соседи.
- `restore_mirror`: если `restored.merged > 0` — пометка стирается (`log::warn!` при ошибке, восстановление не отказывает).
  `inspect_mirror` пометку не трогает (осмотр — чтение). Пустое восстановление и мусорные файлы (`merged == 0`) — пометка остаётся.
- Подробнее про «когда снимается»: данные вернулись (`merged > 0`) — ядро снимает само; человек отказался или закрыл
  напоминание — Kotlin зовёт `dismiss_restore_pending`.

Отвергнуто: (а) флаг в DataStore приложения — писать его надо из пути открытия (`PlinthCore.core()`), где нет ни настроек, ни
корутин, а окно между `start_over` и записью флага больше, чем у файла в том же `open_journal`; ядро — единственный, кто точно
знает `carried`; (б) пометка до `start_over` (и снятие при `carried > 0`) — закрывает окно сбоя, но оставляет ложную пометку
при сбое между заменой снимка и снятием; окно после `start_over` — миллисекунды (принятое ограничение, см. ниже);
(в) пометка в журнале (`Op::…`) — она ушла бы в копию и на новый телефон, а там напоминать не о чем.

### 3. Kotlin: отчёт любого открытия (№7)

- `CoreErrors.opened(report: StartupReport)` — вся логика, что была в `CoreInitializer.start()`:
  `journalStartedOver` → `journalStartedOver(carried = report.restoredFromJournal)`; иначе `databaseRecovered || restoredFromJournal` →
  `libraryRestored()`; иначе ничего. (`StartupReport` — Kotlin-класс из `ffi/CoreModels.kt`.)
- `PlinthCore`: в инициализаторе `opened` (`lazy`), сразу после `Core.open(dataDir.path)`:
  `runCatching { it.startupReport().toApp() }.onSuccess { AppLog… ; errors.opened(it) }.onFailure { AppLog.w(...) }` и вернуть `Core`.
  Отказ чтения отчёта **не должен** ронять инициализатор: открытое ядро держит замок файлов, и следующая попытка упёрлась бы в
  «каталог уже открыт». Так отчёт читает то открытие, которое удалось, — откуда бы его ни позвали.
- `CoreInitializer.start()`: убрать вызовы `errors.journalStartedOver/libraryRestored` (теперь их делает `PlinthCore`); строки лога
  «journal started over: …» / «core restored: …» остаются у того, кто их пишет (перенести в `PlinthCore` вместе с отчётом, чтобы
  повтор тоже оставлял след в логе). `checkIntegrity()` как раньше. Поправить KDoc.

### 4. Kotlin: весть сказана, когда показана (№8, часть 1)

- `ErrorPresenter`: `present` для `AppError.LibraryRestored` и `AppError.JournalStartedOver` **больше не ставит** `toldRestored` /
  `toldJournalStartedOver`; новый `fun shown(notice: ErrorNotice)`: `ErrorNotice.LibraryRestored` → `toldRestored = true`,
  `ErrorNotice.JournalStartedOver` → `toldJournalStartedOver = true`, **остальные виды — без последствий** (у них свои правила:
  «раз за запуск» ставится при `present`, как раньше). Это не меняет «сказать один раз», а переносит «сказано» на момент показа.
- `ErrorNoticesViewModel.shown(notice) = presenter.shown(notice)`; `ErrorNotices.kt`: `viewModel.shown(notice)` вызывается после
  того, как `snackbar.showSnackbar(...)` **вернулась** (закрыта временем, смахнута или нажата кнопка); отмена корутины (поворот,
  уход ниже `STARTED`) до него не доходит — при новом сборе весть придёт снова. Для остальных видов `shown` ничего не делает.

### 5. Kotlin: постоянный след (№8, часть 2) и нечитаемая копия (№9)

- `JournalMirror`: `suspend fun restorePending(): Boolean?` (`null` — ядро не ответило, отказ уже в `CoreErrors`) и
  `suspend fun dismissRestorePending(): Boolean` (`true` — снято). `CoreMirror`: `fun restorePending(): Boolean = core.call { it.restorePending() }`,
  `fun dismissRestorePending() = core.call { it.dismissRestorePending() }`; `CoreJournalMirror` — как соседи, через `attempt(io, TAG, …)`.
  `FakeJournalMirror` (sharedTest) я уже дополнил (`markRestorePending()`, снятие при `merged > 0`).
- `BackupUiState.restorePending: Boolean = false`; `BackupOutcome` получает `UNREADABLE` и `NOTHING_TO_RESTORE`.
- `BackupViewModel`:
  - `init`: `viewModelScope.launch { refreshPending() }`; `refreshPending()` = `mirror.restorePending() == true` → `screen.update { copy(restorePending = …) }`.
  - `onFolderPicked`, после `inspect`: `found == null` → `FAILED` (как сейчас); `found.news` → вопрос «Восстановить?» (как сейчас, outcome `null`);
    иначе `found.unreadable > 0` → `UNREADABLE`; иначе, если `restorePending` на экране, → `NOTHING_TO_RESTORE`; иначе молча (как сейчас).
    Папка в любом случае запоминается (как сейчас). Нечитаемые файлы рядом с годными вопроса не отменяют.
  - `onRestore`: после удачного `restore` — `refreshPending()` (ядро само сняло пометку при `merged > 0`).
  - `onDecline`: как сейчас + `mirror.dismissRestorePending()` + `refreshPending()` (отказ от найденного — решение человека).
  - `fun onDismissPending()`: `mirror.dismissRestorePending()` + `refreshPending()`; больше ничего не меняет.
- `BackupContent.kt`: `backupSection(state, onPick, onDismissPending)` — при `state.restorePending` под строкой папки текст
  `backup_pending` (цвет `colorScheme.error` не нужен: это подсказка, не отказ — `bodyMedium`) и `TextButton` с `backup_pending_dismiss`;
  `BackupPrompts` — сообщения для `UNREADABLE` (`backup_unreadable`) и `NOTHING_TO_RESTORE` (`backup_nothing_to_restore`). Место вызова
  `backupSection` — экран настроек: передать `viewModel::onDismissPending`. Мастер (`BackupStep`) пометку не показывает.
- Строки (в `values/strings.xml` и `values-ru/strings.xml`; `RussianTranslationTest` их сверяет):
  - `backup_pending` — «Лайки и плейлисты были повреждены и пока не вернулись. Выберите папку с копией — Plinth предложит их восстановить.» /
    “Your likes and playlists were damaged and haven’t come back yet. Choose the folder with your copy — Plinth will offer to restore them.”
  - `backup_pending_dismiss` — «Скрыть» / “Hide”
  - `backup_unreadable` — «В папке есть копия, которую Plinth не может прочитать: она записана более новой версией, повреждена или слишком велика. Файл не тронут.» /
    “The folder holds a copy that Plinth can’t read: it was written by a newer version, is damaged or too large. The file is left untouched.”
  - `backup_nothing_to_restore` — «В этой папке нет копии, из которой можно что-то вернуть.» / “There is no copy to restore from in this folder.”
- **Тексты `notice_journal_started_over` / `notice_journal_lost` сверены с порядком Р9д и не меняются:** первый — «собраны заново по
  библиотеке» (данные переехали, прежняя копия данных не вливает, отдаёт паспорта — человеку делать нечего); второй — «верните из
  копии: Настройки → Копия данных, выберите ту же папку» (пометки `superseded` при `carried == 0` нет, копия прежней установки
  живая и восстанавливается; теперь в тех же настройках стоит и сама подсказка).
- `SafMirrorFolder.read`: файл больше `MAX_BYTES` **не отбрасывается**, а возвращается как `MirrorFile(имя, ByteArray(0))` без чтения
  (в лог — размер, без имени): ядро считает пустой файл нечитаемым (`junk_in_the_folder_is_counted_not_fatal`), и человек получает то же
  `UNREADABLE`. KDoc `MirrorFolder.read` — дописать.
- `SafMirrorFolder.writeInto` (порядок, JVM-тестом не проверить): создать временный `<имя>.tmp`, записать; **проверить размер** —
  перечитать `children`, найти временный; если провайдер сообщает размер (>0) и он не равен `file.content.size` — стереть временный,
  прежнюю копию не трогать, вернуть `false` (бросить `IOException`: `guarded` вернёт `null` → `false`); только потом стереть прежний файл
  и `renameDocument`. Результат `renameDocument` `null` — тоже `IOException` (временный остаётся: он целая копия, читается как копия).
  Размер не сообщается (0) — не проверять, как до сих пор.

### 6. Auto Backup (`res/xml/`)

В `data_extraction_rules.xml` — по `<exclude domain="file" path="core/journal/aside/"/>` внутри `<cloud-backup>` и `<device-transfer>`;
в `backup_rules.xml` — тот же `<exclude>` внутри `<full-backup-content>`. `<include>` не менять. Комментарии в обоих файлах — дописать
одной строкой, зачем исключена `aside/`.

### 7. Документы

`docs/adr/0007-journal-as-source-of-truth.md` — абзац (3–6 строк): копии рядом лежат в `journal/aside/` вне Auto Backup, метка не ниже
прежних, запись целиком или никак; пометка `restore-pending` (где лежит, кто ставит, кто снимает). `CHANGELOG.md`, `tasks.md`,
`docs/decisions.md` — не трогать (при слиянии их правит Opus).

## Инварианты

- Копия рядом делается **до** перезаписи хвоста (как раньше); не вышла — хвост не тронут; готовое имя в `aside/` — всегда целый файл.
- Свежая копия никогда не стирается чисткой: её метка выше всех прежних того же вида, как бы ни шли часы.
- Открытие не отказывает из-за `aside/`: перенос прежних копий, стирание остатков и чистка — `warn`, не ошибка.
- Папка `aside/` не создаётся, пока нет копии; `journal/` здорового журнала выглядит как до Р9е.
- `restore-pending` лежит вне `journal/`; не записалась — ядро открывается; снимается только `merged > 0` или явным отказом;
  переживает перезапуск; каталог на её месте — не пометка.
- Формат журнала, копии в папке (`FORMAT`, `mirror/format.rs`) и заголовки файлов не меняются; журнал новой версии читает старая.
- Весть «журнал начат заново» / «библиотека восстановлена» сказана, **когда показана**; прочие виды вестей считаются, как раньше.
- Отчёт открытия, удавшегося с n-й попытки, обрабатывается как у первой; отказ чтения отчёта не оставляет открытое ядро висеть.
- Нечитаемая копия в папке не молчит; `UNREADABLE` не отменяет вопрос «Восстановить?», когда есть что восстанавливать.
- `SafMirrorFolder` не удаляет прежнюю копию, пока временная не записана целиком (если провайдер сообщает размер).

## Файлы

- **Менять (Rust):** `core/sync/src/journal/store.rs` (всё, **кроме `mod tests`**); `core/ffi/src/session.rs`; `core/ffi/src/api/mirror_api.rs`.
- **Менять (Kotlin, `app/src/main/kotlin/io/github/puflik/plinth/`):** `ffi/PlinthCore.kt`, `ffi/CoreInitializer.kt`, `ffi/CoreErrors.kt`,
  `ffi/CoreMirror.kt`, `core/ErrorPresenter.kt`, `ui/common/ErrorNotices.kt`, `ui/common/ErrorNoticesViewModel.kt`,
  `backup/JournalMirror.kt`, `backup/CoreJournalMirror.kt`, `backup/MirrorFolder.kt` (KDoc), `backup/SafMirrorFolder.kt`,
  `ui/backup/BackupViewModel.kt`, `ui/backup/BackupContent.kt`, экран настроек (место вызова `backupSection`).
- **Менять (ресурсы и документы):** `app/src/main/res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`,
  `res/values/strings.xml`, `res/values-ru/strings.xml`, `docs/adr/0007-journal-as-source-of-truth.md`.
- **Новые файлы (по желанию):** инструментальный тест `app/src/androidTest/kotlin/io/github/puflik/plinth/ffi/R9fRetryOpeningTest.kt`
  (сценарий №7, ниже), юнит-тесты исполнителя в новых файлах.
- **Не трогать:** `core/sync/src/mirror/*`, `journal/doc.rs`, `start_over.rs`, `FORMAT`/заголовки; `CHANGELOG.md`, `tasks*.md`,
  `docs/decisions.md`, `CLAUDE.md`, план, зависимости, `Cargo.lock`; `ErrorPresenter` — правила прочих вестей (пропуски, `CoreFailed`,
  `NotSaved`); существующие инструментальные тесты (`CoreInitializerTest`, `R14CoreOpeningTest` должны проходить как есть).
- **Защищённые тесты** (из коммитов тестов; не менять, не удалять, не глушить):
  - Rust, новые: `core/sync/tests/r9f_aside_folder.rs`, `core/sync/src/journal/store/r9f_tests.rs`, `core/ffi/src/api/r9f_tests.rs`;
  - Rust, поправленные под `aside/`: `core/sync/tests/r9b_aside_cleanup.rs`, `core/sync/tests/r9b_support/mod.rs`,
    `core/sync/tests/r1_11b_tail_frames.rs`, `core/sync/tests/r1_4_start_over.rs`, `core/sync/tests/r9b_torn_import.rs`,
    `core/sync/src/journal/store/r9b_tests.rs`, `core/ffi/src/r1_4_tests.rs`, `mod tests` в `core/sync/src/journal/store.rs`;
  - Kotlin, новые: `app/src/test/kotlin/io/github/puflik/plinth/core/R9fToldAfterShownTest.kt`, `ffi/R9fOpeningReportTest.kt`,
    `ui/backup/R9fBackupPendingTest.kt`, `R9fBackupRulesTest.kt`;
  - Kotlin, поправленные: `core/ErrorPresenterTest.kt` (`a restored library is told once`), `core/R14JournalNoticeTest.kt`
    (`a journal started over from the library is told once`) — между двумя `present` стоит `shown`;
    `app/src/sharedTest/kotlin/io/github/puflik/plinth/backup/FakeJournalMirror.kt` (память о пометке).

## Тесты (написаны в коммитах тестов)

Rust (прогнаны на временной реализации, см. «На что смотреть»; на `main` падают утверждением, кроме двух отмеченных):

- `r9f_aside_folder`: копия испорченного хвоста — в `aside/`, рядом с журналом ничего (`a_copy_of_a_damaged_tail_goes_to_the_aside_folder`);
  то же для хвоста чужого журнала (`…another_journal…`, `tail.foreign-*`); копии сборок до Р9е переезжают в `aside/`, чужие имена
  остаются (`copies_made_by_older_builds_move_…`); открытие без копий `aside/` не создаёт (*охранный, на `main` проходит*);
  остатки `*.tmp` стираются при открытии, готовая копия цела (`leftovers_of_a_cut_copy_…`); **часы назад** — в `aside/` лежат
  `tail.damaged-99999999999990/91`, открытие с порченым хвостом: остаются `…91` и новая копия с меткой выше, `…90` стёрта
  (`a_fresh_copy_survives_a_clock_that_went_back`).
- `r9b_aside_cleanup` (переписан под `aside/`): новейшие остаются, имена не по образцу — нет; новая копия вытесняет старейшую.
- `store/r9f_tests`: `a_copy_cut_short_leaves_nothing_behind` — крючок `fail_next_aside_midway`: ни временного, ни настоящего имени,
  хвост цел; следующая запись делает целую одну копию. *Не собирается без крючка (новый API).*
- `api/r9f_tests` (*не собирается без `restore_pending`/`dismiss_restore_pending`*): свежая установка — пометки нет; порча снимка,
  базы нет → пометка есть, переживает перезапуск, `restore_mirror` с копией её снимает (и после перезапуска тоже); порча при целой
  базе (`carried > 0`) — пометки нет; осмотр папки, пустое и мусорное восстановление её не снимают; `dismiss_restore_pending` снимает
  и повторяется без ошибки; пометка лежит рядом с `device`, а не в `journal/`; каталог на месте пометки не мешает открытию
  (отчёт «журнал начат заново» честный, `restore_pending() == false`).
- Прежние тесты, смотревшие копии «рядом», теперь смотрят `aside/` — падают на `main` утверждением (копии рядом).

Kotlin (JVM; прогнаны планировщиком на временной реализации — `testGithubDebugUnitTest` с фильтром по этим классам и по
`BackupViewModelTest`, `BackupRulesTest`, `FakeJournalMirrorTest`: 52 теста, 0 падений; на `main` не собираются — новый API, это
ожидаемо; если тест не собирается по причине, не связанной с новым API, — стоп и вопрос в отчёте):

- `R9fToldAfterShownTest`: пока не показана — весть отдаётся снова; показана — `null`; виды не мешают друг другу; `shown` для
  `NotSaved`/`CoreFailed` ничего не меняет.
- `R9fOpeningReportTest`: `CoreErrors.opened` — по отчёту нужная весть (начат заново с переносом / без; база собрана; журнал
  пересобрал библиотеку); обычное открытие молчит.
- `R9fBackupPendingTest`: нечитаемый файл → `UNREADABLE`, папка принята; нечитаемый рядом с годной копией вопроса не отменяет;
  пустая папка без пометки молчит, с пометкой → `NOTHING_TO_RESTORE`; пометка видна на экране; восстановление, отказ и «Скрыть» её
  снимают, «Скрыть» больше ничего не трогает.
- `R9fBackupRulesTest`: `aside/` исключена из облачной копии, переноса на телефон и старой полной копии.

## Проверки

```text
cd core
$env:CARGO_TARGET_DIR = "C:\claude\Plinth-r9f\core\target"
cargo test -p plinth-sync -p plinth-library -p plinth-ffi --no-fail-fast
cargo clippy --workspace --all-targets -- -D warnings
cargo fmt --all -- --check
```

```text
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
.\gradlew.bat ktlintCheck detekt testGithubDebugUnitTest testFdroidDebugUnitTest lintGithubDebug
```

Инструментальные (эмулятор `Medium_Phone_API_36.0`, `$env:ANDROID_SERIAL = "emulator-5554"` в той же команде, что и Gradle; на телефон
автора не ставить): `CoreInitializerTest`, `R14CoreOpeningTest`, `ReinstallRestoreTest`, `PlinthCoreTest` — как раньше; и тест исполнителя
`R9fRetryOpeningTest`: порченый снимок + нет базы + на месте `device.tmp` каталог (первое открытие падает в `renew_device` —
`CoreFailure`); каталог убрали, `core.open()` второй раз; `errors.events.first()` — `JournalStartedOver(carried = false)`,
`core.mirror.restorePending()` — `true`. Это сценарий №7 ревью; на `main` он падает (весть не приходит).

Известное: на Windows падают 4 теста `DataStore*SettingsTest` — не из-за задачи; в CI на Linux проходят. Мигающий
`r1_6_register_conflicts::sets_stay_add_wins_a_like_removed_on_the_new_installation_returns` — известен, перезапуск.

## Готово, когда

- Все защищённые тесты зелёные; «Проверки» проходят; в `mod tests` файла `store.rs` и в защищённых файлах нет правок исполнителя.
- Руками на эмуляторе (или честно: «не проверено» в отчёте): испортить последний байт `files/core/journal/snapshot` и удалить
  `files/core/library.db*` → запуск → строка «Лайки и плейлисты были повреждены…» → в первые секунды повернуть экран → строка
  возвращается; Настройки → «Копия данных»: под строкой папки — подсказка «пока не вернулись» и кнопка «Скрыть»; выбор папки без копии →
  «В этой папке нет копии…»; папка с «записанной более новой версией» файлом (первый байт формата `2`) → «…не может прочитать…».
- `files/core/journal/` здорового журнала — как до задачи (нет `aside/`); после порчи хвоста копия — в `journal/aside/`.
- Никаких `#[ignore]`/`#[allow]`/`@Ignore`/`@Suppress`, ослабленных утверждений, правок защищённых тестов.

## Остановиться и написать, если

- защищённый тест кажется неверным; Kotlin-тест не собирается не из-за нового API;
- для решения нужен файл вне списка «Менять»; `StartupReport`/`MirrorFound` придётся менять в Rust (`types.rs`) — не нужно, но проверь;
- проверки упали дважды подряд (одна попытка исправить, потом стоп);
- `UniFFI`-биндинги не видят `restore_pending`/`dismiss_restore_pending` после сборки (имена Kotlin: `restorePending`, `dismissRestorePending`);
- `renameDocument`/`DocumentsContract` ведут себя иначе, чем описано (SAF на JVM не проверить; на эмуляторе — только папка внутренней
  памяти): не додумывать, написать.

## На что смотреть при чтении плана (Opus)

1. **Пометка в ядре, а не флаг в DataStore.** `restore-pending` пишет `open_journal` сразу после `start_over` с `carried == 0`; Kotlin только
   читает и просит снять. Цена — две новые функции FFI. Согласен ли ты, что это лучше флага в настройках приложения?
2. **Когда пометка снимается.** Ядро — при `restore_mirror` с `merged > 0`; человек — отказом от найденного или «Скрыть». «Скрыть» —
   явная кнопка, а не снятие «после первой попытки найти копию»: человек сам решает, что искать больше нечего.
3. **Монотонная метка** (`max(сейчас, наибольшая + 1)`): метка перестаёт быть строго «временем копии» (после ухода часов назад она
   «из будущего»), зато порядок копий не зависит от часов. Метка нигде не показывается. Устраивает ли?
4. **`aside/`: перенос прежних копий при открытии** — `rename` в `aside/` (не удаление): копии нужны отчёту о сбое. Если имя в
   `aside/` уже занято, `rename` перезапишет (Unix) или откажет (Windows, warn) — редкий случай, не проверялся.
5. **`NOTHING_TO_RESTORE` показывается только при пометке**, `UNREADABLE` — всегда, когда нет `news`. Папка, выбранная в мастере первого
   запуска, остаётся молчаливой.
6. **Файл больше 64 МБ → пустой `MirrorFile`.** Хитрость, зато без новой сигнатуры `MirrorFolder.read` и новых полей; ядро уже считает
   пустые файлы нечитаемыми. Альтернатива — вернуть счётчик отдельно (изменить интерфейс, `FakeMirrorFolder`, `ReinstallRestoreTest`).
7. **«Сказано после показа» — только для `LibraryRestored` и `JournalStartedOver`.** Остальные вести («что-то пошло не так», пропуски)
   по-прежнему помечаются до показа и так же теряются при повороте; в задачу не вошло (см. «Замечено»).
8. **Тексты `notice_journal_*` не менялись** (обоснование — в «Решение», п. 5). Подсказка в настройках — новая строка.
9. **Тесты прогнаны на временных реализациях:** Rust (317 тестов `plinth-sync` и `plinth-ffi`, clippy `-D warnings` чист) и Kotlin
   (52 теста) — патчи `r9f-reference-implementation-rust.patch` и `…-kotlin.patch` в папке заметок сессии; мутации — только Rust (ниже).
   Экранный код (`ErrorNotices.kt`, подсказка в `backupSection`, `SafMirrorFolder`) во временной реализации не писался и не проверен.
10. **Побочное:** `store/r9b_tests.rs`, `r1_4_tests.rs` и другие чужие тесты правлены механически (путь `aside/`); правка — в коммите тестов.

### Временная реализация и мутации (Rust)

Временная реализация по плану (`r9f-reference-implementation-rust.patch` в папке заметок сессии; ложится на коммит тестов, 4 файла
`core/sync/src`, `core/ffi/src`) прошла все 317 тестов `plinth-sync` и `plinth-ffi`. Девять мутаций — каждая ловится:

| Мутация временной реализации | Что падает |
|---|---|
| метка копии = время (без «не ниже прежних») | `a_fresh_copy_survives_a_clock_that_went_back` |
| недописанный временный файл не стирается при ошибке копии | `store::r9f_tests::a_copy_cut_short_leaves_nothing_behind` |
| остатки `*.tmp` не стираются при открытии | `leftovers_of_a_cut_copy_are_removed_at_open` |
| прежние копии не переносятся в `aside/` | `copies_made_by_older_builds_move_into_the_aside_folder` |
| пометка ставится при `carried > 0`, а не `== 0` | 5 тестов `api::r9f_tests` (перенос из базы, перезапуск, снятие, отказ, пустое восстановление) |
| `restore_mirror` не снимает пометку | `…stays_pending_until_restored` |
| любое восстановление (и пустое) снимает пометку | `restoring_nothing_keeps_the_mark` |
| пометка лежит в `journal/` | `the_mark_stays_out_of_the_journal_folder`, `a_mark_that_cannot_be_written_…` |
| каталог на месте пометки считается пометкой (`exists` вместо `is_file`) | `a_mark_that_cannot_be_written_does_not_stop_the_core` |

Окно сбоя `open_journal`: `start_over` заменил снимок, а пометка ещё не записана (миллисекунды) или не помещается на полный диск —
весть «верните из копии» придёт один раз (из отчёта), а напоминания в настройках не будет. Принятое ограничение (данные при этом целы:
копия в папке на месте).

## Замечено, не в этой задаче

- **Прочие вести теряются при повороте так же** (`CoreFailed`, пропуски треков): `ErrorPresenter` помечает «сказано» до показа. Для
  них это дешевле (повторять «что-то пошло не так» незачем), но принцип тот же — при случае перевести на `shown`.
- **Сбой после `start_over` в том же `open_at`** (например, `catch_up` не смог записать базу): следующая попытка видит здоровый журнал,
  и весть «журнал начат заново» не придёт; останется только напоминание в настройках (если `carried == 0`) или «Библиотека
  восстановлена» (если пересборка нужна). Чтобы весть пережила и это, ядру нужно помнить «не объявлено» отдельно от пометки.
- **«Предложить удалить файл прежней установки»** после возврата паспортов (Р9д, «Замечено») — не сделано: отдельный экран/диалог,
  v0.3.
- **Мастер первого запуска** (`BackupStep`) пометку не показывает: на первом запуске журнала ещё нет.
- **Проекция паспортов в базу** (№20 целиком) — Р9ж/v0.3, как записано в Р9д.

## Отчёт исполнителя

_Заполняет исполнитель: что сделано, что не вышло и почему, коммиты, вывод проверок (коротко)._
