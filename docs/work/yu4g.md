# yu4g — затравку радио искать только у YouTube (Ю4г)

> Файл задачи для исполнителя (Sonnet). Пишет планировщик; исполнитель меняет только
> раздел «Отчёт исполнителя». Порядок работы — `docs/work/README.md`.

> **Данные пользователя: не трогает.** Задача меняет только то, *кого* спрашивает поиск затравки радио: каталог, журнал,
> `queue.bin`, историю прослушиваний и настройки она не пишет и не читает иначе, чем раньше. Единственное, что поиск
> пишет, — здоровье провайдеров в памяти реестра (`Registry::observe`), и оно после задачи затрагивает только YouTube.
> Поэтому исполнитель идёт без чтения плана Opus. Спорное — в «На что смотреть при чтении плана (Opus)»; Opus прочтёт
> его на ревью.

- **Ветка:** `work/yu4g` · **Ворктри:** `C:\claude\Plinth-yu4g`
- **Основа:** `main` на `b17d309` · **Коммит тестов:** `87718e2` (первый в ветке)
- **Откуда:** `tasks.md`, Ю4г; находка 7 и ответ Opus 4 в `docs/work/yu4c.md`; решение `docs/decisions.md`, «Ю4в сделано».

## Цель

Нажатие «Радио» у трека с диска (или из Internet Archive) ищет затравку «исполнитель название» **только у YouTube**:
в Internet Archive запрос не уходит, и запуск радио не ждёт его ответа. Для этого у ядра появляется поиск у одного
провайдера, а `RadioStarter` пользуется им.

## Контекст

Прочитать (диапазонами, не целиком):

- `docs/work/README.md` — «Что исполнитель знает без памяти»; `CLAUDE.md` — «Работа Opus и Sonnet».
- Эта задача целиком; в `docs/work/yu4c.md` — находка 7 (строки 75–77) и «На что смотреть», пункт 4, с ответом Opus (строки 464 и 489–492).
- Ядро: `core/providers/src/registry.rs` (весь `Registry`, строки 1–181; тесты ниже не трогать),
  `core/ffi/src/api/online_api.rs` 203–215 (`online_search`), 347–362 (`online_search_within`), 458–468 (`section`, `problem_of`).
  Образцы: `core/providers/tests/r1_11a_search_limits.rs` (срок, отмена, здоровье), `core/ffi/src/api/yu3g_tests.rs` 1–160
  (платформа «youtube» в памяти и сеть Internet Archive, что считает запросы).
- Приложение: `ffi/CoreOnline.kt` 40–62 (`search`); `online/OnlineRepository.kt`, `online/CoreOnlineRepository.kt` 36–75,
  `online/GatedOnlineRepository.kt` (весь), `radio/RadioStarter.kt` (весь).
- Тестовые двойники: `sharedTest/.../online/FakeOnlineRepository.kt`, `test/.../radio/Yu4cRadioRig.kt` (класс `RadioOnline`).
- **Основа реализации** — патч планировщика `yu4g-reference-implementation.patch` в общей папке заметок сессий
  (`C:\Users\User\AppData\Roaming\Claude\side-session-notes\local_6a10ff5e-f13f-4d35-84fc-3a8758d8fb83\`, в ветке его нет):
  все семь файлов из «Менять», ложится на коммит тестов (`git apply`). Каждую строку сверить с «Решением» и «Инвариантами» —
  расхождения исправить, а не применять вслепую. Комментарии к самим методам и к `RadioStarter` патч обновляет; остальное
  доводит исполнитель: комментарий к `Registry` (перечень «поиск спрашивает всех, кроме `Down`…» — добавить `search_in`),
  комментарий к `GatedOnlineRepository` (какие методы ждут ворота — добавить `searchIn`), шапку модуля `online_api.rs`
  («поиск по провайдерам — секциями» — добавить `online_search_in`).

## Находки планировщика (по ходу работы)

Записаны 2026-10-29 по чтению кода `main` на `b17d309`.

1. **Что именно уходит и чего ждёт запуск.** `RadioStarter.search` зовёт `OnlineRepository.search(query)`, то есть
   `Core::online_search` → `Registry::search`, а тот спрашивает **каждого** включённого провайдера по очереди: сперва
   Internet Archive (`best_quality` у него выше, реестр ставит его первым), потом YouTube. Ответ YouTube приходит только
   после ответа Internet Archive; запрос на `advancedsearch` у него 1,9–5,9 с (проба 2026-10-15, комментарий к `SEARCH_BUDGET`),
   а у недоступного — до срока 15 с. Из результатов запуск берёт только секцию YouTube (`firstOrNull { it.provider == provider }`).
2. **Новый метод, а не параметр у `online_search`.** `Core::online_search(query, limit, cancel)` зовут **17 мест в 6 файлах
   Rust-тестов** (`r1_11a_tests`, `r1_11d_tests`, `yu3g_tests`, `yu3v_tests`, `yu4a_tests` и `mod tests` самого
   `online_api.rs`); у метода Rust значений аргументов по умолчанию нет. Лишний аргумент сломал бы их все, а менять
   чужие тесты исполнитель не вправе. Поэтому поиск у одного провайдера — отдельный метод, прежние вызовы не меняются.
3. **Реестру нужен свой метод.** Снаружи спросить одного провайдера с учётом здоровья, срока и отмены нечем:
   `Registry::search` обходит всех, а `find` приватен. Метод `Registry::search_in` — настоящая работа этой задачи; в `Core`
   он только обёрнут. Тот же цикл не копируется: тело обхода выносится в общий приватный шаг (см. «Решение»).
4. **`GatedOnlineRepository : OnlineRepository by inner` пропустит новый метод без ожидания.** Делегирование пересылает
   каждый член интерфейса, а ворота (`awaitSwitch`) стоят только в четырёх переопределённых. Новый `searchIn` без
   переопределения тихо обошёл бы ворота: запуск радио на холодном старте получил бы «источники выключены» вместо поиска.
   Компилятор этого не заметит, поэтому на воротах — тест (`Yu4gGatedSearchInTest`).
5. **Стенд Ю4в называет поиск по имени.** `RadioOnline` (`Yu4cRadioRig.kt`) переопределяет `search(query)`; после задачи
   `RadioStarter` зовёт `searchIn`, и четыре теста `Yu4cRadioStarterTest`, что задают `rig.online.sections` (файл с диска,
   трек без исполнителя, трек Internet Archive, «нет сети в поиске»), покраснели бы: стенд отдал бы им секцию Internet Archive
   вместо сценария (проверено на прототипе: 4 красных из 68 тестов Ю4в). Стенд — тестовый код: правку несёт коммит тестов (он
   же даёт двойнику `FakeOnlineRepository.searchIn`), сами старые тесты остаются нетронутыми и проходят, как проходили.
6. **`Down` у `search_in` как у `search`.** Провайдера в паузе (`Down`) поиск не спрашивает и отдаёт ему `Unavailable`, а
   `Registry::radio` спрашивает и `Down` (действие человека над треком). Так же было до Ю4г: пока YouTube отдыхает,
   радио от файла с диска говорит «не нашлось», хотя `radio` по найденной затравке ответил бы. Поведение не меняется
   (задача про «кого спрашивать», а не «когда»); вопрос Opus — в «На что смотреть».
7. **Часть тестов не может быть красной утверждением.** `Registry::search_in`, `Core::online_search_in`, `CoreOnline.searchIn`
   и `OnlineRepository.searchIn` — новый API: тесты крейтов и приложения до реализации не собираются (допустимо, сигнатуры — в
   «Решении»). Утверждениями красное доказывают мутации прототипа (раздел «Временная реализация»).

## Решение

### Что выбрано и почему

| Вопрос | Решение | Почему |
|---|---|---|
| **Где живёт фильтр** | В ядре: `Registry::search_in` (один провайдер из реестра) → `Core::online_search_in` (UniFFI) → `CoreOnline.searchIn` → `OnlineRepository.searchIn` → `RadioStarter.search`. Фильтра поверх `search` в Kotlin («спросить всех и взять секцию YouTube») нет | Такой фильтр оставил бы обе беды: запрос уходит в Internet Archive, и запуск ждёт его ответа. Задача — не спрашивать (находка 1) |
| **Новый метод, а не параметр** | `online_search_in` рядом с `online_search`; прежний вызов не меняется | Лишний аргумент у `online_search` ломает 17 вызовов в 6 файлах тестов, а менять их нельзя (находка 2). Новый метод ещё и честнее по имени: «секция одного», а не «секции, но не все» |
| **Что значит «нет секции»** | `Registry::search_in` → `None`, `Core::online_search_in` → `Ok(None)`, Kotlin → `null`: источники выключены, провайдер не подключён (или выключен), запрос пуст, поиск отменён до ответа. Беда провайдера — **секция с `problem`**, как у поиска по всем | Для запуска радио оба исхода — «не нашлось» (`NOT_FOUND`), но секция с бедой ещё различает «нет сети» (`NO_NETWORK`). Пустая секция вместо `None` выдумывала бы ответ, которого провайдер не давал |
| **Здоровье, срок, отмена, пауза** | Как у `search`: `Down` — не спрашивать, отдать `Unavailable`; срок вышел — «timed out» без вопроса и без вины; отмена до вопроса или пока провайдер отвечал — `None`, ответ в здоровье не пишется; иначе исход идёт в здоровье спрошенного. Чужих провайдеров не трогает вовсе | Секция одного должна быть той же, что поиск по всем даёт ему. Расхождение в паузе или сроке — два поведения одного поиска (находка 6) |
| **Общий шаг, не копия цикла** | Тело обхода `Registry::search` выносится в приватный `ask(&self, provider, query, now) -> Option<ProviderSearch>`; `search` зовёт его для каждого (`None` — конец обхода), `search_in` — для одного | Одна копия логики срока, отмены и здоровья. Рекомендация, а не требование: тесты закрепляют поведение, не устройство |
| **Негодное имя провайдера** | `Core::online_search_in` → `Err(CoreError::Parse)` (`ProviderId::new`), как `online_radio`. Для Kotlin это `CoreFailure`, а `CoreOnlineRepository.searchIn` обращает его в `null` с записью в лог (как `search`) | Имя приходит из констант приложения; негодное — ошибка программы, не состояние мира. Порядок проверок «имя / источники выключены» при выключенных источниках не закреплён |
| **Ворота холодного старта** | `GatedOnlineRepository.searchIn` ждёт первого `setEnabled`, как `search`, `album`, `radio`, `stream` (`awaitSwitch`) | Находка 4. Без переопределения делегирование пропустило бы метод мимо ворот |
| **Отмена** | `CoreOnlineRepository.searchIn` живёт вне корутины и будит ядро флагом `OnlineCancel`, как `search`. Рекомендуемая форма: общий приватный помощник на оба поиска | Новое нажатие «Радио» отменяет прежний запуск (`job?.cancel()`); без обхода блокирующий вызов держал бы поток `IO` до срока поиска (15 с) |
| **Срок и лимит** | Те же: срок `SEARCH_BUDGET` (15 с), `OnlineRepository.SEARCH_LIMIT` (20) | Запуск радио ждёт ответа YouTube не дольше, чем ждал раньше |

### Сигнатуры (дословно)

```rust
// core/providers/src/registry.rs, impl Registry
/// Ответ только `provider`, как у [`Registry::search`]: остальные не получают запроса и в здоровье не меняются.
/// `None` — никого не спросили: запрос пуст, такого провайдера нет или поиск отменён до ответа.
pub fn search_in(&self, provider: &ProviderId, query: &SearchQuery, now: Timestamp) -> Option<ProviderSearch>

// core/ffi/src/api/online_api.rs, в #[uniffi::export] impl Core, рядом с online_search
pub fn online_search_in(
    &self,
    provider: String,
    query: String,
    limit: u32,
    cancel: Arc<OnlineCancel>,
) -> Result<Option<OnlineSearch>, CoreError>
```

```kotlin
// ffi/CoreOnline.kt
fun searchIn(provider: String, query: String, limit: Int, cancel: OnlineCancel = OnlineCancel()): OnlineSection?

// online/OnlineRepository.kt — без реализации по умолчанию: забытое переопределение должно не собираться
suspend fun searchIn(provider: String, query: String): OnlineSection?
```

(Kotlin выше записан в одну строку для краткости; в коде — по правилам `ktlint` проекта: у функции с двумя и более
параметрами каждый на своей строке.)

`RadioStarter.search` после задачи: `val section = online.searchIn(provider, query)`; дальше всё как было: первый **трек** секции —
затравка, иначе `NOT_FOUND`, а при беде `NO_NETWORK` — `NO_NETWORK`. Комментарий к классу `RadioStarter` («поиск идёт ко всем
включённым провайдерам») исправить.

### Отвергнуто

- **Параметр `provider: Option<String>` у `online_search`.** Ломает 17 вызовов в тестах Rust и не нужен (находка 2).
- **Фильтр в Kotlin поверх `search`.** Не лечит ни утечку, ни ожидание (выше).
- **Поле «только этот» в `SearchQuery`.** Запрос — то, что спрашивают у провайдера, а *кого* спрашивать, решает реестр;
  провайдеры не должны знать о фильтре.
- **Реализация по умолчанию у `OnlineRepository.searchIn`** (`search(query).firstOrNull { … }`). Забытое переопределение
  тихо вернуло бы прежнее поведение (спросить всех) — как раз то, что чинится.
- **Спрашивать `Down`-провайдера, как это делает `radio`.** Меняло бы поведение поиска ради другого вопроса (находка 6);
  если понадобится — отдельное решение.
- **Несколько провайдеров в одном вызове (`Vec<String>`).** Нужен один; обобщение — когда появится второй провайдер радио.

## Инварианты

- **Поиск по всем не изменился:** `Registry::search`, `Core::online_search`, `OnlineRepository.search` отвечают, как
  отвечали (те же секции, порядок, срок, отмена, здоровье). Ни один старый тест не менялся: все они проходят без правок.
- **Спрошен один:** при `search_in(P)` у остальных провайдеров не вызывается `search`, и в их здоровье ничего не пишется;
  Internet Archive при поиске затравки не получает ни одного запроса.
- **Секция одного — та же, что поиск по всем даёт ему:** удача, пустой ответ и каждая беда (`NoNetwork`, `ProviderDown`, `Broken`).
- **Отмена — не вина провайдера:** ответ, пришедший после отмены, в здоровье не пишется, секции нет; три отменённых запуска
  радио подряд не отправляют YouTube на отдых.
- **Пауза и срок — как у поиска:** провайдера в `Down` не спрашивают и отдают `Unavailable`; вышедший срок — «timed out» без
  вопроса и без вины.
- **Запуск радио не берёт чужую секцию:** какие бы секции ни пришли, затравка — первый трек секции YouTube. Исходов три: радио;
  `NO_NETWORK` (у YouTube нет сети); `NOT_FOUND` (всё остальное).
- **Ворота:** `searchIn` до первого применения переключателя ждёт, но не дольше `waitLimit`; выключили — `null` сразу.
- **Данные пользователя не меняются:** задача ничего не пишет в каталог, журнал, очередь и историю.

## Файлы

- **Менять:**
  - `core/providers/src/registry.rs` — `search_in`, общий шаг, комментарий к `Registry`; модуль `tests` внизу файла не трогать
  - `core/ffi/src/api/online_api.rs` — `online_search_in`; модуль `tests` внизу файла не трогать
  - `app/src/main/kotlin/io/github/puflik/plinth/ffi/CoreOnline.kt` — `searchIn`
  - `app/src/main/kotlin/io/github/puflik/plinth/online/OnlineRepository.kt` — `searchIn`
  - `app/src/main/kotlin/io/github/puflik/plinth/online/CoreOnlineRepository.kt` — `searchIn`, общий помощник поиска
  - `app/src/main/kotlin/io/github/puflik/plinth/online/GatedOnlineRepository.kt` — `searchIn` за воротами, комментарий к классу
  - `app/src/main/kotlin/io/github/puflik/plinth/radio/RadioStarter.kt` — `search` через `searchIn`, комментарий к классу
- **Не трогать:** всё остальное; явно — `Core::online_search` и `online_search_within`, `SearchViewModel` и прочих, кто зовёт
  `OnlineRepository.search`; `core/ffi/src/api/mod.rs` (строка `mod yu4g_tests;` уже в коммите тестов); `Cargo.lock`;
  `CHANGELOG.md`, `tasks*.md`, `docs/decisions.md`, `docs/plan/**`, `CLAUDE.md`, `gradle/libs.versions.toml`;
  **все старые тесты** и тестовые двойники, кроме перечисленных ниже как правленные коммитом тестов.
- **Защищённые тесты** (из коммита тестов; не менять, не удалять, не глушить):
  - `core/providers/tests/yu4g_search_in.rs` (10)
  - `core/ffi/src/api/yu4g_tests.rs` (11) и строка `mod yu4g_tests;` в `core/ffi/src/api/mod.rs`
  - `app/src/test/kotlin/io/github/puflik/plinth/radio/Yu4gRadioSeedSearchTest.kt` (4)
  - `app/src/test/kotlin/io/github/puflik/plinth/online/Yu4gGatedSearchInTest.kt` (4)
  - `app/src/androidTest/kotlin/io/github/puflik/plinth/online/Yu4gSearchInCancelTest.kt` (3, инструментальный, эмулятор)
  - `app/src/sharedTest/kotlin/io/github/puflik/plinth/online/OnlineRepositoryContractTest.kt` — пять тестов Ю4г в конце
    (`a_search_in_one_provider_…`, `a_search_in_a_provider_that_is_not_there_…`, `a_blank_query_in_one_provider_…`,
    `without_network_the_section_of_one_provider_…`, `switched_off_there_is_no_section_of_one_provider`); на фейке идут в JVM,
    на ядре — на эмуляторе (`CoreOnlineRepositoryContractTest`)
  - двойники, правленные коммитом тестов: `app/src/sharedTest/kotlin/io/github/puflik/plinth/online/FakeOnlineRepository.kt`
    (`searchIn`), `app/src/test/kotlin/io/github/puflik/plinth/radio/Yu4cRadioRig.kt` (`RadioOnline`: `searchIn`,
    `searchedIn`, `wide`; старые тесты Ю4в проходят на нём как проходили)

## Тесты (написаны в коммите тестов)

Без нового API они не собираются: `Registry::search_in` (тесты `plinth-providers`), `Core::online_search_in` (`plinth-ffi`),
`OnlineRepository.searchIn` / `CoreOnline.searchIn` (`compileGithubDebugUnitTestKotlin`). Сигнатуры — в «Решении».

**`yu4g_search_in.rs` (10) — реестр.** Спрошен только названный: у Internet Archive и у YouTube по очереди, чужой не вызван
ни разу; секция одного равна секции того же провайдера в `search` (удача, пустой ответ, отказ); провайдера, которого нет в
реестре, и пустой запрос — `None`, никто не спрошен; **здоровье чужих цело** (Internet Archive, что отказывал бы при первом же
вопросе, после трёх поисков у YouTube здоров и не спрошен, а потом поиском по всем спрошен и отказывает — страж, что сценарий
настоящий); исход идёт в здоровье спрошенного — отказ → `Down`, отдыхающего не спрашивают (`Unavailable` на 30-й секунде), на
61-й спрашивают снова и удача возвращает здоровье; отменён во время вопроса — `None`, здоровье цело, следующий поиск работает;
отменён до начала — `None`, не спрошен; срок ноль — «timed out» без вопроса и без вины.

**`yu4g_tests.rs` (11) — ядро.** Платформа «youtube» в памяти, Internet Archive на записанных ответах со счётчиком запросов.
`online_search_in("youtube")` — секция с одним видео и **ноль запросов к Internet Archive**; `online_search_in("archive.org")` —
его секция, платформа не спрошена (фильтр не привязан к YouTube); поиск по всем — как прежде: `[archive.org, youtube]`, оба
спрошены (страж); запрос обрезан по краям и лимит дошёл до платформы («Billy Joel Piano Man», 7); неподключённый провайдер
(Internet Archive при `archive = false`, `bandcamp`) — `Ok(None)`; источники выключены (не подключали вовсе, подключили и
отключили) — `Ok(None)`; пустой запрос («», пробелы, `\t\n`) — `Ok(None)`; негодное имя («», «YouTube», «you tube», «a/b») —
`Err(Parse)` без вопросов; отменённый заранее — `Ok(None)`; секция одного равна секции поиска по всем для удачи, `Network`,
`Unavailable`, `Parse`; отказ платформы — секция с `ProviderDown`, а не пустота и не ошибка.

**`Yu4gRadioSeedSearchTest` (4) — запуск радио.** Файл с диска: затравка ищется у `youtube` один раз (`searchedIn`), поиска по
всем нет (`wide` пуст), запрос «Queen Bohemian Rhapsody», затравкой становится видео YouTube, а не «песня» из секции Internet
Archive (приманка); трек Internet Archive и трек без исполнителя — то же; ответ YouTube решает исход (у него нет сети —
`NO_NETWORK`, секции YouTube нет вовсе, а у архива есть «песня» — `NOT_FOUND`); трек YouTube из каталога поиска не требует вовсе.

**`Yu4gGatedSearchInTest` (4) — ворота.** Поиск до переключателя ждёт и после него спрашивает (секция с концертом, запрос
записан); выключили — `null` сразу; переключатель не пришёл — идёт дальше через `waitLimit` (5 с) и получает `null`; после
переключателя — сразу, а чужой провайдер — `null`. Без переопределения в `GatedOnlineRepository` первый тест красный: поиск
вернулся бы `null` сразу.

**Общий контракт (5)** — на фейке (JVM) и на ядре (эмулятор): секция одного равна секции поиска по всем
(`results.single().external` — концерт); провайдер, которого нет, — `null`; пустой запрос — `null`; без сети — секция с
`NO_NETWORK` и пустыми результатами, не ошибка; выключено (главным, выключенным провайдером) — `null`, включили снова — секция есть.

**`Yu4gSearchInCancelTest` (3, инструментальный)** — как `CoreOnlineRepositoryCancelTest` для `searchIn`: отмена возвращается
сразу, отменённый поиск не повторяется, поиск без отмены отвечает (страж).

## Проверки

Из PowerShell, в ворктри `C:\claude\Plinth-yu4g`:

```text
cd core; cargo test -p plinth-providers; cargo test -p plinth-ffi; cargo clippy --workspace --all-targets -- -D warnings; cargo fmt --all -- --check
```

```text
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
.\gradlew.bat testGithubDebugUnitTest --tests "*Yu4g*" --tests "*Yu4c*" --tests "*GatedOnlineRepository*" --tests "*FakeOnlineRepository*"   # защищённые и соседние
.\gradlew.bat ktlintCheck detekt testGithubDebugUnitTest lintGithubDebug --continue                                                         # всё остальное
.\gradlew.bat assembleGithubDebug compileGithubDebugAndroidTestKotlin                                                                       # Hilt и инструментальные тесты собираются
```

Инструментальные — только на эмуляторе `Medium_Phone_API_36.0` (`-no-window`, после работы `adb emu kill`),
`$env:ANDROID_SERIAL = "emulator-5554"` в той же команде, что Gradle, по одному классу за прогон (запятая в списке классов
теряет всё, кроме первого):

```text
$env:ANDROID_SERIAL = "emulator-5554"; .\gradlew.bat connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=io.github.puflik.plinth.online.Yu4gSearchInCancelTest"          # защищённый, 3 из 3
$env:ANDROID_SERIAL = "emulator-5554"; .\gradlew.bat connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=io.github.puflik.plinth.online.CoreOnlineRepositoryContractTest"   # прежние + 5 новых
$env:ANDROID_SERIAL = "emulator-5554"; .\gradlew.bat connectedGithubDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=io.github.puflik.plinth.online.CoreOnlineRepositoryCancelTest"     # страж: поиск по всем после выноса помощника
```

Число выполненных тестов — по `TEST-*.xml`, а не по «зелёной» сборке. Устройства автора не трогать.

Известное: на Windows падают 7 тестов `DataStore*SettingsTest` (`IOException` в `FileStorage.kt:114`) — не из-за задачи; в CI на
Linux проходят.

## Готово, когда

- 10 защищённых тестов реестра и 11 тестов ядра зелёные; прочие тесты `plinth-providers` и `plinth-ffi` — как были; `clippy` и
  `fmt` чисты.
- 4 + 4 защищённых JVM-теста зелёные, 5 тестов контракта зелёные на фейке; все тесты Ю4в, `GatedOnlineRepository*`,
  `FakeOnlineRepository*` и поиска — как были (кроме известных 7 `DataStore*` на Windows).
- `ktlintCheck`, `detekt`, `lintGithubDebug` чисты; `assembleGithubDebug` и `compileGithubDebugAndroidTestKotlin` собираются.
- На эмуляторе: `Yu4gSearchInCancelTest` 3 из 3; `CoreOnlineRepositoryContractTest` — все, включая пять новых;
  `CoreOnlineRepositoryCancelTest` — как был.
- **Критерий скорости — счёт запросов, не секундомер:** при поиске затравки в Internet Archive уходит ноль запросов
  (`the_platform_is_asked_and_the_archive_is_not`), то есть запуск радио не ждёт его ответа. Замер секундомером на телефоне — Ю6.
- Комментарии не врут: перечень в комментарии к `Registry`, список методов за воротами в комментарии к
  `GatedOnlineRepository`, шапка `online_api.rs` и комментарий к `RadioStarter` упоминают поиск у одного провайдера и больше не
  говорят «ко всем включённым».
- Защищённые тесты не тронуты: `git diff <коммит тестов> HEAD -- <файлы защищённых тестов>` пуст; изменены только файлы из
  «Менять»; новых `#[allow]`, `#[ignore]`, `@Ignore`, `@Suppress`, `!!` нет.

## Остановиться и написать, если

- защищённый тест кажется неверным;
- для решения нужно менять файл вне списка «Менять» (в том числе старый тест);
- после выноса общего шага из `Registry::search` краснеет любой **старый** тест (`registry::tests`, `r1_11a_*`, `yu3v_*`,
  `yu3g_*`) — не править тест, стоп;
- UniFFI не принимает `Result<Option<OnlineSearch>, CoreError>` (не ожидается: прототип собирается);
- хочется сделать фильтр в Kotlin поверх `search` или дать `searchIn` реализацию по умолчанию — это не решение (см. «Отвергнуто»);
- проверки упали дважды подряд (одна попытка исправить, потом стоп);
- на эмуляторе поведение расходится с тестами (например, отмена `searchIn` ждёт сеть).

## На что смотреть при чтении плана (Opus)

Данных пользователя задача не трогает, поэтому исполнитель идёт без этого чтения; ниже — то, что Opus проверит на ревью, и
вопросы. К каждому — совет планировщика.

1. **Новый метод `online_search_in`, а не параметр у `online_search`** (находка 2): 17 вызовов в тестах Rust остаются как есть.
   *Совет: принять.*
2. **`Down` как у поиска, не как у радио** (находка 6): пока YouTube отдыхает после отказов подряд, «Радио» от файла с диска
   скажет «не нашлось», хотя `radio` по найденной затравке ответил бы. Так было и до задачи. **Вопрос Opus:** оставить?
   *Совет: оставить; если автор услышит такой случай на телефоне (Ю6) — отдельное решение «спрашивать `Down` при действии
   человека», оно коснётся `search_in` одной строкой.*
3. **«Нет секции» (`null`) и «секция с бедой» — разные исходы** (таблица «Решение»). Для запуска радио оба — «не нашлось», но
   беда сети различима. Состояние «настройки говорят “включено”, а ядро ещё не подключено» запуск увидит как `null` →
   `NOT_FOUND`; ворота `GatedOnlineRepository` как раз ловят его на холодном старте. *Совет: принять.*
4. **Негодное имя — ошибка вызова, в Kotlin — `null` с записью в лог.** Единственный вызывающий передаёт константу из
   `OnlineProviderIds.RADIO`, поэтому случай теоретический. *Совет: принять.*
5. **Что видит человек.** Только скорость: запуск радио от файла с диска не ждёт Internet Archive (было 1,9–5,9 с на живом
   ответе, до срока 15 с при молчании). Вести те же. То, что запрос с тегами файла теперь видит один YouTube, а не ещё и
   Internet Archive, — это и есть слова плана 9.5 о том, что провайдер видит запрос. Запись в CHANGELOG — на усмотрение Opus при
   слиянии.
6. **Двойники правит коммит тестов** (`FakeOnlineRepository`, `Yu4cRadioRig`): старые тесты Ю4в не менялись и проходят на новом
   `RadioOnline`; его `queries` пишет оба вида поиска, поэтому прежние утверждения про `queries` живы.
7. **Реализация по умолчанию у `OnlineRepository.searchIn` отвергнута** — каждый, кто реализует интерфейс, обязан ответить явно
   (в тестах такой один — `FakeOnlineRepository`). *Совет: принять.*
8. **Эмулятор.** Контракт на ядре и отмена — инструментальные; без эмулятора проходят только JVM-тесты. Планировщик гонял их на
   прототипе (раздел ниже).

## Временная реализация и проверка тестов

**Прототип** (проверено 2026-10-29) лежит патчем в общей папке заметок сессий (`yu4g-reference-implementation.patch`: семь файлов
из «Менять», +108/−24 строки; в ветке его нет); рядом — `yu4g-mutate.py` (18 мутаций прототипа по одной правке),
`yu4g-mutate.json` (их итог) и `yu4g-planner-notes.md`. Против него:

- **красное до реализации:** на ветке без патча тесты не собираются — нет `Registry::search_in` (`cargo test -p plinth-providers`),
  `Core::online_search_in` (`cargo test -p plinth-ffi`), `OnlineRepository.searchIn` (`compileGithubDebugUnitTestKotlin` и
  `compileGithubDebugAndroidTestKotlin`): новый API, сигнатуры — в «Решении». Утверждениями, а не сборкой, красное доказывают
  мутации ниже. Отдельно проверено, что без правки стенда `RadioOnline` краснеют 4 из 68 тестов Ю4в (находка 5);
- **зелёное с патчем:** Rust — 10 из 10 в `yu4g_search_in.rs`, 11 из 11 в `yu4g_tests.rs`; весь `cargo test -p plinth-providers` и
  `-p plinth-ffi` (191 из 191) зелёные; `clippy --workspace --all-targets -- -D warnings` чист; `cargo fmt --all -- --check` без
  настоящих `Diff in`. Kotlin — 116 из 116 в `*Yu4g*` (8), `*Yu4c*` (68), `*GatedOnlineRepository*` (15), `*FakeOnlineRepository*`
  (25, из них 5 новых контрактных); `ktlintCheck`, `detekt`, `lintGithubDebug` чисты, `assembleGithubDebug` (Hilt) и
  `compileGithubDebugAndroidTestKotlin` собираются (на первом прогоне `detekt` и `ktlint` поймали две длинные строки — в
  комментарии `RadioStarter` и в моём тесте; исправлены). Весь `testGithubDebugUnitTest`: 1018 тестов, 1 пропущен, 7 упали — все `DataStore*SettingsTest` (известный Windows-сбой, `FileStorage.kt:114`); на `main` после Ю4в было 1005, разница — 13 новых JVM-тестов (8 Ю4г и 5 контрактных на фейке);
- **эмулятор** (`Medium_Phone_API_36.0`, `-no-window`, `ANDROID_SERIAL=emulator-5554`; после прогона `adb emu kill`):
  `Yu4gSearchInCancelTest` 3 из 3, `CoreOnlineRepositoryContractTest` 19 из 19 (14 прежних + 5 новых — `Option<Record>` через UniFFI
  доходит до Kotlin как `null` и как секция), `CoreOnlineRepositoryCancelTest` 5 из 5 (числа — по `TEST-*.xml`); эмулятор погашен,
  устройств автора не было;
- **мутации** (`yu4g-mutate.py`: 18 правок одной строки основного кода, каждая запускает защищённые тесты своего слоя; плюс одна
  на эмуляторе): убиты **все 19**.

  | № | Правка прототипа | Красные тесты |
  |---|---|---|
  | R1 | `search_in`: пустой запрос тоже спрашивает | `a_blank_query_asks_nobody` |
  | R2 | `search_in`: берёт первого провайдера, а не названного | четыре: `only_the_named_provider_is_asked`, `the_answer_is_the_one…`, `the_others_are_not_touched…`, `a_provider_the_registry_does_not_have…` |
  | R3 | `ask`: отмену до вопроса не смотрит | `a_search_in_cancelled_before_it_starts…` |
  | R4 | `ask`: ответ, пришедший после отмены, пишется и отдаётся | `a_search_in_cancelled_while_asking…` |
  | R5 | `ask`: паузу (`Down`) не уважает | `the_outcome_goes_into_the_health…` |
  | R6 | `ask`: вышедший срок не замечает | `a_search_in_with_no_time_at_all…` |
  | R7 | `ask`: исход не идёт в здоровье | `the_others_are_not_touched…`, `the_outcome_goes_into_the_health…` |
  | R8 | `search_in`: спрашивает всех и берёт нужного (фильтр после вопроса) | `only_the_named…`, `the_first_provider_of_the_order…`, `the_others_are_not_touched…` |
  | F1 | `online_search_in`: то же в ядре — `registry.search` и отбор | четыре, в том числе `the_platform_is_asked_and_the_archive_is_not` |
  | F2 | `online_search_in`: отмена не подключена | `a_search_cancelled_before_it_starts…` |
  | F3 | `online_search_in`: лимит не доходит до платформы | `the_query_and_the_limit_reach_the_platform…` |
  | F4 | `online_search_in`: негодное имя молча даёт «нет секции» | `a_bad_provider_name_is_an_error…` |
  | F5 | `online_search_in`: выключенные источники — ошибка вызова | `with_the_sources_off_there_is_no_section` |
  | F6 | `online_search_in`: беда провайдера пропадает из секции | `the_section_is_the_one…`, `a_platform_that_does_not_answer…` |
  | K1 | `RadioStarter`: прежний поиск по всем с отбором | три из четырёх `Yu4gRadioSeedSearchTest` |
  | K2 | `GatedOnlineRepository`: `searchIn` мимо ворот | два из четырёх `Yu4gGatedSearchInTest` |
  | K3 | `RadioStarter`: ищет у другого провайдера | три из четырёх `Yu4gRadioSeedSearchTest` |
  | K4 | `FakeOnlineRepository.searchIn`: отдаёт любую секцию | `a_search_in_a_provider_that_is_not_there…` (контракт на фейке) и `Yu4gGatedSearchInTest` |
  | K5 | `CoreOnlineRepository.searchIn` без обхода отмены (эмулятор) | `Yu4gSearchInCancelTest`: 2 из 3 |

  Живых мутаций нет. Первая редакция R5 (`if false`) не собралась — заменена на `if false && …`; это правка мутации, не теста.
- **не покрыто прототипом:** живая сеть YouTube и вид радио на телефоне автора (Ю6). Критерий «запуск не ждёт Internet Archive» закреплён
  счётом запросов (ноль к Internet Archive), секундомер — на телефоне. Прототип по-прежнему требует сверки каждой строки с «Решением»:
  тесты двойниками доказывают поведение, а не имена и комментарии.

Исполнителю: патч — основа, а не истина; `ktlintCheck`/`detekt` на нём чисты, но если что-то из «Решения» исполнитель считает неверным,
стоп и вопрос в отчёте.

## Отчёт исполнителя

_Заполняет исполнитель: что сделано, что не вышло и почему, коммиты, вывод проверок (коротко)._
