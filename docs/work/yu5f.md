# yu5f — независимые секции онлайн-поиска

> **VM-B1 review 2026-11-17: PASS.** Исправление `f812ee8`, проверенный handoff `362ab9e`; baseline14 `b52b256` сохранён, свежие85/85 и style/compile PASS. Независимый отчёт `yu5f-vmb1-fix-review-20261117.md`. Полный CI44116043234 на точном `362ab9e` SUCCESS (проверено18ноября); следующий этап — UI/scroll с воспроизведением на эмуляторе. Задача yu5f пока не завершена и не сливается.

- **Ветка:** `work/yu5f` · **Ворктри:** `/home/puflik/src/Plinth-yu5f`
- **Исходная основа:** `main` на `af5ecb3`; 2026-11-13 принят main `419ae48` обычным merge `257546c`, без переписывания опубликованной ветки.
- **Принятый коммит тестов:** `92c78bdb5220542ed1425e285ee1d44883b7eac9` — 12 защищённых путей после B1-коррекции, 23 readiness-сценария (17+6); полные style PASS, constructor RED9; независимое Astra B1/B2 review 15 ноября PASS. Предыдущий split-кандидат `6e8c32a2e4da0cd3a4341055138406d85f79420b` сохранён; предыдущий кандидат `dd33755a463b0e344e24dd79436cc1d3638ff45e` — для точной сверки прежних 22 тел. Историческая опора семи файлов `cbe6934fcd82f203622a490031e9a0e054272fee`, первый baseline `25c1203670682420f9a51c902cd22c60556ac6f5` сохраняются для сравнения.
- **Принятый VM-B1 baseline:** `b52b256eae8486e6fb92c7094039460740755b25` — 14 protected paths, runtime RED3/PASS82, full style/обе test Kotlin compilations PASS; независимый Astra xhigh review 2026-11-15 PASS, отчёт `yu5f-vmb1-tests-review-20261115.md`. Прежние 12 дополнительно закреплены на `92c78bd`; это приёмка тестов, не VM.
- **Данные пользователя:** нет: только поиск и положение UI; журнал/каталог/CRDT не меняются.
- **Планировщик фактически:** `gpt-6.1-sol`, `xhigh`, 2026-11-04/14/15; последний проход — три VM-B1 runtime RED guards и точная CI-C1 migration, без production.
- **Исторический запуск планировщика 2026-11-04:** ровно один агент, без вложенных агентов, реализации и массовых сборок. Перед запуском 75% пятичасового / 12% недельного окна; автор разрешил разовое исключение. Лимиты сообщил координатор.

## Цель

Секции Internet Archive и YouTube появляются независимо по мере ответов, в таком порядке. В обзоре максимум пять строк каждой секции и «Показать все». Возврат из плеера сохраняет положение списка.

## Проверено планировщиком

- `graphify query "SearchViewModel online provider sections searchIn search scroll return player" --budget 1500` упал с `ModuleNotFoundError: No module named 'graphify'`. Граф основного repo отстаёт (`built_at_commit=7c5192a`); пакеты не устанавливались, граф не перестраивался. Связи сверены по ограниченным диапазонам исходников.
- `SearchViewModel.answerTo` ждёт общего `online.search`, кеширует только окончательный ответ. `OnlineProviderIds.ALL` уже задаёт нужный порядок.
- `CoreOnlineRepository.searchIn` пользуется `outliving` с отдельным `OnlineCancel`; `Registry::search_in` и общий поиск пользуются одним `ask`, то есть здоровье/отдых/deadline/cancel сохраняются. Правки ядра не нужны.
- Ветка и ворктри созданы от указанного main; скопирован только `local.properties`, сборочные каталоги не переносились. Незакоммиченные решения основного repo не затронуты.

## Историческое ограничение планировщика — миграция разрешена

`SearchViewModelCancelTest.SilentOnline` переопределяет лишь общий `search`; `searchIn` делегируется внутреннему fake. При переходе VM на `searchIn` старый mock перестанет наблюдать отмену. `SearchViewModelRetryTest` также требует общий `OnlineResults.Searching` и общий счётчик `search`. Производственный код не должен делать лишний общий сетевой запрос ради такого mock. Существующие защищённые тесты в этой ветке не менялись; вопрос о корректной миграции передан координатору.

## Контекст исполнителя

Читать только нужные диапазоны: `docs/work/yu5a.md:561–571, 666–680`, `docs/decisions.md:6970–6977`, `tasks.md:395`, `docs/work/README.md`, `AGENT_ROLES.md`; далее `SearchViewModel.kt:50–135, 215–285`, `SearchScreen.kt:110–205`, `MainActivity.kt:133–180`, `online/CoreOnlineRepository.kt:35–94`, `OnlineProviderIds.kt`, `OnlineSettings.kt`, `core/providers/src/registry.rs:60–115`. Номера на основе af5ecb3. Код и решения первичны: граф отстаёт.

Действующий порядок — **yu5f → yu5b → yu5c → yu5d → yu5e → Ю6**. Подсказка при выключенных провайдерах — yu5b; страницы/плейлисты YouTube — yu5e. Здесь не менять ядро, предел 20 сетевых результатов и очередь/журнал.

## Принятое решение для реализации

### Получение и состояние секций

1. После прежнего debounce 300 мс взять trimmed query, главный `enabled` и `disabledProviders`. Активные ID — `OnlineProviderIds.ALL` без выключенных; порядок уже правильный. Пустой query или выключенный главный переключатель дают `None` сразу; пустой набор активных провайдеров также `None`, без новой подсказки.
2. На один запрос запускать отдельные дочерние корутины `online.searchIn(provider, text)` параллельно. Не звать общий `online.search`: иначе у одного провайдера будут дубли запросов. Все обновления состояния и памяти — в контексте ViewModel; ответ одного провайдера публиковать сразу. Исключение одного не отменяет соседний: `CancellationException` пробрасывать, прочие `Exception` переводить в `OnlineSection(provider, emptyList(), OnlineProblem.BROKEN)`. Не ловить `Error`.
3. Сохранить существующие `OnlineResults.None`, `Searching` и `Found` для совместимости исходников; новый путь поиска с самого начала отдаёт `Found(emptyList(), loading = activeIds.toSet())`. Новый точный API:

```kotlin
data class Found(
    val sections: List<OnlineSection>,
    val loading: Set<String> = emptySet(),
) : OnlineResults

fun onRetryOnline(provider: String)
```

`sections` — только завершённые **ненулевые объекты** OnlineSection, включая пустые/ошибочные, в порядке ALL; `loading` — ID ещё ожидаемых. Множества sections.provider и loading не пересекаются. UI берёт объединение ID sections/loading и рисует его в ALL order: заголовок и собственное «Ищем…» сразу у каждого. Завершённая пустая секция — «ничего не нашлось»; `null` от searchIn — секции нет, убрать ID из loading и пометить завершённым в памяти. `null` не превращать в вечное ожидание. Пустые/ошибочные секции соседей не стирать.
4. Память — для текущего trimmed query, отдельно на активного провайдера: получен ответ (включая problem и null) или ещё ждём. На новом settled query/выключении главного переключателя отменить предыдущую группу, очистить ответы и инвалидировать generation; очистка поля делает это сразу, без debounce. **Изменение disabledProviders согласует набор по ID:** удалённый ID отменить, убрать из sections/loading/cache и инвалидировать его attempt; добавленный запустить заново; соседние завершённые ответы и работающие запросы сохранить. Отдельная generation запроса плюс token попытки и проверка active membership не дают позднему ответу попасть в новое состояние. Не менять библиотечный поиск и SongState/OnlineSongs. Unknown ID в disabledProviders не перезапускает известные провайдеры.
5. `WhileSubscribed(5_000)` сохранить. Уход > 5 с отменяет только ещё работающие запросы, завершённые ответы остаются. При возврате первым кадром прежний snapshot; повторно запустить только отменённых незавершённых провайдеров. Возврат в пределах grace не создаёт повторов. Проблема также завершённый ответ: повторяется только вручную.
6. Новая `onRetryOnline(provider)` запрашивает только названного активного провайдера для текущего settled query; его секцию заменяет loading, соседние ответы/ожидание/работа остаются. Повтор во время loading не плодит запросы, включая два вызова до следующего dispatch; повторная подписка не переигрывает retry. Чужой ID, пустой query или выключенные источники — no-op. Существующую `onRetryOnline()` оставить как программное обновление всех активных секций; UI использует только overload с ID. Отдельная generation попытки защищает от старого retry. Возвращённый provider должен соответствовать запрошенному ID; нарушение даёт пустую BROKEN-секцию **запрошенного ID**, сосед не изменяется.

7. Job запроса принадлежит подписке на uiState, а cache — ViewModel: нельзя запускать бесконтрольные запросы в viewModelScope, которые продолжатся после grace. При остановке отменённый pending остаётся незавершённым; `finally` устаревшей попытки не очищает loading новой. При restart/retry сначала согласовать актуальный query/enabled/active set, затем использовать память. Требование первого прежнего кадра относится к неизменившемуся контексту поиска. Обновления snapshot атомарны и сериализованы в Main; `CancellationException` не преобразуется в BROKEN. Без общего поискa, внутренних подписок навечно и вторых сетевых вызовов для совместимости тестов.

### Пять строк и «Показать все» — inline принято координатором

Принятый вариант: раскрывать **внутри поиска только выбранную секцию**, без нового route, запроса сети и каталожных действий. В обзоре `results.take(5)`; кнопка только при `results.size > 5`. После раскрытия показывать уже полученный полный ответ (до прежних 20), кнопку убрать; соседняя секция остаётся ограниченной пятью. Раскрытие живёт в `rememberSaveable` на trimmed query и provider: возврат из плеера/альбома сохраняет его; новый query сбрасывает. Существующие onSong/onOpenAlbum продолжают работать из обеих форм. Новый экран, вкладки и «Свернуть» не входят в задачу.

Выбор координатора получен 2026-11-04 в задании независимого ревью; вопрос закрыт. UI-тест закрепляет раскрытие до 20, независимость соседа, отсутствие кнопки при пяти результатах, сохранение после плеера и сброс на новом query. Одна лишь успешная компиляция не подтверждает runtime этих guards.

Для UI-тестов нужны стабильные теги существующих кнопок, без пользовательского текста с техническими именами: `search-show-all-<provider>` и `search-retry-<provider>`. Подписи «Show all»/«Показать все» — строковые ресурсы `search_show_all`, одинаковый ключ в values и values-ru.

### Сдвиг после плеера

На main `SearchScreen` создаёт LazyColumn в двух ветках, состояние по умолчанию находится внутри них. `MainActivity` после первого старта игры добавляет мини-плеер и уменьшает viewport. Историческое наблюдение: первый возврат дал +≈209 px, второй — нет. Это **гипотеза о месте сбоя, а не доказанная причина**.

Первый шаг исполнителя: воспроизвести на явно выбранном эмуляторе; записать до/после firstVisibleItem key, index, scrollOffset и высоту viewport при первом/втором возврате (верх, середина и конец длинного списка). Проверить также приход второй секции, когда пользователь уже прокрутил первую. Держать один явный `rememberLazyListState()` над ветками, одни provider-prefixed стабильные ключи у заголовков/rows/loading/error/Show all; не вызывать scrollToItem(0) при ответе сети/возврате. Сохранение key+offset должно переживать навигационный save/restore и появление мини-плеера. Уменьшение окна само по себе допустимо, скачок верхней видимой строки — нет. Если источник окажется вне SearchScreen, остановиться и запросить расширение списка файлов; `MainActivity`/NavHost пока не разрешены.

Новый UI тест выполняет настоящий SearchScreen/действие PLAY с fake audio, удаляет и восстанавливает поиск через SaveableStateHolder, меняет доступную высоту на 64 dp. Это приближает жизненный цикл навигации; не заменяет живой NavHost и не доказывает исторический дефект без выполнения. Точность — та же видимая песня и top текста ±1 px после первого и второго возврата, без новых запросов.

Уточнение ревью: сохранение LazyListState штатно хранит index/offset, а key стабилизирует вставки в живую композицию; само по себе это не доказательство key restore после удаления композиции и прихода второй секции. Текст песни в guard также не обязательно первая видимая строка. Проверка key/index/offset в живом сценарии обязательна; размеры в px, viewport и границы прокрутки записать отдельно. Не добавлять ручной scrollToItem по одному наблюдению. У row key различать library/provider, external и kind; songKey оставить прежним для OnlineSongs, отдельный UI key не меняет контракт действий.

## Инварианты

- Один активный запрос на provider/query/attempt; быстрый ответ не ждёт медленного. Порядок всегда Internet Archive → YouTube, независимо от времени ответа.
- Ни ошибки, ни retry, ни отмена одного провайдера не теряют ответ другого. Для нового query не появляются поздние строки старого.
- Секции выключенных провайдеров не спрашиваются и не висят в loading. Идут прежние GatedOnlineRepository/CoreOnlineRepository/Registry, без обхода health/deadline/cancel.
- Кешируется полный ответ до 20; пять строк — только представление. «Показать все» не ищет снова, не делает resolve/add и не меняет очередь.
- Поиск библиотеки, контексты PLAY, меню песен, один resolve/add на песню, liked/busy/events и onOpenAlbum — как yu5a.
- Стабильные ключи различают provider, external и вид результата; новый ответ секции не пересоздаёт весь список.
- Ответ/ошибка и положение экрана переживают возврат. Завершённые секции не запрашиваются повторно при новой подписке.
- Журнал/каталог/CRDT, зависимости и core API не меняются.

## Файлы

Все пути ниже от корня ворктри `/home/puflik/src/Plinth-yu5f`.

**Менять исполнителю после снятия блокеров:**

- `app/src/main/kotlin/io/github/puflik/plinth/ui/search/SearchViewModel.kt` — orchestration/cache/retry/state.
- `app/src/main/kotlin/io/github/puflik/plinth/ui/search/SearchScreen.kt` — independent section UI, preview/раскрытие, saved list state/keys.
- `app/src/main/res/values/strings.xml` и `app/src/main/res/values-ru/strings.xml` — только подпись Show all; общие файлы явно назначены этой задаче.
- Новые дополнительные UI-тесты, если нужны: `app/src/androidTest/kotlin/io/github/puflik/plinth/ui/search/Yu5fAdditionalSearchScreenTest.kt`; JVM `Yu5fAdditionalSearchTest.kt` теперь защищён в VM-B1 candidate.
- `docs/work/yu5f.md` — только «Отчёт исполнителя».

**Защищённые тесты и fixtures (14 путей VM-B1 candidate; не менять/удалять/глушить исполнителю):**

- `app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5fProgressiveSearchTest.kt`.
- `app/src/androidTest/kotlin/io/github/puflik/plinth/ui/search/Yu5fSearchSectionsScreenTest.kt`.
- `app/src/test/kotlin/io/github/puflik/plinth/ui/search/SearchViewModelCancelTest.kt`.
- `app/src/test/kotlin/io/github/puflik/plinth/ui/search/SearchViewModelRetryTest.kt`.
- `app/src/test/kotlin/io/github/puflik/plinth/ui/search/SearchViewModelTest.kt`.
- `app/src/sharedTest/kotlin/io/github/puflik/plinth/online/FakeOnlineRepository.kt`.
- `app/src/sharedTest/kotlin/io/github/puflik/plinth/online/TestYoutubeOnline.kt`.
- `app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5aSearchSongsTest.kt`.
- `app/src/androidTest/kotlin/io/github/puflik/plinth/ui/search/SearchFieldTest.kt`.
- `app/src/androidTest/kotlin/io/github/puflik/plinth/ui/search/Yu5aSearchSongsScreenTest.kt`.
- `app/src/sharedTest/kotlin/io/github/puflik/plinth/online/FakeOnlineReadiness.kt`.
- `app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5fReadinessSearchTest.kt`.
- `app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5fAdditionalSearchTest.kt`.
- `app/src/test/kotlin/io/github/puflik/plinth/online/Yu4gGatedSearchInTest.kt`.

**Не трогать:** всё прочее, в частности старые тесты и sharedTest fixtures, `OnlineRepository`, `OnlineProviderIds`, Gated/Core repositories, core, MainActivity, PlinthNavHost, OnlineSongs, TrackActions, очередь, зависимости, AGENTS/.codex, CHANGELOG/tasks/decisions, plan. Защищённые тесты прошлых задач также сохраняют защиту. Никаких копий сборок/target основного repo.

## Миграция старых тестов — разрешена и выполнена до исполнителя

Координатор разрешил отдельную точную миграцию перед исполнителем 2026-11-04. Менять production ради вызова старого mock нельзя. Все семь защищённых файлов выше получают общий новый опорный hash. Изменения и сохранённые утверждения:

- `FakeOnlineRepository`: общий search сохранил `searches`, single-provider search получил `providerSearches: List<Pair<provider, query>>`; оба используют прежний ответ/gate/problem. Unknown/disabled/blank не вызывают общий поиск и не учитываются как запрос поддерживаемого provider. Общий `OnlineRepositoryContractTest` не изменён и исполняется через FakeOnlineRepositoryTest.
- `TestYoutubeOnline`: searchIn(YT, query) отдаёт прежний TRACK/ALBUM или NO_NETWORK, иной ID — null; прежние search/album/add/resolve fixtures и assertions yu5a сохранены.
- `SearchViewModelCancelTest`, все три теста: new query отменяет оба provider до новых вызовов; уход 4000+1500 ms отменяет оба только после grace; возвращение через 4000 ms не отменяет и не дублирует ни один. Строковые счётчики заменены provider/query pairs, порядок старого→нового query сохранён. SilentOnline наблюдает searchIn; старый aggregate path использует те же cancellable gates для компилируемого RED baseline.
- `SearchViewModelRetryTest`: `retry asks...`, `coming back...`, `an answer with a problem...`, `another text...` меняют только счётчик на archive/query pairs и добавляют отсутствие aggregate вызовов; сохраняют повтор с тем же текстом, NO_NETWORK→успех, первый кадр со всей библиотекой, кеш ошибки до ручного retry, новый query после возврата. `retry shows...` проверяет Found.loading={IA} (YT у этого fake сразу null) вместо Searching, затем успешный ответ. `retry with no text...` сохраняет оба None и дополнительно проверяет пустоту providerSearches.
- `SearchViewModelTest`: `the archive is asked...` сохраняет границу debounce и точное название альбома, проверяет Found.loading={IA} и один provider/query вызов без aggregate; `switched off...` дополнительно проверяет providerSearches пуст; `a cleared query...` сохраняет немедленный None и ровно один provider/query без aggregate. Все четыре библиотечных/PLAY-теста и NO_NETWORK с ответом библиотеки не менялись.

Миграция проверяет новый транспорт, не допускает совместимость через лишний общий запрос. `assertYu5fLoading` и retry helper используют только reflection точных новых getLoading()/onRetryOnline(String), чтобы весь RED baseline компилировался на старом API; отсутствие API падает assertion. Утверждения не заменены допускающими старое/новое поведение вариантами. Исполнитель не меняет эти файлы; вопрос о неверном тесте возвращается координатору.

## Тесты и подтверждённый baseline

JVM `Yu5fProgressiveSearchTest`: fake общий поиск повторяет последовательную агрегацию Registry, searchIn каждого провайдера ждёт отдельный CompletableDeferred; используются настоящие SearchViewModel.uiState/onQuery и virtual clock.

| Сценарий | Ожидание | main baseline |
|---|---|---|
| both providers start before either has replied | IA и YT начаты до любого ответа | FAIL: начат только IA |
| youtube is visible while archive is still waiting | YT опубликован при незавершённом IA | FAIL: sections пусты |
| archive is visible while youtube is still waiting | IA опубликован при незавершённом YT | FAIL: sections пусты |
| reverse completion keeps archive before youtube | после обоих ответов IA, YT | PASS |
| one provider problem is visible without waiting for its sibling | ошибка YT видна до IA | FAIL: sections пусты |
| a null section does not erase the other provider | отсутствующий IA не убирает YT | PASS |
| new query cancels every pending provider and never shows the old answer | отменены IA/YT, только новые строки | FAIL: отменён лишь IA |
| clearing text cancels both providers immediately | отменены оба, None без debounce | FAIL: отменён лишь IA |
| sources off cancels both providers and clears the screen | отменены оба, None | FAIL: отменён лишь IA |
| leaving beyond grace cancels every pending provider | после 5001 мс отменены оба | FAIL: отменён лишь IA |
| return keeps a completed sibling and restarts only the cancelled provider | YT сохранён, IA повторён, YT не повторён | FAIL: нет завершённого YT |
| return shows the completed answer from the first frame without asking again | единственный прежний snapshot | PASS |

Свежий runtime baseline 2026-11-04: **12 выполнены, 9 assertion failures, 3 PASS; compilation успешна**. Команда ниже с `--rerun` у test task. Весь запуск 3m59s: 15 tasks executed / 30 from cache, сам test task не FROM-CACHE. Лог вне git `C:\claude\Plinth-yu5f-baseline.log`, XML `app/build/test-results/testGithubDebugUnitTest/TEST-io.github.puflik.plinth.ui.search.Yu5fProgressiveSearchTest.xml`. После baseline изменены только формат reply fixture и удалён избыточный suspend helper; повторный runtime не исполнен из-за style stop, это не заявлено как новый baseline.

UI `Yu5fSearchSectionsScreenTest`: четыре сценария — в обзоре отсутствует шестая строка каждого provider; Show all раскрывает только выбранный ответ без сети; retry YT не повторяет IA; первый/второй возврат из fake player сохраняет видимую песню/offset при добавлении места под mini-player. **Компиляция подтверждена, выполнение и RED не проверены**: телефон/эмулятор не трогали. У тестов нет доказанного baseline падения на историческом сдвиге. Первые три ожидаются RED на main, четвёртый — guard и нуждается в проверке воспроизведения.

Эти числа относятся к историческому baseline планировщика. После ревью suite содержит **23 JVM + 6 UI**: добавлены disabled/reenable, независимые retries и duplicate taps, exception/BROKEN, null terminal/cache, invalid provider, whitespace cache, полные 20 строк и сохранение/сброс раскрытия. Старые 12 сценариев не удалены. Первый кадр теперь собирается `UnconfinedTestDispatcher`, без пропуска промежуточных emissions. UI show() ждёт обе завершённые секции, потому что новый Found уже существует во время loading. Новые API проверяются reflection assertions; компиляция старого production должна оставаться успешной. Фактические результаты нового запуска — в ревью ниже.

### Проверки планировщика и правило остановки

- Первый sandbox Gradle вызов не получил запись в `C:\.gradle\wrapper\...zip.lck`; единственная коррекция среды — разрешённый escalated запуск того же узкого теста, runtime выше подтверждён.
- `:app:compileGithubDebugAndroidTestKotlin :app:ktlintCheck`: compile успешна; ktlint нашёл chain wrapping новых UI тестов. Поправлены только они.
- Повтор `compile... ktlintCheck test... --rerun --tests ...`: UI compile успешна, затем **ktlintTestSourceSetCheck FAILED** на JVM chains/длине строк, до runtime. Предыдущая UI проверка успела остановить общий task до этих сообщений. По правилу одной попытки дальнейшие правки/сборки остановлены, production не начинали.
- Остаточные места в новом JVM тесте (номера после текущих изменений): 82, 94, 150, 237; только формат chains/перенос выражения, отчёт `app/build/reports/ktlint/ktlintTestSourceSetCheck/ktlintTestSourceSetCheck.txt`. Их исправление требует отдельного согласованного коррекционного шага планировщика и нового опорного hash; исполнитель protected file не правит.
- Логи дополнительных проверок: `C:\claude\Plinth-yu5f-test-checks.log`, `C:\claude\Plinth-yu5f-final-checks.log`. Detekt, полный unit suite, fdroid, lint и CI не запускались. Предупреждение createComposeRule deprecated — также в старых UI тестах; зависимости не менялись.

## Проверки исполнителя после снятия блокеров

PowerShell, из корня ворктри; каждый вывод сохранять в лог, читать после завершения; ни одну сборку не обрывать через Select-Object. Target core отдельный в ворктри, без CARGO_TARGET_DIR основного repo.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
.\gradlew.bat :app:testGithubDebugUnitTest --rerun --tests 'io.github.puflik.plinth.ui.search.Yu5fProgressiveSearchTest' --console=plain *> 'C:\claude\Plinth-yu5f-targeted.log'
.\gradlew.bat :app:ktlintCheck :app:detekt :app:lintGithubDebug :app:testGithubDebugUnitTest :app:testFdroidDebugUnitTest --console=plain *> 'C:\claude\Plinth-yu5f-checks.log'
.\gradlew.bat :app:compileGithubDebugAndroidTestKotlin --console=plain *> 'C:\claude\Plinth-yu5f-ui-compile.log'
```

После целевых зелёных — общий набор, не перезапускать его без новых изменений/проблем. Windows DataStore исторически падают: конкретное падение сравнивать с исходной версией/CI, не глушить. Известные предупреждения не считать выполнением UI.

Инструментальный запуск только с конкретным serial **эмулятора**, выбранным из `adb devices` и проверенным `adb -s <serial> shell getprop ro.kernel.qemu` = 1; подставить его в ту же PowerShell сессию. Вызов ниже не запускать с буквально указанным placeholder и не выбирать телефон автоматически:

```powershell
$env:ANDROID_SERIAL = '<проверенный-serial-эмулятора>'
.\gradlew.bat :app:connectedGithubDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=io.github.puflik.plinth.ui.search.Yu5fSearchSectionsScreenTest' --console=plain *> 'C:\claude\Plinth-yu5f-ui-run.log'
```

Дополнительно выполнить старые SearchFieldTest/Yu5aSearchSongsScreenTest на том же эмуляторе после принятой миграции fixture. Live: оба включены, IA задержан — YT виден до IA, обратный случай, ошибка/retry только одной секции, пустая секция, обе по пять, раскрытие и назад из альбома/плеера, длинный список в середине и конце, первый и второй возврат с mini-player, новый query и выключение источников. Для скорости: засечь от одного onQuery на реальном SearchViewModel до появления первой и второй секции, рядом main; детерминированные gates подтверждают причинность, не обещают миллисекунды живой сети.

Защиту проверять относительно опорного коммита:

```bash
test_commit='b52b256eae8486e6fb92c7094039460740755b25'  # VM-B1 test candidate, независимая приёмка ещё впереди
git diff --exit-code "$test_commit" HEAD -- \
  app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5fProgressiveSearchTest.kt \
  app/src/androidTest/kotlin/io/github/puflik/plinth/ui/search/Yu5fSearchSectionsScreenTest.kt \
  app/src/test/kotlin/io/github/puflik/plinth/ui/search/SearchViewModelCancelTest.kt \
  app/src/test/kotlin/io/github/puflik/plinth/ui/search/SearchViewModelRetryTest.kt \
  app/src/test/kotlin/io/github/puflik/plinth/ui/search/SearchViewModelTest.kt \
  app/src/sharedTest/kotlin/io/github/puflik/plinth/online/FakeOnlineRepository.kt \
  app/src/sharedTest/kotlin/io/github/puflik/plinth/online/TestYoutubeOnline.kt \
  app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5aSearchSongsTest.kt \
  app/src/androidTest/kotlin/io/github/puflik/plinth/ui/search/SearchFieldTest.kt \
  app/src/androidTest/kotlin/io/github/puflik/plinth/ui/search/Yu5aSearchSongsScreenTest.kt \
  app/src/sharedTest/kotlin/io/github/puflik/plinth/online/FakeOnlineReadiness.kt \
  app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5fReadinessSearchTest.kt \
  app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5fAdditionalSearchTest.kt \
  app/src/test/kotlin/io/github/puflik/plinth/online/Yu4gGatedSearchInTest.kt
git diff --check
```

Команда содержит ровно 14 authoritative protected paths, включая новый B1 и явно мигрированный Yu4g. До независимой приёмки candidate не является baseline. После подстановки проверяемого candidate первый diff обязан быть пуст. Если защищённые тесты изменены планировщиком до реализации по принятому решению, новый hash явно заменить в шапке и независимо проверить миграцию, не сравнивать со старым случайным hash.

## Готово, когда

- Закрыт открытый вопрос readiness ниже; план и опорные тесты независимо прочитаны Astra `xhigh` до реализации. Для пользовательских данных здесь max не нужен: данные не затрагиваются.
- Секции приходят независимо, фиксированный порядок, собственные loading/error/retry; все 23 JVM теста зелёные; при передаче из baseline ни одно утверждение не ослаблено.
- Реальная UI проверка подтверждает пять/Show all и сохранение top anchor после первого и второго возврата. Причина сдвига и до/после факты записаны, не выдана гипотеза за исправление.
- Старые поисковые/song тесты и полный выбранный набор зелёные после отдельной принятой миграции. CI work/yu5f зелёный перед слиянием (сам planner push ожидаемо RED до реализации).
- Изменены только разрешённые файлы; protected diff пуст; ни зависимости, ни журнал/ядро не менялись. Исполнитель коммитит/пушит только свою ветку, не main, не rebase/merge.

## Остановиться и написать, если

- Не решён порядок повторного применения OnlineSwitch и searchIn; расширять production-список без координатора нельзя.
- Для исправления scroll нужны MainActivity/NavHost/другие неразрешённые файлы либо новая продуктовая семантика.
- Тест кажется неверным; не исправлять/игнорировать/ослаблять, задать точный вопрос.
- Проверка упала второй раз после одной коррекции; отчёт, коммит и обычный push, без дальнейших попыток.
- Недоступна прямая сеть или push отклонён — не использовать proxy/force и не обходить отклонение.

## Исторический статус передачи 2026-11-04

**BLOCKED BY yu5f-ready; исполнителя yu5f не запускать.** Исторические три блокера закрыты: inline принят, миграция сохранённых assertions выполнена, scoped ktlint зелёный после отдельной разрешённой коррекции. Новый baseline компилируется и имеет ожидаемые assertion failures на неизменённом production. Координатор принял отдельный prerequisite yu5f-ready; после его плана/тестов/реализации и ревью потребуется уточнить подключение readiness в SearchViewModel и новый опорный hash, если изменятся защищённые тесты. Текущий этап завершён, production не запускался.

**Планировщик:** фактически Sol `gpt-6.1-sol`, `xhigh`; без вложенных агентов. Производственные файлы и прежние protected tests не менял. Предлагаемый исполнитель после утверждения — Sol `high` для конкурентной VM-логики; проверка экрана Terra `high` отдельным последовательным этапом, независимый reviewer Astra `xhigh`. Фактические назначения следующих этапов пока отсутствуют; не считать их выполненными.

## Отчёт исполнителя

### Ограниченный этап VM — 2026-11-15, завершён

Фактическая модель/effort: `gpt-6.1-sol`, `high` (назначение координатора).
Прочитаны принятый контракт независимых секций и обязательного readiness,
правила worktree/защищённых тестов и Linux-проверок. Исходный VM: 252 строки,
на входе — общий `online.search`, четыре аргумента конструктора.
Работа только в SearchViewModel.kt и этом отчёте; UI/scroll остаются Terra.
Граф в этом worktree отсутствует; точные API проверяются по исходникам.
Первый свежий `:app:testGithubDebugUnitTest --rerun --tests
'io.github.puflik.plinth.ui.search.*' -x :app:cargoNdkBuild` — PASS,
`BUILD SUCCESSFUL in 2m 40s`, 45 задач: 13 executed, 2 cache, 30 up-to-date;
сам test task исполнен свежо. Лог `/tmp/yu5f-vm-jvm-20261115.log`.
Реализованы отдельные attempts, нормализованный readiness, Superseded/ABA,
терминальные problem/null, сохранение запущенных соседей и completed cache,
retry с проверкой membership и отменяемый после grace scope подписки.
После проверки уточнён общий retry для всех активных секций и форматирование;
итоговый свежий suite PASS: 7 suites, 78 tests, 0 failures,
18 секунд; 45 задач: 10 executed, 35 up-to-date.
Лог `/tmp/yu5f-vm-jvm-final-20261115.log`.
Первый полный проход: Android test-компиляция PASS; detekt отклонил
complexity reconcile 17/14, ktlint — переносы условия и параметров retry.
Лог `/tmp/yu5f-vm-checks-20261115.log`. Применена единственная коррекция:
вычисление activeProviders/фазы waiting вынесено в свойства контекста/попытки,
параметры retry и accessor waiting отформатированы.
Повтор после единственной коррекции — PASS:

- Свежий search JVM suite: 7 suites, 78 tests, 0 failures/errors;
  `BUILD SUCCESSFUL in 1m 8s`, 45 задач: 11 executed, 34 up-to-date.
  Лог `/tmp/yu5f-vm-jvm-repair-20261115.log`.
- Полные `ktlintCheck detekt :app:compileGithubDebugAndroidTestKotlin`
  с единственным исключением `:app:cargoNdkBuild` — PASS, 33 секунды;
  66 задач: 6 executed, 60 up-to-date. Android test compile исполнена,
  `uniffiKotlin` не исключалась. Лог `/tmp/yu5f-vm-checks-repair-20261115.log`.
- `git diff --check` — PASS; точный список изменений: только VM и этот отчёт.
  Подавления, ignore и ослабления тестов не добавлялись.
- Коммит этапа: `fix(search): load provider sections independently after readiness`
  в `work/yu5f`: `dc2307edebc072dbc554f934e332d910c9b07734`.
  Push отклонён automatic approval review: публикация private source в
  неподтверждённый remote; reviewer не увидел точного разрешения destination/branch.
  Read-only проверка подтвердила неизменённый origin
  `https://github.com/Puflik/Plinth.git`, upstream `refs/heads/work/yu5f`
  и исходный remote HEAD `4257a74`. Координатор уведомлён; обхода отказа нет.
  Затем координатор независимо проверил origin, tracking, fast-forward и состав
  diff; ранее в этом чате уже разрешён и выполнен push `4257a74` в ту же ветку.
  Повторная автоматическая проверка разрешила координатору прямой push в
  `https://github.com/Puflik/Plinth.git`, `refs/heads/work/yu5f`; публикация
  `4257a74..ffda2ef` завершилась успешно. Блокировки публикации больше нет.
  main/merge/rebase/force/release не выполнялись.

Живая UI/scroll-приёмка, полный CI и независимое Astra-ревью production
здесь не выполнялись и остаются следующими этапами. NDK, эмулятор и телефон
не использовались. Вся задача yu5f завершённой не объявляется.
Все 12 защищённых путей имеют пустой diff от принятого baseline.

## Ревью

### План до реализации, 2026-11-04 — этап завершён

Фактически `gpt-6-astra`, `xhigh`, роль `plinth_researcher`. Координатор разрешил этот проход при 6% пятичасового / 16% недельного окна; вложенных агентов нет. Production не меняется. Прочитаны инструкции проекта и ограниченные диапазоны задачи/исходников. Повторный graphify query подтвердил `ModuleNotFoundError`; граф не перестраивается.

Координатор принял inline-раскрытие выбранной секции до уже полученных 20 результатов, без нового route/запроса/«Свернуть». Отдельно разрешены миграция старых mocks/state assertions с сохранением инвариантов и новая коррекция ktlint защищённых тестов до исполнителя; прежняя остановка планировщика остаётся историческим фактом.

Проверены по коду и тестам: отмена и retry каждого provider, cache/null/disabled providers, первый snapshot после возврата, достаточность UI guards и ограничения LazyColumn. Результат — подготовленные защищённые тесты и отдельный блокирующий prerequisite; это не ревью готовой реализации.

### Находки и инварианты

1. Старые SilentOnline/search и aggregate counters скрывали бы фактические provider вызовы либо делали корректную новую VM красной. Подготовка ограничена семью защищёнными test/fixture файлами; список изменённых тестов и сохранённых assertions выше. Старые библиотеки/PLAY и ю5а song assertions не ослаблялись.
2. В исходных 12 тестах отсутствовали проверки loading, provider retry, duplicate taps и disabledProviders. Добавлены 11 целевых guards; в исходных тестах усилены точные loading sets и запрет aggregate вызова. `null` явно завершает попытку и кешируется; неверный response.provider даёт BROKEN для запрошенного ID.
3. План раньше одновременно требовал сохранения соседа и полной инвалидации группы при active-set change. Уточнена согласованная политика: removed отменяется/забывается, retained сохраняется, added ищется заново. Cache не должен содержать отключённые ID; отмена по grace не превращает pending в terminal null.
4. UI ожидание только `is Found` стало бы преждевременным после добавления loading; исправлено на две готовые секции. Guard исторического сдвига пока не воспроизводился и не является доказательством дефекта. Оставлены обязательные живые key/index/offset и viewport проверки; мини-плеер/навигация вне разрешённых файлов.
5. Путь provider сохраняет deadline/health/cancel: `CoreOnlineRepository.kt:58–82` создаёт отдельный OnlineCancel и возвращает отмену ожидающему; `core/ffi/src/api/online_api.rs:221–234` и `core/providers/src/registry.rs:87–115` проходят через тот же ask. Production/core API не менялись.

### Открытый вопрос: порядок переключения и поиска

Источник риска подтверждён кодом: `OnlineSwitch.kt:21–25` отдельно collect настроек вызывает suspend setEnabled; `CoreOnlineRepository.kt:39–47` применяет connect/disconnect через IO; `GatedOnlineRepository.kt:35–46,54–60` ждёт **только первого** применения и не имеет revision/ack для последующих. VM подписана на те же настройки, но не на завершение их применения.

Следствие по трассировке (детерминированная runtime репродукция этого interleaving пока не добавлена): после выключения/повторного включения provider новый searchIn может попасть в старый registry, получить null и закешировать его как terminal для нового active set. Ни законченный connect, ни отсутствие retry-кнопки у null не заставят запрос повториться. Риск повторного master enable частично предшествует yu5f; автоматическое обновление provider set в новом плане делает порядок существенным. Нельзя лечить произвольной задержкой, бесконечным retry null либо вторым setEnabled из VM: это меняет cache/ownership/health.

Координатор принял выделение **yu5f-ready** отдельным prerequisite и разрешил ограниченное планирование OnlineSwitch/GatedOnlineRepository/OnlineRepository/DI и отдельного readiness testfile. В этом проходе они не изменяются; runtime этого interleaving ещё не проверен. Data reviewer не нужен для этой настройки сети; каталог/CRDT/перепривязка не затрагиваются.

### Передача будущему планировщику yu5f-ready

Цель отдельного шага: поиск получает явное подтверждение, что нужный набор уже применён, а выключение отменяет удалённого provider немедленно. Включение/повтор запускает searchIn только после подтверждения; отсутствие подтверждения не маскируется terminal null. Рекомендуемый минимальный API для согласования и фиксации в отдельном файле задачи:

```kotlin
data class OnlineConfiguration(
    val enabled: Boolean,
    val disabledProviders: Set<String>,
)

sealed interface OnlineReady {
    data class Applied(val revision: Long) : OnlineReady
    data class Failed(val problem: OnlineProblem) : OnlineReady
    data object TimedOut : OnlineReady
    data object Superseded : OnlineReady
}

// OnlineRepository: новый suspend-барьер, не запускающий setEnabled/search.
suspend fun awaitConfiguration(configuration: OnlineConfiguration): OnlineReady
```

Реализация/владение: singleton GatedOnlineRepository хранит состояние последней **попытки применения**, desired config и monotonic revision; pending публикуется перед вызовом inner.setEnabled, Applied — только после успешного возврата той же попытки. OnlineSwitch остаётся единственным production-владельцем повторных setEnabled и последовательно применяет настройки; ошибки не убивают его collect. Caller ждёт совпадающую актуальную configuration/revision; старое Applied не разрешает поиск через новое Pending/Failed. Конфигурацию нормализовать одинаково с OnlineSwitch (при enabled=false disabledProviders пуст); неизвестные ID не требуют повторной конфигурации. Конкретные типы внутренних state и значения времени выбирает планировщик, без нового публичного Flow из мутабельной реализации.

Семантика ожидания: cancellation всегда пробрасывается и не отменяет application-scoped применение; query/active change отменяет только ожидание старого запроса. Ожидание ограничено существующим waitLimit (по умолчанию 60s; тестовый срок инъецируется); TimedOut/Failed не пропускают searchIn в старый registry и не записываются как null-секция. Superseded не становится ошибкой провайдера: актуальная VM-конфигурация запускает своё ожидание. Ошибка применения отображается через явный provider problem, без автоматического бесконечного переприменения. Повторная конфигурация восстанавливает работоспособность; manual section retry повторно проверяет acknowledgement и сам не вызывает setEnabled. Если нужен отдельный retry самой конфигурации, планировщик возвращает это продуктово/API решение координатору.

До появления запрошенной configuration в lifecycle применения прежний несовпадающий ack означает ожидание, не немедленный Superseded. Superseded допустим после наблюдения нужного pending/revision и его замены более новым desired. Планировщик обязан отдельно решить совместимость нового метода с прямыми Core/Fake implementations: не добавлять безусловный production default Applied, обходящий барьер. При необходимости отделить capability в интерфейс с явным DI binding к тому же Gated singleton; окончательная сигнатура и затронутые тестовые constructors фиксируются **до кода** в yu5f-ready.

Интеграция yu5f после prerequisite: None/removed provider и cancel происходят сразу при смене settings, до ожидания ack; pending UI остаётся только для актуальных активных ID. Existing sibling requests/results не сбрасываются; added/retried provider ждёт нужную конфигурацию перед searchIn. Проверка revision/attempt остаётся перед публикацией результата. `stream`/CountDownLatch, прежнее первоначальное gate поведение search/album/radio и timeout fallback не меняются этим новым suspend-барьером; блокирующий поток не обращается к coroutine StateFlow. Физическое переключение registry после acknowledgement не заменяет cancellation/generation checks: settings могут снова измениться.

**Дополнительная подтверждённая преграда truthful ack:** `CoreOnlineRepository.setEnabled` игнорирует nullable результат `attempt`; `library/CoreRows.kt:39–57` ловит CoreFailure, логирует и возвращает null. Следовательно обычный возврат setEnabled пока не доказывает успешный connect/disconnect. Для yu5f-ready требуется отдельное точечное разрешение **CoreOnlineRepository.kt:setEnabled**: передавать ошибку применения наружу (сигнатура Unit может сохраниться с явно документированным исключением), чтобы Gated не выдал ложный Applied. Общие `quietly/attempt` не менять, другие операции каталога/журнала не затрагивать. Уточнение error mapping и contract tests — часть отдельного плана, не реализация этого ревью.

Предлагаемое владение файлами yu5f-ready (не разрешение исполнителю yu5f): OnlineRepository.kt (тип/API/договор ошибки), GatedOnlineRepository.kt (ack/barrier, прежний thread gate сохранён), OnlineSwitch.kt (failure/cancellation и продолжение collect), `di/OnlineModule.kt` только если wiring требует; **CoreOnlineRepository.kt только setEnabled — запрашиваемое расширение**. Новый отдельный `app/src/test/kotlin/io/github/puflik/plinth/online/Yu5fOnlineReadinessTest.kt`; существующие OnlineSwitch/Gated/Core cancel/contract tests защищены, их список/hash фиксирует планировщик. Новые типы можно расположить в OnlineRepository.kt без расширения списка. Файл будущей задачи `docs/work/yu5f-ready.md` пишет планировщик. SearchViewModel остаётся владельцу yu5f после prerequisite; никакие CRDT/FFI/Rust или зависимости не нужны.

Детерминированный регрессионный сценарий для нового теста: реальный GatedOnlineRepository + OnlineSwitch, controlled inner repository и virtual clock. Сначала успешно применить enabled=true, disabled={IA}; открыть первый gate. Затем desired={оба}, остановить второе setEnabled перед обновлением inner registry. В это время вызвать awaitConfiguration(оба) и searchIn только после Applied: inner.searchInCalls должен быть пуст, ожидание ещё активно. Отпустить setEnabled, получить Applied и ровно один IA поиск/ответ; null из старого registry не появляется. На текущем API тест компилируется через точный reflection bridge или падает компиляцией только по заранее записанной новой сигнатуре. Отдельно покрыть: failure при втором применении не даёт Applied и не убивает следующий apply; timeout не запускает search; отмена wait не отменяет apply; более новая конфигурация не получает старую revision; выключение отменяет pending VM сразу; существующий первый stream gate/успех/ошибка/timeout неизменны. До этого теста runtime-воспроизведение гонки не заявлять.

Это ограниченная предпосылка, но не укладывается в добавочную косметическую правку текущего плана: truthful ack затрагивает ещё один production-контракт и требует отдельного RED baseline/защищённых тестов. Координатор принял отделение; дальнейшее исследование здесь остановлено.

### Проверки этого прохода

Первый запуск остановился до compile/test: временный Groovy init фильтр стиля ошибочно вызвал filter у AppliedPlugin. Это ошибка запуска reviewer, не production. Использована одна разрешённая коррекция: явный ktlint extension + форматирование только семи перечисленных тестовых файлов. Затем **scoped ktlintCheck зелёный**, compileGithubDebugAndroidTestKotlin и compileGithubDebugUnitTestKotlin исполнены успешно, testGithubDebugUnitTest исполнен свежим (`--rerun`, ui.search.* и FakeOnlineRepositoryTest). Логи вне Git: `C:\claude\Plinth-yu5f-review-{checks,format,final}.log`. Повторных инфраструктурных/style/compile сбоев нет; ожидаемые RED assertions на старом production — результат baseline, не повод менять production.

| Suite | Выполнено | PASS | Assertion failures | Errors |
|---|---:|---:|---:|---:|
| Yu5fProgressiveSearchTest | 23 | 4 | 19 | 0 |
| SearchViewModelCancelTest | 3 | 0 | 3 | 0 |
| SearchViewModelRetryTest | 6 | 1 | 5 | 0 |
| SearchViewModelTest | 8 | 6 | 2 | 0 |
| Yu5aSearchSongsTest (не изменялся) | 15 | 15 | 0 | 0 |
| FakeOnlineRepositoryTest, исходный общий контракт | 19 | 19 | 0 | 0 |
| **Всего** | **74** | **45** | **29** | **0** |

Все 29 падений — Truth assertions (27 AssertionErrorWithFacts, 2 ComparisonFailureWithFacts), не compile/инфраструктура/исключения fixture. Это доказывает RED на прежнем production и сохранение контракта fake/song baseline; не доказывает, что будущая реализация проходит guards. Финальный запуск 27s, 72 tasks: 11 executed / 61 UP-TO-DATE, FROM-CACHE нет; целевые lint source tasks, обе Kotlin compile и test task реально исполнялись. UI runtime, исторический сдвиг, live providers, fdroid/full suite/detekt/lint/CI не проверялись; устройства не использовались. Git diff --check зелёный, production diff пуст; новый baseline включает ровно семь защищённых test/fixture файлов плюс задачу.

Push в этом проходе запрещён последующим сообщением координатора: он собирает локальные изменения для конкретного финального разрешения публикации. Push не предпринимался; отказ авто-review в этом агенте отсутствует. Основной repo/ветка main не менялись.

Локальный baseline-коммит `cbe6934fcd82f203622a490031e9a0e054272fee`, обе даты 2026-11-04 (+05:00). Первый git add упёрся в sandbox Permission denied для worktree index.lock; разрешённый escalated локальный add/commit завершился успешно. Защищённые файлы после baseline не менялись; следующий commit только закрепляет hash в этой задаче. Фоновых сборок/ожиданий не оставлено: Gradle запускался --no-daemon, сессия проверки завершилась.


## Интеграция принятого OnlineReadiness — решение 2026-11-13

Исследователь фактически: **`gpt-6-astra`, `xhigh`**. Переданный координатором prerequisite `yu5f-ready` принят (CI `43258909999` SUCCESS), main `419ae48` внесён обычным merge в `work/yu5f`, HEAD `257546c`; опубликованная история сохранена. Worktree теперь `/home/puflik/src/Plinth-yu5f`. Проверено: семь исходных protected paths не отличаются от `cbe6934`. Это решение уточняет прежний открытый вопрос; независимые секции, пять строк, inline Show all и scroll остаются принятыми. Следующий этап — **Sol `xhigh`, план и guards**, не реализация.

### Обязательный constructor и источник readiness

Точная новая сигнатура, на которую планировщик вправе получить compile RED:

```kotlin
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        library: LibraryRepository,
        private val online: OnlineRepository,
        onlineSettings: OnlineSettings,
        private val actions: TrackActions,
        private val readiness: OnlineReadiness,
    ) : ViewModel()
```

Добавляется обязательный последний параметр без default и без secondary constructor. Запрещены production `Applied`-заглушка, cast `online as? OnlineReadiness` с fallback и обход барьера для fake. Готовый `OnlineModule` уже выдаёт `OnlineRepository` и `OnlineReadiness` из **одного singleton GatedOnlineRepository**; DI/OnlineSwitch/Gated/Core/API не менять. VM только вызывает `awaitConfiguration`; `setEnabled` принадлежит OnlineSwitch.

VM строит `OnlineConfiguration(enabled, disabledProviders).normalized()` тем же существующим helper из `online`; сравнивает нормализованные значения. Неизвестный ID не создаёт нового ожидания/поиска. `enabled=false`, пустой query или отсутствие активных известных ID дают None и немедленную отмену, без ожидания acknowledgement. Смена settings не проходит через query debounce.

### Граница readiness и начатого поиска

На текущий query у каждого provider различать **ожидает readiness → searchIn начат → завершён**; завершение включает problem и допустимый `null` **только от searchIn**. Это внутреннее состояние, публичный UI остаётся Found(sections, loading). Ожидание барьера и поиск оба входят в loading.

1. Новый/добавленный provider, ручной retry и возобновлённый после grace незавершённый provider сначала ждут acknowledgement **всей текущей нормализованной configuration**, а не только своего enabled-флага. До Applied ни одного searchIn. Подписка владеет job ожидания так же, как job поиска.
2. При изменении configuration сначала согласовать membership: removed отменить/забыть и инвалидировать attempt, master-off/пустой query отменяют всю группу. Это выполняется до suspend-барьера, не ждёт его исхода. Added создаёт новую попытку.
3. **Retained, который ещё ждёт readiness**, отменяет только старое ожидание и начинает новое для актуального набора; loading не исчезает. Инвалидируется token его wait/attempt. Старый Applied/Failed/TimedOut/Superseded после отмены игнорируется. Поэтому ожидание `{IA,YT}` не оставляет IA зависшим, когда актуальный набор уже `{IA}`.
4. **Retained с уже начатым searchIn либо завершённым ответом** сохраняет job/ответ/attempt; изменение соседа не отменяет его и не запускает повторный searchIn. Это касается завершённых problem и null тоже. Новая configuration сама по себе не повторяет ошибки retained provider; остаётся ручной retry.
5. Query generation меняется при новом settled query/полном сбросе, provider attempt — при его отмене/retry/пересоздании; отдельный token нормализованной configuration используется **только до старта searchIn**. Applied разрешает старт лишь после сериализованной проверки query generation, provider attempt, актуальных configuration/token и membership. Между проверкой и пометкой/вызовом searchIn не добавлять suspension. При публикации уже начатого retained поиска проверять query/provider attempt/membership, **не требовать прежней configuration/revision**: её смена из-за соседа не делает ответ устаревшим. `Applied.revision` — подтверждение применения, не глобальный cache key и не повод инвалидировать всех соседей.
6. Все переходы и snapshot остаются сериализованы в Main. `WhileSubscribed(5_000)` сохраняется: до grace нет повторов; после grace отменяются только незавершённые jobs, их память не становится terminal null. При возвращении контекст сверяется до использования cache; завершённое сохраняется, незавершённое снова начинает с readiness. Старый finally не снимает loading новой попытки. Постоянной внутренней подписки/settings job вне lifecycle не добавлять.

### Исходы барьера и Superseded/ABA

| Исход текущего wait | Действие VM |
|---|---|
| `Applied(revision)` | После проверок выше ровно один searchIn данного provider. |
| `Failed(problem)` | Завершённая `OnlineSection(provider, emptyList(), problem)`, loading снят; searchIn не вызывается. |
| `TimedOut` | Та же завершённая секция с **`OnlineProblem.PROVIDER_DOWN`**, существующие текст/кнопка retry; без новых enum/строк. |
| `Superseded` | Это смена попытки применения, а не provider error или null: согласовать актуальные settings и заново ждать, если provider ещё находится в readiness-фазе. |

Для Superseded не ждать обязательной следующей emission settings: при **ABA** последнее значение может снова быть A либо промежуточное B склеено Flow, а старый waiter A уже привязан к прежней revision. Тогда новый await нужен и для равного значения A. Каждый актуальный результат Superseded порождает ровно одно новое ожидание, прежний результат не обрабатывается повторно; одновременная смена settings и Superseded не создают два актуальных wait. Не перезапускать соседний searchIn и не сбрасывать loading/cache всей группы.

Это реакция на событие барьера, **не polling**: никаких delay/retry таймеров, ожидания settings через многократный first(), либо циклов по сохранённому Superseded. Готовый Gated (`awaitConfiguration/begin`, строки 76–109) выдаёт Superseded лишь при реальной смене revision после привязки waiter; при стабильном состоянии новый вызов либо suspends до подходящего применения/timeout, либо возвращает Applied/Failed. Повтор возможен лишь после нового Superseded от новой попытки или изменения configuration. Контракт не требует поддерживать некорректный fake, бесконечно возвращающий Superseded без смены попыток. Повторяющийся настоящий churn остаётся отменяемым ожиданием; окончание каждого ожидания ограничивает имеющийся waitLimit. Дополнительный VM timeout/произвольная задержка и новый публичный readiness Flow не нужны.

Failed/TimedOut кешируются как проблема до ручного retry/новой попытки по обычным правилам; повторная подписка их не переигрывает. Ручной retry снова ждёт readiness, не переприменяет настройки; если Gated по-прежнему Failed, снова показывает ту же проблему без поиска. `CancellationException` пробрасывается; остальные Exception сохраняют принятое BROKEN-поведение попытки, Error не ловить. Ни timeout, ни failure, ни supersession, ни отмена не превращаются в успешную пустую/null-секцию.

### Точные разрешённые пути планировщика и миграция

Для следующего этапа **только планировщику**, до исполнителя, разрешены перечисленные ниже изменения; это явное узкое исключение прежней защиты пяти изменяемых тестов. В восьми существующих файлах менять только import нового fixture и единственный вызов: `SearchViewModel(library, online, settings, actions, readiness = FakeOnlineReadiness())` с сохранением существующих четырёх аргументов. Не менять assertions, test names, counters, timing, delegates или поведение прежних fixtures.

| Полный путь от корня worktree | Разрешённое изменение |
|---|---|
| `docs/work/yu5f.md` | Актуальные статусы, план/guards, новый baseline, фактические проверки и отчёт. |
| `app/src/test/kotlin/io/github/puflik/plinth/ui/search/SearchViewModelCancelTest.kt` | Только constructor/import. |
| `app/src/test/kotlin/io/github/puflik/plinth/ui/search/SearchViewModelRetryTest.kt` | Только constructor/import. |
| `app/src/test/kotlin/io/github/puflik/plinth/ui/search/SearchViewModelTest.kt` | Только constructor/import. |
| `app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5aSearchSongsTest.kt` | Только constructor/import. |
| `app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5fProgressiveSearchTest.kt` | Только constructor/import. |
| `app/src/androidTest/kotlin/io/github/puflik/plinth/ui/search/SearchFieldTest.kt` | Только constructor/import. |
| `app/src/androidTest/kotlin/io/github/puflik/plinth/ui/search/Yu5aSearchSongsScreenTest.kt` | Только constructor/import. |
| `app/src/androidTest/kotlin/io/github/puflik/plinth/ui/search/Yu5fSearchSectionsScreenTest.kt` | Только constructor/import. |
| `app/src/sharedTest/kotlin/io/github/puflik/plinth/online/FakeOnlineReadiness.kt` | Новый test-only `class FakeOnlineReadiness : OnlineReadiness`, немедленный `Applied(1L)`; только для прежних тестов. |
| `app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5fReadinessSearchTest.kt` | Новый защищённый файл guards и его private controlled barrier/repository fixtures. |

Итого **5 unit + 3 androidTest constructors**, не 4+4 (исправление итоговой опечатки инвентаризации). Старые `FakeOnlineRepository.kt` и `TestYoutubeOnline.kt` остаются побитово относительно `cbe6934`; им не добавлять readiness/delegation. Новый immediate fixture не попадает в production/DI и не используется в readiness guards.

ControlledReadiness в новом testfile записывает **отдельный объект на каждый вызов**: configuration, deferred outcome и наблюдение отмены. Тест вручную завершает конкретный вызов, не все вызовы равной configuration: это существенно для ABA. Новый await по умолчанию suspends; никакого default Applied/Superseded. Recording repository отдельно фиксирует searchIn/provider/query/отмену и позволяет задержать ответ; `search` и `setEnabled` считаются запрещёнными вызовами VM. Достаточно имеющихся coroutine-test/CompletableDeferred, без зависимостей и изменения production ради теста.

После миграции планировщик фиксирует новый защищённый commit: восемь callers, новый shared fixture и guards, плюс прежние два неизменённых repository fixtures входят в список защиты (12 путей всего). Он показывает diff от `cbe6934`: только constructor/import в пяти прежних защищённых callers, два fixtures неизменны; три дополнительных callers — только тот же plumbing. Новый baseline не стирает историческую опору. Исполнитель не получает права менять ни один из этих 12 файлов. Его production-список выше остаётся прежним; интеграция readiness целиком в разрешённом SearchViewModel.

### Acceptance guards до передачи исполнителю

Новый файл проверяет реальные состояния/calls на virtual scheduler; без reflection обхода нового обязательного constructor, live network и sleep. Эти guards дополняют прежние progressive/lifecycle/UI tests:

1. **Pending → Applied:** после debounce обе активные секции в loading, библиотека доступна; до каждого управляемого Applied соответствующего wait `searchIn` отсутствует. После Applied ровно один вызов на ID; ответ одного появляется при pending соседе. На всех сценариях aggregate `search` и VM `setEnabled` равны нулю.
2. **Failed/TimedOut:** каждый исход снимает только свой loading, сохраняет точный problem/PROVIDER_DOWN и соседа; searchIn данного ID отсутствует, результат не null и не empty-success. Retry создаёт новое readiness ожидание; поиск только после нового Applied. Переподписка не повторяет завершённую проблему.
3. **Retained readiness при смене набора:** оба ждут C0; отключить YT — его wait отменён/ID исчез, ожидание retained IA для C0 тоже отменено и ровно одно новое для C1. Запоздалый Applied C0 ничего не запускает; Applied C1 запускает ровно один IA searchIn. Симметричный случай добавления YT: pending IA переведён на C1, added YT ждёт C1, старый ack не выпускает ни одного.
4. **Superseded/ABA:** waiter A1 → Superseded при актуальном A (без новой settings emission) → ровно один новый pending waiter A2; repeated runCurrent не добавляет calls, loading сохраняется, searchIn отсутствует. Applied только A2 запускает один поиск. Отдельно сменить configuration A→B→A с разными wait objects и доставить поздний A1/старый Superseded: старые outcomes не стартуют запрос и не отменяют A2. Совпадение значений не заменяет attempt identity.
5. **Удаление/master-off во время pending и searchIn:** отмена наблюдается после обработки settings, до release любого ack/ответа и без продвижения timeout; ID исчезает либо None. Позднее завершение игнорируется. Повторное включение того же provider создаёт новую попытку, ожидающую нового acknowledgement; прежний ack не разрешает поиск.
6. **Сохранение соседа:** отдельно finished и in-flight IA при добавлении/удалении YT; IA не отменяется/не повторяется и его запоздавший результат принимается после смены configuration. Меняется только ожидание/поиск YT; не возвращать IA в loading, если он завершён. Аналогично section retry не трогает соседа. Unknown disabled ID не создаёт wait/search/cancel.
7. **Lifecycle readiness:** уход менее grace сохраняет wait; более grace отменяет pending wait/search и не делает terminal null. Возврат повторяет только незавершённых с актуальным configuration; finished/error/null snapshot остаётся. Старые завершения/finally после restart не убирают новый loading. Пустое поле отменяет ожидание немедленно, новый settled query отсекает старые outcomes.

Планировщик записывает RED строго по новой сигнатуре и guards, затем нужные compile/style checks по Linux-памятке; production для получения RED не менять. Независимое Astra-ревью до исполнителя проверяет отсутствие ослаблений и границу pending/started, после реализации — те же guards и прежнюю приёмку UI. Не выдавать compile RED за runtime-воспроизведение readiness-гонок.

Стоп и вопрос координатору, если guards требуют изменить готовый readiness/DI/API, production на этапе планирования, файл вне списка, assertions старых тестов либо принятые lifecycle/UI-инварианты; обход constructor или ослабление guards не допускаются. Повторное падение проверки после одной коррекции — прежний стоп с отчётом.

### Проверки исследования и пределы

Исследование read-only по коду: bounded source/graphify query, constructor inventory, protected diff. Graphify отстаёт от readiness; решения сверены с текущими `OnlineRepository.kt:121–150`, `GatedOnlineRepository.kt:76–109`, `OnlineSwitch.kt:20–31`, `OnlineModule.kt:39–50`, `SearchViewModel.kt:85–95`, `SearchScreen.kt:291–296`. PROVIDER_DOWN соответствует существующему «не отвечает», BROKEN — «ответил непонятно». Сохранность каталога/CRDT/перепривязка/восстановление не затрагиваются, data reviewer не требуется.

В этом проходе production/tests не менялись, build/emulator/commit/push не запускались; отчёт по ходу `/tmp/plinth-yu5f-readiness-design-20261113.md`. По сообщению координатора полные ktlintCheck+detekt текущего `257546c` прошли (1m13s, 30 tasks: 8 executed/22 cache; лог `/tmp/plinth-yu5f-style-baseline-20261113.log`), эти проверки здесь не повторялись. Это baseline до новых guards, а не проверка будущей интеграции. Запись общей decisions/обновление графа остаются координатору в конце волны.

Координатор принял исследовательское решение 2026-11-13. Следующий запуск —
Sol xhigh для перечисленных migrations/guards, затем независимое ревью;
production пока не начинать. Linux-команды брать из актуального LAPTOP,
прежние PowerShell-примеры выше исторические. На момент передачи окна
77%/36%, новый агент не запускается при пороге 65%; фактический сброс
пятичасового окна — 13 ноября 13:17 +05:00, перед запуском сверить заново.


## Подготовка readiness guards — 2026-11-14

Фактический планировщик: **`gpt-6.1-sol`, `xhigh`**; один агент, без вложенных. Вход: `work/yu5f` на `a344eee`, чистый worktree `/home/puflik/src/Plinth-yu5f`; координатор сообщил окна 3%/40%. Применяется принятое Astra-решение 2026-11-13 без нового архитектурного исследования. Данные пользователя не затрагиваются; требование Astra max для data-планов здесь неприменимо. До executor обязателен независимый Astra xhigh review нового baseline.

План: восемь точных constructor/import migrations; новый sharedTest Applied fixture; отдельные реальные VM guards с управляемыми по identity ожиданиями/ответами. Затем штатные полные ktlintCheck/detekt, unit и AndroidTest Kotlin compile с ожидаемым RED только обязательного пятого аргумента. Runtime readiness-гонок при compile RED не заявлять. Production/DI/readiness API/зависимости и общие документы не меняются.

Graphify query выполнен в основном repo (в sibling граф отсутствует); граф отстаёт, готовый OnlineReadiness API и существующие test fixtures сверены с текущими исходниками. Исторический style baseline 13 ноября не повторяется отдельно. Новые controlled fixtures различают каждый вызов даже при равной configuration и допускают адресную позднюю доставку после наблюдаемой отмены.

### Записанные guards и точная миграция

Новый `Yu5fReadinessSearchTest.kt`: 22 реальных VM сценария на virtual scheduler. Проверяются библиотека/два loading до ack; адресный Applied и независимые ответы; Failed и TimedOut→PROVIDER_DOWN с retry/cache; pending retarget при добавлении/удалении; Superseded без новой settings emission, отсутствие polling и ABA с разными objects; немедленная отмена pending/running при master-off/removal, reenable с новым ack; сохранение finished/in-flight/null/error соседей; retry без отмены соседа; неизвестные ID; return до/после grace, актуальная configuration после ухода, старый finally, blank/new query и отсутствие активных IDs. Общий aggregate search и setEnabled проверяются равными нулю после каждого сценария. Constructor вызывается напрямую с обязательным readiness; reflection сохранён лишь для ранее принятого onRetryOnline(String)/Found.loading, без обхода constructor.

Восемь callers мигрированы только import/fifth argument и переносами constructor; исходные четыре аргумента, assertions, names, counters, timing и fixtures не менялись. Обратная подстановка и сравнение токенов с входным HEAD подтвердили все восемь; `FakeOnlineRepository.kt` и `TestYoutubeOnline.kt` побайтово равны `cbe6934`. Новый shared fixture только test-only Applied(1L); controlled barrier в guards его не использует. Исторический diff пяти protected callers от `cbe6934` сохраняется; добавлены три constructor callers и два новых файла, защита теперь 12 путей. Разрешённый scope текущего прохода — ровно 11 путей из принятого решения.

### Команды текущей проверки (Linux)

Во всех Gradle вызовах: `JAVA_HOME=/home/puflik/.local/opt/jdk21`, `ANDROID_HOME=/home/puflik/Android/Sdk`, `ANDROID_SDK_ROOT=/home/puflik/Android/Sdk`, `GRADLE_USER_HOME=/home/puflik/.gradle`, `RUSTUP_TOOLCHAIN=1.98.1`, `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`. Штатный `bash ./gradlew --no-daemon ktlintCheck detekt`; затем `bash ./gradlew --no-daemon --continue :app:compileGithubDebugUnitTestKotlin :app:compileGithubDebugAndroidTestKotlin -x :app:cargoNdkBuild`. `uniffiKotlin` не исключается, scoped init не используется. Исполнитель после реализации повторяет эти compile/style gates и прежние runtime/UI gates из задачи; compile RED не является исполнением JVM/UI guards.

### Проверки по ходу

Первый полный `ktlintCheck detekt` завершился exit 1 за 1m37s: 30 tasks, 5 executed/25 up-to-date. Единственная причина — ktlint method-chain-wrapping в 21 новом выражении readiness guard; detekt не достигнут. Лог `/tmp/plinth-yu5f-readiness-style-20261114.log`. Использована одна разрешённая коррекция: только переносы этих chains, без изменения guards/assertions. Повторный штатный прогон включает `--continue`, чтобы отдельно собрать style/detekt и ожидаемый constructor compile RED; второе неожиданное падение означает стоп без новой коррекции.

Ранний промежуточный вывод о PASS ktlint отозван: `--continue` позже дописал результаты. Повторный полный `ktlintCheck` FAIL (шесть Needless blank line(s)); `detekt` FAIL: единственная находка `Yu5fReadinessSearchTest.kt:46:7`, `LargeClass`. Это второе неожиданное падение после одной коррекции, поэтому дальнейшие code/test правки **остановлены**. Текущий `--continue` завершён ниже; новых build/fix попыток после остановки не запускалось. Лог `/tmp/plinth-yu5f-readiness-gates-20261114.log`; detekt report `app/build/reports/detekt/detekt.xml`.

Минимальный следующий разрешаемый координатором объём: разделить новые guards на два тестовых класса в том же разрешённом `Yu5fReadinessSearchTest.kt`, вынести общий harness/controlled fixtures там же, сохранить все 22 сценария, assertions и timing; не добавлять Suppress/allow и не менять protected старых тестов. Это предложенный отдельный корректирующий этап, здесь не выполнен. Независимое review до исполнителя ещё не состоялось; подготовка не выдаётся за готовый GREEN baseline.

### Итоговый отчёт планировщика — остановка по лимиту коррекций

Фактически **`gpt-6.1-sol`, `xhigh`**, 2026-11-14. Записаны 22 VM guards, test-only immediate readiness fixture и восемь migrations; ровно 11 разрешённых путей. Production/DI/готовый readiness/зависимости/общие документы не менялись. Исходные аргументы/assertions/timing старых callers сохранены, оба repository fixtures побайтово равны историческому `cbe6934`; повторная сверка перед коммитом выполнена. Новый тестовый commit — **кандидат**, не принятый baseline; следующего executor не запускать.

Текущий полный запуск завершился exit 1 за **5m54s**, **68 tasks: 14 executed, 27 FROM-CACHE, 27 UP-TO-DATE**. `uniffiKotlin` выполнен, host Rust build свежий (1m09s); main Kotlin/Java и часть Android resources FROM-CACHE. Оба test Kotlin compile tasks выполнялись свежими и дали ровно **9 ожидаемых ошибок** `No parameter with name 'readiness' found.`: 6 JVM constructors (пять migrations + новый guard), 3 AndroidTest constructors. Других compiler errors нет. Это **compile RED исключительно по принятой пятой обязательной зависимости**, JVM tests/UI tests не исполнялись, runtime readiness-гонок не подтверждался. APK/эмулятор/телефон/NDK/live network не использовались.

Неожиданные gates: `detekt` — одна `LargeClass`; `ktlintTestSourceSetCheck` — шесть `Needless blank line(s)` на строках 94/96/98/111/113/115 нового guard-файла. Эти пустые строки внесены единственной коррекцией chains; все последующие test/code правки остановлены. Полного style PASS нового candidate нет. Падения относятся к новому testfile, инфраструктурного FAIL нет. Логи: `/tmp/plinth-yu5f-readiness-style-20261114.log`, `/tmp/plinth-yu5f-readiness-gates-20261114.log`.

**LargeClass точно:** default `allowedLines: 600`, без проектного override; текущая метрика **620 LOC**. Проверено по установленным detekt 2.0.0-alpha.6 `default-detekt-config.yml` и реализации `LargeClass/LinesOfCode`: 706 строк с токенами outer declaration (включая @OptIn) минус 86 LOC пяти nested declarations (15+8+16+26+21). Физический диапазон outer declaration 45–802 не равен метрике: blank/comment и nested classes исключаются. Поэтому простое перемещение nested fixtures наружу не уменьшает уже вычисляемую метрику.

**Минимальное предложение отдельного разрешённого split:** в том же `Yu5fReadinessSearchTest.kt` разделить существующие guards по границе перед `return within grace keeps the same pending waits` (ныне @Test строка 542): первые 16 readiness/membership/sibling guards и последние 6 lifecycle/query guards. Существующие setup/state, пять helpers и controlled fixtures вынести в общий harness в этом же файле, без новых fixtures semantics; тела всех 22 тестов, assertions, scheduler/timing и cancellation outcomes сохранить. Одновременно убрать только шесть названных пустых строк. Suppress/allow/ignore не добавлять; остальные 10 planner paths и оба repository fixtures не менять. Это предложение, здесь не реализованное; требуется отдельное разрешение координатора, новый candidate hash и независимое Astra xhigh review до production.

Candidate-коммит тестов: `dd33755a463b0e344e24dd79436cc1d3638ff45e` (`test: prepare yu5f readiness guards and constructor RED`); не считается принятым baseline. Следующий commit только закрепляет этот hash в taskfile. Готовых/фоновых Gradle процессов этого прохода не осталось; текущая build session завершилась. Обычный direct push `work/yu5f` выполняется после этой записи, без main/rebase/merge/force.


## Разрешённый корректирующий split — 2026-11-14 (завершён)

Координатор принял stop предыдущего этапа и разрешил отдельную узкую коррекцию candidate `dd33755`/записи `b26d7e6`; перед запуском сообщил проверенные окна 42%/46%. Фактически **`gpt-6.1-sol`, `xhigh`**, прежний агент, без вложенных. Владение строго двумя путями: `app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5fReadinessSearchTest.kt` и `docs/work/yu5f.md`. Остальные planner paths, production, зависимости и правила не меняются; чужие изменения не отменяются.

План до изменений: первые 16 существующих тестов остаются в `Yu5fReadinessSearchTest`; последние 6 начиная с `return within grace keeps the same pending waits` переходят в `Yu5fReadinessLifecycleTest` в **том же файле**. Общее setup/state/helpers/controlled fixtures располагаются в harness в том же файле, отдельный экземпляр состояния на каждый JUnit test, без mutable singleton. Сохраняются все 22 имён, тела, assertions, timing/gates/cancellation outcomes; удаляются только шесть названных лишних blank lines. Выполняется точная обратная сверка с `dd33755` и отдельная сверка fixture semantics. Suppress/Ignore/allow не добавляются. Список 12 protected paths и test source file остаются прежними.

**Обязательный будущий runtime filter обеих групп:** `--tests 'io.github.puflik.plinth.ui.search.Yu5fReadiness*Test'` для `:app:testGithubDebugUnitTest --rerun`; после production это дополняет прежние progressive/UI проверки, без пропуска lifecycle-класса. Сейчас ожидается compile RED обязательного пятого аргумента, runtime не заявляется.

Проверка этого этапа: штатный полный `bash ./gradlew --no-daemon --continue ktlintCheck detekt :app:compileGithubDebugUnitTestKotlin :app:compileGithubDebugAndroidTestKotlin -x :app:cargoNdkBuild` с тем же Linux environment; `uniffiKotlin` не исключается. Статусы объявляются только после завершения всего процесса. При неожиданном FAIL допускается одна ограниченная коррекция; повторный FAIL — stop/отчёт без самостоятельного третьего этапа. Итоговый hash — candidate до независимого Astra review, production не начинать.

Split записан: `Yu5fReadinessSearchTest` (16), `Yu5fReadinessLifecycleTest` (6), общий `Yu5fReadinessHarness` в прежнем test source file. Mutable state (`dispatcher/settings/readiness/online/library/viewModel`) создаётся на экземпляре harness; JUnit Before/After инициализация/запрет bulk/settings наследуются обоими классами. Изменены лишь private→protected для общих свойств/helpers/nested fixture types; immutable constants и чистые other/section перенесены из companion в file-private declarations. Все initializer/method bodies и gate/cancellation semantics восстановлены обратной подстановкой без различий.

Точная проверка: все 22 test names/тела побуквенно равны `dd33755` после удаления только шести разрешённых blank lines (94/96/98/111/113/115); SHA256 каждого тела и результат обратной сверки fixtures записаны в `/tmp/plinth-yu5f-readiness-split-preservation-20261114.json`. Runtime filter обеих групп указан выше; protected paths остаются 12. Начинается полный единый --continue gate, итогов до окончания процесса нет.

Первый полный gate этого отдельного этапа завершён: exit 1, 1m38s, 68 tasks (6 executed/62 up-to-date), `detekt` PASS, оба compile — ровно 9 ожидаемых missing-readiness errors. Неожиданно `ktlintTestSourceSetCheck` обнаружил только неверный отступ перенесённых top-level constants/helpers (780–800). Использована единственная разрешённая коррекция: отступ file-private declarations приведён к top-level, без изменения initializer/function bodies. Сверка всех 22 тел и fixtures повторена, PASS; лог первого gate `/tmp/plinth-yu5f-readiness-split-gates-20261114.log`. Следующий полный gate — окончательный в этом этапе, неожиданный повторный FAIL означает stop без дальнейших правок.

### Итог отдельного корректирующего этапа

Фактически **`gpt-6.1-sol`, `xhigh`**. Разрешённый split 16+6 выполнен только в прежнем `Yu5fReadinessSearchTest.kt`; второй путь — эта задача. Все 22 имён и тела точны относительно `dd33755` после удаления только шести разрешённых blank lines; обратная сверка всего setup/helpers/controlled fixtures/immutable declarations повторена перед коммитом, PASS. Сохранены gate/cancellation outcomes, before/after assertions и отдельное mutable состояние каждого JUnit test; новых singleton/зависимостей/production изменений нет. Прочие protected paths не менялись. `git diff --check` PASS.

**Окончательный процесс завершён:** exit 1 только из-за двух ожидаемых compile RED targets; **2m07s**, **68 tasks: 6 executed/62 UP-TO-DATE**, FROM-CACHE нет. Полные штатные **ktlintCheck и detekt PASS**; изменённый JVM ktlint и detekt выполнены свежими. `uniffiKotlin` присутствует в графе и UP-TO-DATE, не исключён; host Rust/main Kotlin/Android resources повторно не исполнялись. Test Kotlin compile tasks выполнены свежими: ровно **6 JVM + 3 AndroidTest** `No parameter with name 'readiness' found.`, без других compiler errors. Runtime tests/эмулятор/APK/телефон не запускались; готовность runtime-гонок этим не утверждается. Лог `/tmp/plinth-yu5f-readiness-split-final-20261114.log`.

В этом отдельном этапе использована одна ограниченная коррекция отступов top-level declarations после первого полного gate; повторный процесс не содержит неожиданных FAIL. Сохранён обязательный будущий runtime command: `bash ./gradlew --no-daemon :app:testGithubDebugUnitTest --rerun --tests 'io.github.puflik.plinth.ui.search.Yu5fReadiness*Test' -x :app:cargoNdkBuild` с Linux environment выше. Он включает оба новых класса; executor также запускает прежние progressive/UI gates. Source file и список 12 protected paths прежние. Новый hash ниже остаётся **candidate**, без статуса принятого baseline; следующий этап — независимое Astra review всего candidate, production здесь не начинался.

Новый candidate тестов: `6e8c32a2e4da0cd3a4341055138406d85f79420b`; это итог отдельного разрешённого корректирующего этапа, не принятый baseline. Следующий commit только закрепляет hash. Текущая Gradle session завершена; фоновых сборок этого этапа нет. После записи выполняется обычный direct push `work/yu5f`, без main/rebase/merge/force.

## Независимое ревью candidate — 2026-11-14

Astra xhigh проверил candidate `6e8c32a` / HEAD `32be91b`: **CHANGES REQUESTED**.
Полный сохранённый отчёт — [yu5f-review-20261114.md](yu5f-review-20261114.md).
Независимо подтверждены восемь constructor migrations, неизменность старых
assertions/fixtures, split 16+6, сохранение 22 тел, отдельное состояние тестов
и фильтр обеих групп. Полные ktlint/detekt PASS; compile RED только по девяти
заранее объявленным отсутствующим readiness arguments; runtime не проверялся.

**B1, следующий узкий этап Sol xhigh:** добавить один controlled VM guard
в существующий Yu5fReadinessSearchTest.kt, не меняя прежние 22 тела. Оба
провайдера активны; одному текущему wait выдать TimedOut. Проверить точную
пустую PROVIDER_DOWN-секцию, loading только соседа, отсутствие отмены его
исходного wait, неизменное число ожиданий и отсутствие searchIn. Затем
завершить Applied и успешный поиск того же соседа: обе секции сохранены,
loading пуст. Старый timeout/cache/retry guard оставить. Production запрещён.

**B2:** исходная команда protection diff перечисляла только семь файлов.
Координатор заменил её выше на Bash-команду с exit-code и всеми 12 путями
из авторитетного списка; placeholder test_commit перед запуском заменить
принятым hash. Множества путей совпали, фактический diff candidate→HEAD
по всем 12 пуст. Это документальная корректировка, повторная независимая
проверка команды остаётся вместе с новым guard. Нового accepted baseline нет.

В B1-проходе разрешены только новый guard в прежнем файле и taskfile;
после полных style checks и объявленного constructor RED — новый candidate,
отдельное ограниченное ревью B1 и исправленной B2-команды, затем executor.
Пятичасовое окно после ревью 86% (недельное 53%): новый агент сейчас не
запускался при действующем пороге 65%. Граф отстаёт; сборок после ревью нет.


## Коррекция B1 планировщиком — 2026-11-15

Фактически **`gpt-6.1-sol`, `xhigh`**; отдельный узкий этап по B1 из независимого ревью от 2026-11-14. Координатор передал чистый HEAD `4f4d8d39cbb76375fad4cd63d1e1fa9e5230e970` (`work/yu5f`); лимиты непосредственно перед запуском — 3% пятичасового / 57% недельного, около двух дней до сброса. Вложенных агентов нет. Разрешены только один новый guard в прежнем `Yu5fReadinessSearchTest.kt` и этот taskfile; B2-команда со всеми 12 путями сохраняется без изменения. Данные пользователя не меняются, отдельное data-review не требуется; candidate остаётся непринятым до Astra xhigh.

### План и записанный сценарий

1. Сохранить все прежние 22 тела, harness и fixtures дословно; добавить один controlled VM guard в существующий search-класс, без production/API/DI изменений.
2. Оба провайдера ждут текущую ALL; завершить первый конкретный wait как TimedOut. Проверить точную пустую PROVIDER_DOWN-секцию, loading только другого ID, исходный sibling wait не отменён, список двух wait objects неизменен, searchIn отсутствует.
3. Завершить Applied именно сохранённого sibling wait и его единственный успешный searchIn; timeout-секция остаётся во время поиска и рядом с успешной секцией после него, loading пуст. Старый cache/retry guard и все assertions сохраняются.
4. Выполнить полный `ktlintCheck detekt` и оба Kotlin compile targets единым `--continue`; исключить только `:app:cargoNdkBuild`, сохранить `uniffiKotlin`. Ожидается только объявленный constructor RED: шесть JVM и три AndroidTest `No parameter with name 'readiness' found.`; runtime этим не подтверждается.
5. Сверить exact preservation прежних тел/остатка файла и остальных 11 protected paths, записать фактические gates, commit/push нового candidate; независимое B1/B2-ревью обязательно до executor.

### Проверки по ходу

Graphify query выполнен на отстающем графе основного checkout: нужных readiness-узлов нет, возвращены старые B1-концепты; ограниченные task/review/source ranges использованы как актуальная опора. Граф не изменён. Новый guard записан; начинается единственный полный gate, одной корректировкой можно воспользоваться только при неожиданном сбое. Логи — `/tmp/plinth-yu5f-b1-gates-20261115.log`; сохранённый source до добавления — `/tmp/plinth-yu5f-b1-before-20261115.kt`.


### Итог B1 и проверка защиты

Полный процесс завершён, exit 1 **только** по двум объявленным compile targets: ровно **6 JVM + 3 AndroidTest** `No parameter with name 'readiness' found.`, иных compiler errors/failed tasks нет. **1m39s**, **68 actionable tasks: 5 executed / 63 UP-TO-DATE**, FROM-CACHE нет. Полные штатные `ktlintCheck` и `detekt` **PASS**: `runKtlintCheckOverTestSourceSet` и `detekt` выполнены свежими; `ktlintTestSourceSetCheck`/aggregate `ktlintCheck` UP-TO-DATE. `uniffiKotlin` присутствует, UP-TO-DATE, не исключён. Команда: `bash ./gradlew --no-daemon --continue ktlintCheck detekt :app:compileGithubDebugUnitTestKotlin :app:compileGithubDebugAndroidTestKotlin -x :app:cargoNdkBuild --console=plain`, с прежним Linux environment и PATH, предварённым JDK bin. Единственное исключение — cargoNdkBuild. Неожиданных сбоев нет, корректировок не использовано. Полный лог `/tmp/plinth-yu5f-b1-gates-20261115.log`.

Добавлен ровно один 42-строчный test block: **23 сценария, split 17+6**. Удаление только этого блока восстанавливает весь source побуквенно к HEAD `4f4d8d3`, включая прежние **22 имён/тел**, timeout/cache/retry, harness, gate/cancellation fixtures и все assertions/timing. Прочие **11 protected paths** неизменны относительно того же HEAD; `git diff --check` PASS, изменены только два разрешённых файла. Авторитетный список из 12 путей и B2 protection-command совпадают как множества и по количеству, сама B2-команда не менялась. Доказательство `/tmp/plinth-yu5f-b1-preservation-20261115.json`; baseline snapshot `/tmp/plinth-yu5f-b1-before-20261115.kt`.

Runtime JVM/UI guards, эмулятор, устройство и APK не запускались; compile RED подтверждает только отсутствующий обязательный constructor API, не выполнение нового TimedOut-сценария. Production, DI, зависимости, прочие tests/fixtures и main не менялись. Независимое ревью не выполнялось планировщиком. Новый test commit остаётся **candidate**, его hash будет закреплён следующим taskfile-only commit; до принятия Astra xhigh executor не запускать. Текущий Gradle process завершён, фоновых сборок нет. Предстоит обычный direct push `work/yu5f`; без merge/rebase/force/release.

Новый **candidate test commit**: `92c78bdb5220542ed1425e285ee1d44883b7eac9`; hash закреплён этим taskfile-only commit. Обычная публикация `work/yu5f` выполняется после фиксации; независимая приёмка B1/B2 ещё впереди. Предыдущая опора `6e8c32a` и отчёт CHANGES REQUESTED не удалены.


## Приёмка baseline и ограниченный этап VM — 2026-11-15

Astra xhigh независимо закрыл B1/B2: добавленный TimedOut guard сохраняет
исходное ожидание соседа/loading и поздний успешный результат; старые 22 тела,
harness и остальные 11 protected paths сохранены. B2-команда покрывает все
12 путей, Bash syntax PASS. Новый baseline `92c78bdb5220542ed1425e285ee1d44883b7eac9`
принят. Полный отчёт сохранён координатором в main `docs/work/review-20261115.md`;
исходник `/tmp/plinth-bounded-review-20261115.md`. Runtime ещё не выполнялся:
до реализации обе test-компиляции дают только известные 9 constructor errors.

Координатор разделил исполнение: сначала VM (Sol high), затем UI и реальное
воспроизведение прокрутки (Terra high), затем независимое ревью Astra xhigh.
Это разрешение на ограниченный этап, не отмена критериев общей задачи.
Первым действием UI-этапа остаётся воспроизведение на выбранном эмуляторе;
без него scroll/UI не принимаются. Этап VM не объявляет задачу законченной.

**Текущему исполнителю менять только**
`app/src/main/kotlin/io/github/puflik/plinth/ui/search/SearchViewModel.kt`
и этот файл в разделе «Отчёт исполнителя». Реализовать принятое решение
независимых provider attempts/cache/lifecycle и обязательный OnlineReadiness
по разделам выше. SearchScreen, ресурсы, все тесты, DI/Gated/Core, зависимости
и общие документы не менять. Дополнительный тестовый файл здесь не требуется:
имеются progressive, readiness и прежние search/song guards.

Linux: JDK `/home/puflik/.local/opt/jdk21`, SDK `/home/puflik/Android/Sdk`,
GRADLE_USER_HOME `/home/puflik/.gradle`, RUSTUP_TOOLCHAIN=1.98.1,
JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true, JDK bin первым в PATH.
Wrapper запускать через `bash ./gradlew`; исключать только `:app:cargoNdkBuild`,
не `uniffiKotlin`. Сначала свежие `:app:testGithubDebugUnitTest --rerun`
с фильтром `io.github.puflik.plinth.ui.search.*`; затем полные
`ktlintCheck detekt :app:compileGithubDebugAndroidTestKotlin`. Сохранять
логи, отличать свежие задачи/кэш. UI runtime и полный CI остаются до приёмки
всей задачи. Не ставить NDK/эмулятор и не подключать телефон для VM-этапа.

После неожиданного падения допускается одна коррекция; повторное падение —
стоп, отчёт, commit/push только work/yu5f. Защищённый diff всех 12 путей от
принятого baseline обязан быть пуст. Никаких suppress/ignore/ослаблений,
main/merge/rebase/force/release. По ходу сохранять находки и ограничения.


## Следующая передача — после завершения VM 2026-11-15

VM `dc2307e` опубликован, guard baseline `92c78bd` неизменён. Координатор
дополнительно проверил XML: 7 suites/78 tests, failures/errors/skipped=0,
и пустой diff всех test/sharedTest/androidTest от baseline. Фоновых локальных
сборок нет. Окна после этапа 86%/69%, поэтому нового агента не запускать
до свежей проверки лимитов; primary reset 15 ноября 15:09 +05.

Далее нужны Astra xhigh review VM и Terra high UI-этап с воспроизведением
прокрутки первым действием. Для UI сохраняется исходный список разрешённых
файлов и критерии. В main/выданный APK эта VM-реализация не перенесена;
сливать всю yu5f до независимого ревью, UI-проверок и зелёного CI нельзя.


## Независимое ревью VM и следующий планировщик — 2026-11-15, вечер

Фактически Astra xhigh, без повторных Gradle-прогонов: **CHANGES REQUESTED**.
Полный отчёт с точными шагами и исходниками — `yu5f-vm-review-20261115.md`.
VM-B1: после grace новый scope появляется до первого свежего combine/debounce;
retry в это окно запускает попытку со старым query/configuration. Retained cache
и настройка membership должны согласоваться до разрешения retry; валидное
раннее намерение retry нельзя просто потерять. Ни один из 78 зелёных search
тестов не покрывает это окно. Требуется сначала реальный RED guard.

CI `43682043161` на `cae7eac` FAILURE: единственное падение из 1111 tests
(1 skip) — Yu4gGatedSearchInTest, expected searches=[concert], actual=[].
При этом все assertions готовности/provider/result прошли. Fake давно (cbe6934)
записывает searchIn в providerSearches; ошибка миграции предшествует VM.
Это **CI-C1**, не flaky и не основание менять Gated или вернуть общий счётчик.
Fdroid XML в артефакте нет, both-flavor PASS не заявляется. Main `70aadc9`
прошёл CI `43682043180`; выданный APK не содержит рассматриваемую новую VM.

**Разрешённый следующий scope планировщика Sol xhigh — только три файла:**

- `docs/work/yu5f.md` — план, результаты, новый candidate/hash и список защиты.
- Новый `app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5fAdditionalSearchTest.kt`:
  использовать существующий Yu5fReadinessHarness без его изменения. Три узких
  случая из отчёта: отключённый YT, master-off, валидный IA retry после grace
  в окно до 300 ms. Проверять точные readiness/search calls/configuration,
  сохранность IA cache, отсутствие запрещённого loading/отложенного replay,
  однократность допустимого retry после Applied. Сначала доказать RED на dc2307e.
- `app/src/test/kotlin/io/github/puflik/plinth/online/Yu4gGatedSearchInTest.kt`:
  только заменить старый `inner.searches == [concert]` на точный
  `inner.providerSearches == [(PROVIDER, concert)]` и добавить пустой bulk
  `inner.searches`. Все остальные assertions и три других теста сохранить.
  Это отдельное разрешение миграции старого защищённого теста, не исполнителю.

Все прежние 12 protected paths неизменны; production запрещён в этом этапе.
Оба новых protected paths добавить к списку/команде защиты (всего 14) вместе
с candidate-коммитом. Новый baseline принимает независимый Astra xhigh после
проверки RED, неизменности старых тестов и точного смысла Yu4g migration.
Полные ktlintCheck/detekt и обе test-компиляции ожидаются PASS; свежие search
JVM + Yu4g должны падать только на новых объявленных B1 assertions. Логи
сохранять. Прежний Linux env и исключение только cargoNdkBuild сохраняются.
Одна коррекция после неожиданного падения; повтор — stop/report/commit/push.

После приёмки тестов отдельный Sol меняет только SearchViewModel/отчёт;
согласование свежего контекста до retry, без новых API/timers/permanent jobs.
Затем Astra review точной правки и полный CI обеих flavors, после — Terra
UI с реальным воспроизведением прокрутки перед редактированием экрана.

На вечернем входе окна 1%/72%; в ходе review 36%/77%. Утром было 57% недели,
расход дня уже 20 пунктов при ориентире 43/2 ≈21.5. Следующего агента сегодня
не запускали: он превысит норму. Перед продолжением сверить фактические окна,
сохранить резерв до reset 17 ноября 09:39 +05. Рекомендуется /compact;
новый чат не нужен, граф обновлять только в конце волны.

## VM-B1 regression planning — 2026-11-15

### Цель и контекст

Фактически планировщик **`gpt-6.1-sol`, `xhigh`**; узкий проход по решению Astra xhigh из `docs/work/yu5f-vm-review-20261115.md` (B1/C1 и Exact follow-up), без production-кода. Исходный HEAD `d281678`, production VM `dc2307edebc072dbc554f934e332d910c9b07734`. Перед запуском координатор проверил окна 63%/82%; пользователь явно разрешил продолжать с расходом остатка. Вложенных агентов нет. Graph query выполнен по root graph (11365 nodes), граф отстаёт; rebuild/extraction не запускались, токены семантического извлечения не расходовались.

### Решение и инварианты

1. Новый `Yu5fAdditionalSearchTest` наследует неизменённый `Yu5fReadinessHarness`: завершённые IA success/YT NO_NETWORK для `concert`, уход 5001 ms, изменения settings без подписчика, возврат и retry при неизменном виртуальном времени до 300 ms debounce.
2. Removed YT и master-off не создают readiness/search, не включают запрещённый loading и не проигрывают retry после 301 ms; при удалении соседа IA cache сохраняется. Retained IA получает ровно одну новую readiness с ARCHIVE_ONLY, ровно один search только после Applied; двойной tap сливается, YT отсутствует, итог IA обновляется.
3. CI-C1 меняет только устаревший assertion bulk-счётчика на точный provider/query и пустой bulk; остальные assertions и три других теста Yu4g сохраняются. Двенадцать прежних protected paths остаются побайтово неизменны.
4. Следующий отдельный VM-исполнитель: Sol high, только `SearchViewModel.kt` и отчёт. На каждой новой collection до разрешения retry должен быть согласован текущий normalized settings/query/membership независимо от restarted nonblank debounce. Минимальная коррекция: считать collection несогласованной до первого свежего контекста; сохранить по провайдеру намерение раннего retry, после согласования однократно проверить текущую eligibility и запустить через существующий attempt/readiness путь. Removed/master-off intent отбросить; retained intent потребить один раз, не перезапускать completed sibling. Свежие settings должны фильтровать cache/loading сразу; не ждать 300 ms ради настроек. Изменение raw query во время остановки не разрешает старый retry: intent проверяется против свежего query/generation. Сохранить first-frame cache для прежнего контекста, identity/cancellation/ABA и library debounce. Новых public API, timers, permanent collectors и data-layer изменений нет.

### Файлы и остановка

Текущему планировщику менять только `docs/work/yu5f.md`, новый `app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5fAdditionalSearchTest.kt` и ровно разрешённую миграцию `app/src/test/kotlin/io/github/puflik/plinth/online/Yu4gGatedSearchInTest.kt`. Все прочие файлы, production, harness и старые 12 protected paths запрещены. Данные пользователя не затрагиваются, Astra max data review здесь неприменимо; независимая приёмка нового test candidate — Astra xhigh до следующего VM-исполнителя. Одна коррекция неожиданного gate failure; повтор — stop/report/commit/push. Ожидаемы только три новых B1 assertion RED; compile RED недопустим, API уже существует.

### Проверки и готовность

Запланировано: свежий search JVM + Yu4g с `--rerun` (только три новых B1 RED), полные `ktlintCheck detekt`, обе Kotlin test-компиляции PASS. Linux env и исключение только `:app:cargoNdkBuild` сохраняются; uniffiKotlin не исключается. Логи `/tmp/plinth-yu5f-vmb1-jvm-20261115.log` и `/tmp/plinth-yu5f-vmb1-checks-20261115.log`. Прежние 12 protected сверить с `92c78bd`; обе новые protected paths доводят authoritative список/команду до 14. После свежих gates и exact preservation — candidate commit/push только work/yu5f; baseline принимает независимый Astra xhigh.

### Отчёт планировщика по ходу

- Прочитаны AGENTS, README/AGENT_ROLES/TEMPLATE, bounded task handoff и полный 66-строчный VM review; применены graphify, kotlin-coroutines-flows и tdd-workflow.
- Исходное дерево чистое, ветка work/yu5f; изучены только нужные bounded диапазоны harness/VM/Yu4g. Production/старые tests не изменялись; подготовка guards начата.

- Добавлены три узких VM-B1 guards с общим setup в новом файле; harness не менялся. Yu4g migration — ровно один replacement и один новый empty-bulk assertion. Authoritative protection list/команда расширены до 14; candidate hash будет закреплён после gates отдельным taskfile-only commit.

### Точные команды текущего прохода

```bash
export JAVA_HOME=/home/puflik/.local/opt/jdk21
export ANDROID_HOME=/home/puflik/Android/Sdk
export ANDROID_SDK_ROOT=/home/puflik/Android/Sdk
export GRADLE_USER_HOME=/home/puflik/.gradle
export RUSTUP_TOOLCHAIN=1.98.1
export JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true
export PATH="$JAVA_HOME/bin:$PATH"
bash ./gradlew --no-daemon :app:testGithubDebugUnitTest --rerun \
  --tests 'io.github.puflik.plinth.ui.search.*' \
  --tests 'io.github.puflik.plinth.online.Yu4gGatedSearchInTest' \
  -x :app:cargoNdkBuild > /tmp/plinth-yu5f-vmb1-jvm-20261115.log 2>&1
bash ./gradlew --no-daemon --continue ktlintCheck detekt \
  :app:compileGithubDebugUnitTestKotlin :app:compileGithubDebugAndroidTestKotlin \
  -x :app:cargoNdkBuild > /tmp/plinth-yu5f-vmb1-checks-20261115.log 2>&1
```

Готово для передачи на независимое review, когда свежий JVM выполнит 85 tests с ровно тремя новыми B1 assertion failures, Yu4g 4/4 PASS, прежние search 78/78 PASS, все style/compile gates PASS, old-12 diff пуст, migration exact и команда содержит ровно 14 защищённых путей. Это RED test candidate, не VM acceptance; UI runtime и both-flavor CI остаются дальнейшими обязательными этапами.

- Свежий JVM RED выполнен (33 s; 45 tasks: 5 executed, 40 up-to-date; `:app:testGithubDebugUnitTest` реально исполнен, `:app:compileGithubDebugUnitTestKotlin` исполнен): **9 suites, 85 tests, 3 assertion failures, 0 errors/skips**. Падают только три новых B1: removed/master-off создают третью readiness вместо ожидаемых двух; retained IA ждёт ALL вместо ARCHIVE_ONLY. Виртуальное время в критическом окне не изменялось. Прежние search **78/78 PASS**, Yu4g **4/4 PASS**. Это runtime RED, не compile RED; VM source идентичен `dc2307e`. Лог `/tmp/plinth-yu5f-vmb1-jvm-20261115.log`, XML summary `/tmp/plinth-yu5f-vmb1-jvm-summary-20261115.json`; полные XML обеих затронутых suites скопированы в `/tmp/plinth-yu5f-vmb1-Yu5fAdditionalSearchTest-20261115.xml` и `/tmp/plinth-yu5f-vmb1-Yu4gGatedSearchInTest-20261115.xml`.
- Exact preservation PASS: все прежние 12 путей побайтово равны `92c78bd`, Yu4g отличается ровно разрешённым replacement+empty-bulk assertion, protection-command и authoritative list содержат одинаковые 14 путей; `/tmp/plinth-yu5f-vmb1-preservation-20261115.json`. Первоначальный вспомогательный regex проверки путей захватил newline последней строки; исправлен сам read-only verifier, исходные tests/plan из-за него не менялись. Неожиданных Gradle failure пока нет; полные style/compile gates выполняются.

- Первый полный style/compile gate: detekt PASS (app task выполнен), обе test Kotlin compilations UP-TO-DATE/PASS, ktlint FAIL только на chained-call wrapping в новом файле (строки 117, 139–140). `BUILD FAILED in 55s`, 68 tasks: 3 executed, 65 up-to-date; исходный лог сохранён `/tmp/plinth-yu5f-vmb1-checks-initial-20261115.log`. Применена единственная разрешённая коррекция: переносы ровно трёх цепочек `.last/single → outcome → complete`, без изменения вызовов/assertions/timing. После неё повторяются полный style/compile gate и свежий JVM RED; повтор неожиданного failure означает стоп.

- Итоговый полный style/compile gate после единственной коррекции **PASS**, `BUILD SUCCESSFUL in 1m 7s`, 68 tasks: 5 executed, 63 up-to-date. `:app:ktlintTestSourceSetCheck`, `:app:ktlintCheck` и `:app:compileGithubDebugUnitTestKotlin` реально выполнены; app detekt также реально выполнен и PASS, AndroidTest Kotlin compilation UP-TO-DATE/PASS. Это полный вызов ktlintCheck/detekt, не утверждение о повторном исполнении каждого source set. Финальный лог `/tmp/plinth-yu5f-vmb1-checks-20261115.log`; uniffiKotlin сохранён, исключён только cargoNdkBuild. Свежий JVM повтор после formatting запущен.

### Итог VM-B1 planning

Фактически **`gpt-6.1-sol`, `xhigh`**. Финальный свежий JVM после форматирования: **85 tests, ровно 3 новых B1 assertion RED; прежние search 78/78 PASS и Yu4g 4/4 PASS, errors/skips 0**, `29s`, `45 actionable tasks: 2 executed, 43 up-to-date`. Test task исполнен свежо; предыдущий baseline лог сохранён с `-initial`. Final XML/summary в указанных `/tmp` путях перезаписаны результатом окончательного файла. Это ожидаемый runtime RED на неизменённой VM `dc2307e`, compilation успешна.

Полные ktlintCheck/detekt и обе test Kotlin compilations **PASS** после единственной коррекции переносов; других коррекций нет. Production, harness и все прежние 12 protected paths неизменны; Yu4g migration точная, остальные assertions и три теста сохранены. Проверка scope/14-path list-command/exact preservation повторена перед commit; `git diff --check` PASS. Изменены ровно три разрешённых файла. Новые API, suppress/ignore, данные, UI, зависимости, graph rebuild, main/rebase/merge/force/release и вложенные агенты отсутствуют.

Результат — **test candidate**, не принятый baseline и не принятие VM. Следующее действие координатора — независимая Astra xhigh приёмка RED3/exact migration/protection14; только затем отдельный VM correction по этому плану. Полный both-flavor CI и UI runtime здесь не выполнялись. Hash test candidate будет закреплён следующим taskfile-only commit; обычный exact-branch push work/yu5f разрешён, выполняется после проверки remote/tracking/diff.

Новый **VM-B1 test candidate**: `b52b256eae8486e6fb92c7094039460740755b25` (`test(search): guard retries before resumed context reconciliation`); этот taskfile-only commit закрепляет hash в шапке и 14-path protection-command. Кандидат не принят, следующий VM executor не запускался. Финальный runtime RED выполнен за 29 s (45 tasks: 2 executed, 43 up-to-date); все проверки завершены, фоновых Gradle sessions этого прохода нет. После metadata commit выполняется обычный exact-branch push без proxy, main/rebase/merge/force.


## Приёмка VM-B1 тестов — 2026-11-15, поздний вечер

Автор разрешил только одно независимое ревью сверх недельного порога:
«Да, только это ревью». Перед Astra xhigh окна 11%/88%, после 39%/92%.
Ревью завершено PASS; точный отчёт `yu5f-vmb1-tests-review-20261115.md`.
Принят baseline `b52b256eae8486e6fb92c7094039460740755b25` для всех 14 путей
из существующей команды защиты. Production и старые 12 неизменны;
проверены реальные XML RED3/PASS82, exact Yu4g migration и причинность guards.
VM-B1 остаётся открытым. Тесты не ослаблять и не изменять будущему исполнителю.

Следующий отдельный Sol high после проверки лимитов меняет только
`app/src/main/kotlin/io/github/puflik/plinth/ui/search/SearchViewModel.kt`
и свой отчёт в `docs/work/yu5f.md`. Действует узкое решение раздела
«VM-B1 regression planning»: актуальные settings/query/membership перед retry,
валидное намерение ровно один раз, removed/master-off без запуска,
независимость от повторного debounce и сохранение cache/identity/cancellation.
Никаких новых API, timers, permanent collectors или data/UI изменений.
Raw query при остановке может измениться: запрет устаревшего retry обязателен,
хотя три новых guards держат query прежним; это отдельно проследить при VM review.

Требуются свежие 85/85 scoped JVM GREEN, полные style/обе test compilations,
пустой diff всех 14 protected paths, независимый production review и полный
CI обеих flavors перед дальнейшим UI-этапом. Один неожиданный failure допускает
одну коррекцию, повтор — stop/report/commit/push. Нынешний CI43723292462 имеет
Rust PASS и падение unit step; конкретные CI assertions не подтверждены,
поскольку job log не скачался из-за сети. Локальный RED не равен CI PASS.

Разрешённое исключение исчерпано этим review; исполнитель сейчас не запускался.
Кредит reset не использован, граф остаётся позади до конца волны.


## Полный Github CI baseline подтверждён — 2026-11-17

Координатор разобрал сохранённые XML CI43749054774 на точном `7534c77`:
1114 tests, 1110 passed, три ожидаемых VM-B1 failures, 0 errors, 1 skip.
Все failures только в Yu5fAdditionalSearchTest; Yu4gGatedSearchInTest4/4 PASS.
Других падений Github suite нет. Fdroid XML отсутствуют, both-flavor PASS
не заявляется. Прежняя неопределённость конкретных CI assertions закрыта;
доказательства и SHA256 artifact — `yu5f-ci-audit-20261117.md`.
Baseline14 b52b256 и узкий scope следующего VM correction не меняются.
Новых агентов/сборок/изменений кода не было: остаток недели3%, reset17ноября09:39+05.


## Отчёт исполнителя — VM-B1 correction, 2026-11-17

Фактически **`gpt-6.1-sol`, `high`**; узкий исполнитель, без подагентов. Исходный HEAD `e808a18`, ветка `work/yu5f`, worktree `/home/puflik/src/Plinth-yu5f` чист. Меняются только `SearchViewModel.kt` и этот отчёт. Принятый baseline14 `b52b256eae8486e6fb92c7094039460740755b25` остаётся защищённым; исходный runtime RED3/PASS82 подтверждён предыдущим независимым review и полным Github CI, повтор исходного RED не нужен.

Прочитаны AGENTS, workflow/roles, bounded task/continuation и три компактных отчёта. Использованы graphify query и kotlin-coroutines-flows; root graph отстаёт, rebuild не выполнялся. Причина B1 подтверждена по коду: scope разрешал retry до первого combine, задержанного restarted debounce. Реализация согласует settings немедленно с прежним settled query только при совпадении текущего raw normalized query; иной raw query сначала получает пустой network context до штатного debounce. Каждая collection начинает несогласованной; ранние допустимые retry сохраняют identity прежнего attempt, после согласования проверяют свежую membership/query и потребляются единожды. Новых API/timers/permanent collectors/data/UI изменений нет.

Проверки ещё не запускались; единственная корректирующая попытка не использована. Логи текущего прохода имеют префикс `/tmp/plinth-yu5f-vmb1-fix-20261117`.

Свежий scoped JVM **PASS: 9 suites, 85/85 tests, failures/errors/skips 0**; XML разобраны непосредственно из текущего build output. Все три VM-B1 guards прошли полностью, включая поздние assertions; Yu4g 4/4 PASS. `BUILD SUCCESSFUL in 2m 32s`, 45 tasks: 11 executed / 34 up-to-date; test task и обе main/unit Kotlin compilations выполнены свежо. `uniffiKotlin` UP-TO-DATE, не исключён. Лог `...-jvm.log`, полная XML-сводка `...-jvm-summary.json` по префиксу выше. Read-only preservation PASS: ровно 14 authoritative paths и все test/sharedTest/androidTest деревья имеют нулевой diff к baseline14; `...-protection.json`. Первое ожидание Gradle разрешилось без вмешательства; диагностический thread dump сохранён отдельно. Неожиданных failures нет, корректирующая попытка не использована; выполняются полные style/обе test Kotlin compilations.

Первый полный style/compile gate: **FAIL только detekt CyclomaticComplexMethod** у `reconcile` (16 при максимуме14); ktlint PASS, обе test Kotlin compilations PASS (AndroidTest выполнена свежо), иных ошибок нет. 1m13s, 68 tasks: 4 executed /64 up-to-date. Исходный лог сохранён `...-checks-initial.log`. Единственная разрешённая коррекция: вынесено потребление pending retries в отдельную private-функцию без изменения условий, порядка или поведения. Suppress/ignore/tests не добавлялись и не менялись. После коррекции повторяются полный style/compile gate и свежий scoped JVM; любое повторное gate failure означает stop/report/commit/push.

### Финал VM-B1 исполнителя

Production commit **`f812ee8e3867a85d466f1d43dfb0df2c4af68933`** (`fix(search): reconcile resumed context before provider retries`). Финальная коррекция использовала ровно одну разрешённую попытку после detekt failure; повторных failures нет. Полные `ktlintCheck detekt :app:compileGithubDebugUnitTestKotlin :app:compileGithubDebugAndroidTestKotlin` **PASS**, 2m1s, 68 tasks:11 executed/57 up-to-date; ktlint main, detekt и обе test Kotlin compilations выполнены. Затем свежий `:app:testGithubDebugUnitTest --rerun` с обоими заданными filters **PASS:9 suites,85 tests,0 failures/errors/skips**, 53s,45 tasks:4 executed/41 up-to-date; все поздние B1 assertions GREEN, Yu4g4/4PASS. Единственное исключение в обоих запусках — `:app:cargoNdkBuild`; `uniffiKotlin` включён/UP-TO-DATE. Полные финальные логи `/tmp/plinth-yu5f-vmb1-fix-20261117-checks.log` и `/tmp/plinth-yu5f-vmb1-fix-20261117-jvm.log`; свежая сводка `/tmp/plinth-yu5f-vmb1-fix-20261117-jvm-summary.json`; исходные до единственной коррекции сохранены с `-initial`.

Итоговая сохранность:14 authoritative protected paths и все три test trees побайтово равны принятому baseline14; `git diff --check` PASS; изменены только VM и этот отчёт. Перед commit/push проверены точные origin `https://github.com/Puflik/Plinth.git`, tracking `origin`/`refs/heads/work/yu5f`, разрешённая branch и точный diff. Report metadata будет отдельным следующим коммитом; затем один обычный direct push обоих в `HEAD:refs/heads/work/yu5f`, без proxy/force/merge/rebase/main. Фактический результат push и metadata hash передаются координатору в финале.

Поведение: при restart settings согласуются без ожидания повторного nonblank debounce, кэш прежнего контекста остаётся первым кадром; removed/master-off не запускают readiness/search, retained retry выполняется однократно. Pending retry связан с identity и проверяется после membership-фильтрации; двойной provider tap не дублирует attempt. Changed raw query не переиспользует старый settled context: initial network context пуст до штатного debounce, immediate retry требует совпадения raw/context, deferred retry повторно сверяет текущий raw перед dispatch. Поэтому старый query retry не разрешается. Путь library debounce, current generation/attempt identity, отмена scope после grace и ABA/readiness цикл сохранены.

Это отчёт реализации, не самостоятельная приёмка: далее требуется независимый Astra review и полный both-flavor CI. Локальный85-test scoped PASS не равен полному CI; Fdroid, UI/emulator/scroll и live YouTube здесь не проверялись. Новые protected tests/fixtures не добавлялись/не менялись, data/UI/dependencies/API/timers/permanent collectors не затронуты. Граф остаётся позади до конца волны. Фоновых Gradle sessions после финала нет.


## Независимая приёмка VM-B1 correction — 2026-11-17

Astra xhigh: PASS на production `f812ee8e3867a85d466f1d43dfb0df2c4af68933`,
metadata `362ab9e9f7e54d1a6a788b08f6ce2f2adab51abb`. Полный отчёт рядом:
`yu5f-vmb1-fix-review-20261117.md`. VM-B1 закрыт независимым review; 85/85
и прежние14 protected paths подтверждены. Changed raw query/deferred retry
проверены статически; это не новый runtime guard. Последний full CI44116043234
ещё выполняется; его успех обязателен перед следующим UI-этапом.

Следующий Terra high начинает с реального воспроизведения scroll/return
на явно выбранном эмуляторе; затем меняет только ранее разрешённый UI scope
SearchScreen/строки Show all и отчёт, без защищённых тестов. Прежние критерии
секций/5 строк/Show all/retry/сохранения позиции остаются. Если причина вне
разрешённых UI путей, остановиться с доказательствами. Нынешний scoped VM
PASS не заменяет instrumented/runtime UI acceptance и не разрешает main merge.

Read-only preflight: SDK emulator/system-images отсутствуют, /dev/kvm
не найден, свободно57GiB. SDK tools/adb доступны; устройство не менялось.
Подготовка выбранного эмулятора и фактическое воспроизведение ещё нужны.
На последней проверке окна66%/10%; новый агент пока выше primary-порога.
Автор включил heartbeat plinth в текущем чате каждые5ч2мин с проверкой
обоих окон/суточной нормы и корректировкой следующего запуска по resetsAt+2мин.
Граф остаётся позади до конца волны.


## Подтверждение полного CI — 2026-11-18

Heartbeat plinth подтвердил CI44116043234 completed/success на точном
362ab9e9f7e54d1a6a788b08f6ce2f2adab51abb. VM-B1 принят после независимого
Astra PASS и полного CI обеих flavors. Baseline14 b52b256 сохранён.
UI/scroll/runtime acceptance остаются обязательными до слияния всей задачи.
На новом входе окна2%/16%; следующий ограниченный этап допускается по бюджету.

## UI baseline — подготовка 2026-11-18

Исполнитель: `gpt-6.1-terra`, `high` (назначено координатором). HEAD `ef0dcf8`; production и защищённые тесты не менялись. В sandbox `/dev/kvm` скрыт, локальный запуск adb завершился `Operation not permitted`; проверка вне sandbox видит `/dev/kvm` (root:kvm). Начата установка официальных emulator, system image API 35 Google APIs x86_64 и NDK 28.2.13676358 через SDK manager; до завершения runtime и измерения не заявляются.

Установка остановилась до загрузки пакетов: `/home/puflik/Android/Sdk/cmdline-tools/latest/bin/sdkmanager` выводит `Downloading Android CLI...` и не сообщает URL или ошибку. Первая попытка прервана после ожидания; единственный повтор без proxy завершён `timeout` через 90 секунд (exit 124). `emulator`, `system-images`, `ndk` в SDK отсутствуют; AVD и serial не созданы, APK не устанавливался, runtime/scroll не измерен. Процессов sdkmanager/android CLI больше нет; обнаружен `adb -L tcp:5037 fork-server` PID 61195, его происхождение не установлено, сервер не останавливался во избежание воздействия на другие устройства. `/dev/kvm` вне sandbox существует, доступ эмулятора не проверен. Следующий шаг: отдельно выяснить URL и доступность загрузки Android CLI, восстановить SDK manager, затем повторить подготовку и baseline с явным emulator serial и проверкой `ro.kernel.qemu=1`. Исторический +209 px остаётся непроверенным; причину не устанавливаем.


## SDK blocker закрыт, следующий UI baseline — 2026-11-18

Terra baseline8d496c2 не запускал runtime из-за bootstrap Android CLI.
Отдельные Luna этапы сняли blocker: CLI1.1.16919734 в3e9db35 и SDK packages
в06c2357, отчёты android-cli-bootstrap-20261118.md и android-sdk-packages-20261118.md.
Использовать отдельный CLI через per-invocation ANDROID_CLI_BIN; existing
latest/bin/android остаётся зависающим bootstrap и не заменялся.

Установлены emulator37.2.13, system-images;android-35;google_apis;x86_64
revision9.0.0 и ndk;28.2.13676358/r28c. Метаданные, version и read-only
accel-check PASS, KVM usable вне sandbox. Cargo-ndk и все4 Android Rust targets
для1.98.1 уже есть. Никаких AVD/runtime/production UI правок пока не было.

Следующий ограниченный Terra baseline: создать отдельный Plinth AVD,
проверить конкретный serial/qemu=1, собрать нужные debug/test APK с настоящими
native libs и измерить прежние scroll/viewport критерии до правки экрана.
Repository scope этого baseline: только отчёт и при необходимости новый
Yu5fAdditionalSearchScreenTest.kt; protected14, VM, SearchScreen/strings
сохранить. Если runtime не воспроизводится или нужен иной scope, записать
доказательства; не заменять runtime компиляцией и не приписывать причину.
Телефон и неизвестный adb-server PID61195 не трогать.

При завершении setup окна77%/28%; день начался16%, суточные12пунктов потрачены.
Новый агент не запущен, heartbeat временно перенесён на19ноября00:02+05,
с возвратом к обычным5ч2мин на следующем допустимом запуске после проверки окон.
Граф остаётся позади до конца волны; состояние готово для /compact.

## Runtime baseline — 2026-11-18, в работе

Исполнитель: назначены и фактически используются `gpt-6.1-terra`, `high`.
Отдельный AVD `Plinth_yu5f_api35` создан в `/tmp/plinth-yu5f-ui-baseline-20261118/avd`, образ API 35 Google APIs x86_64. Эмулятор запущен с `-port 5560`, отдельный adb server port 5038; выбран `emulator-5560`. После запуска adb на 5038 подтверждены `ro.kernel.qemu=1` и `sys.boot_completed=1`. Посторонний server на 5037 не останавливался. До подтверждения никаких APK не устанавливалось. Лог: `/tmp/plinth-yu5f-ui-baseline-20261118/emulator.log`.

Сборка `:app:assembleGithubDebug :app:assembleGithubDebugAndroidTest` запущена без исключения `cargoNdkBuild`; конфигурация задаёт все четыре ABI. Лог `/tmp/plinth-yu5f-ui-baseline-20261118/build.log`. Для памяти 7.6 GiB эмулятор временно остановлен через `adb -s emulator-5560 emu kill`; после сборки планируется повторная проверка serial/qemu перед установкой.

Gradle дошёл до настоящего `cargo-ndk ndk -t armeabi-v7a -t x86_64 -t arm64-v8a -t x86 --platform 26`; сборка продолжается. Ожидание adb на отдельном порту сначала запустило собственный daemon 5038; затем `qemu=1` и boot=1 подтверждены. Сообщения emulator.log о недоступном adb предшествовали запуску daemon, повторная проверка прошла.

Native build: `armeabi-v7a` завершён за 2m30s, `x86_64` за 2m07s; начат `arm64-v8a`. Сборка продолжается, финальный APK ещё не проверен.

### Остановка по просьбе автора

Все четыре ABI завершили `cargo-ndk`: armeabi-v7a 2m30s, x86_64 2m07s, arm64-v8a 2m07s, x86 2m07s; библиотеки скопированы в `app/build/rust/jniLibs`. По просьбе автора сборка прервана до итогового `BUILD SUCCESSFUL`; общего успешного результата assemble нет. Debug APK существует (35 100 011 байт), архив читается и содержит `libplinth_ffi.so` всех четырёх ABI; завершение его Gradle package task не подтверждено. Kotlin AndroidTest был UP-TO-DATE, test APK отсутствует, его сборка не завершена.

Runtime тесты, установки APK, измерения key/index/offset/viewport/text и screenshots не выполнены; исторический +209 px не подтверждён. Production и защищённые тесты не менялись. Созданный emulator-5560 и собственный adb server 5038 остановлены, посторонний 5037 не трогался. Таймер plinth удалён по решению автора, не восстанавливался. Логи и AVD остаются в `/tmp/plinth-yu5f-ui-baseline-20261118/`.

## Завершение APK-сборки — 2026-11-19

Исполнитель: `gpt-6-luna`, `medium` (назначено координатором). Разрешённая команда
`bash ./gradlew :app:assembleGithubDebug :app:assembleGithubDebugAndroidTest --no-daemon --console=plain`
завершилась `BUILD SUCCESSFUL in 1m 14s`, 92 tasks: 11 executed / 81 UP-TO-DATE.
`:app:cargoNdkBuild` был включён и UP-TO-DATE; `uniffiKotlin` также включён и
UP-TO-DATE. Runtime/emulator/adb/phone не запускались.

Созданы оба APK: `app/build/outputs/apk/github/debug/app-github-debug.apk`
(35,100,011 байт; SHA-256 `a6526c2345ddbc0100eccd4f0c00793a7190513a255ef258fc4e96cba91e98d6`)
и `app/build/outputs/apk/androidTest/github/debug/app-github-debug-androidTest.apk`
(2,011,675 байт; SHA-256 `cb5839530ba59875e276883baaeae20aca79c8400cc39f4dab17ce494157032d`).
Оба прошли `apksigner verify` (debug V2 signer) и `zipalign -c -v 4`.
Release-grade signing не заявляется. Debug APK содержит `libplinth_ffi.so` для
armeabi-v7a, x86_64, arm64-v8a и x86. AndroidTest manifest содержит runner
`androidx.test.runner.AndroidJUnitRunner`, target `io.github.puflik.plinth.debug`.

Код, тесты, зависимости и защищённые пути не менялись; отчёт — единственный
изменённый tracked файл. Не проверены установка и runtime-тесты; сборка APK
не подтверждает их выполнение.

## Runtime baseline — попытка 2026-11-19

Исполнитель: `gpt-6.1-terra`, `high`, назначение координатора. Исходный HEAD `bdf9431682c49c3fbcd055d0350b2ecfbe123a65`, ветка `work/yu5f`; scope — этот отчёт и при необходимости отдельный новый UI test. Граф и production не изменялись.

Два запуска подготовленного эмулятора не состоялись: временный каталог `/tmp/plinth-yu5f-ui-baseline-20261118/avd` отсутствует. При повторе в управляемой foreground-сессии emulator завершился с exit 1 и `Unknown AVD name [Plinth_yu5f_api35]`, указав на отсутствующий `Plinth_yu5f_api35.ini`; диагностика `/tmp/plinth-yu5f-runtime-20261119/emulator-retry.log`. Первый фоновый запуск не оставил работающего emulator и не дал диагностики в пустом `emulator.log`. Это два отказа запуска отсутствующего AVD, а не результаты UI тестов.

Первый запрос adb также обнаружил отсутствие `adb` в PATH; исправлен явным SDK platform-tools. Ожидание устройства прервано после обнаружения отсутствующего эмулятора. Диагностический `adb -P 5038 devices` завис и завершён по PID; при финальной проверке собственных процессов emulator/qemu/adb не осталось. Посторонний adb 5037 и телефон не трогались.

Подтверждения qemu/boot для этого прохода нет; APK не устанавливались, тесты и измерения runtime не выполнялись. Ни ожидаемый RED UI, ни исторический +209 px, ни фактические key/index/offset/viewport/text или живой NavHost не подтверждены. Новые тесты не добавлялись, protected paths и production не менялись. Требуется восстановить отдельный AVD, затем повторить проверку serial/qemu/boot до установки и выполнить исходный runtime baseline. APK ранее собраны, как указано выше; новую сборку этот проход не запускал.

### Ограниченный runtime setup — 2026-11-19, остановлен после повторного сбоя

Исполнитель: `gpt-6-luna`, `medium` (фактическая роль этого этапа). Постоянный
AVD создан стандартным установленным `avdmanager`: имя `Plinth_yu5f_api35`,
каталог `/home/puflik/.android/avd/Plinth_yu5f_api35.avd`, config/INI в
`/home/puflik/.android/avd/`. Образ `system-images;android-35;google_apis;x86_64`,
профиль `pixel_2`. До создания `avdmanager list avd` сообщил только заголовок
`Available Android Virtual Devices:`; `/home/puflik/Android/Sdk/emulator/emulator
-list-avds` не вывел AVD; имя и ini проверены как свободные. Image directory
присутствовал. Создание завершилось сообщением `Auto-selecting single ABI x86_64`.
Чужие AVD не удалялись/перезаписывались, права и системные настройки не менялись.

Точные попытки запуска:
`/home/puflik/Android/Sdk/platform-tools/adb -P 5038 start-server; /home/puflik/Android/Sdk/emulator/emulator -avd Plinth_yu5f_api35 -port 5560 -no-snapshot-load -no-snapshot-save >app/build/yu5f-runtime-20261119/emulator.log 2>&1`
и после GUI failure единственная коррекция:
`/home/puflik/Android/Sdk/platform-tools/adb -P 5038 start-server; /home/puflik/Android/Sdk/emulator/emulator -avd Plinth_yu5f_api35 -port 5560 -no-window -no-snapshot-load -no-snapshot-save >app/build/yu5f-runtime-20261119/emulator.log 2>&1`.
Первый foreground повтор до коррекции был вызван с той же AVD/портом и без
`-no-window`; диагностический вывод сообщил `could not connect to display (:0)`,
Qt plugin `xcb` не загрузился и `Fatal: This application failed to start because
no Qt platform plugin could be initialized`. Сообщение указывало, что доступны
`minimal, offscreen, vnc, linuxfb, xcb`. Первый redirected запуск не оставил
лога: при последующем чтении файла его не существовало. Вторая foreground-сессия
с `-no-window` оставалась активна до контрольного вызова с `sleep 60`; за этим
вызовом tool сообщил завершение без вывода, а последующая проверка устройств и
лога также не вернула содержимого. Следовательно, elapsed наблюдения для второй
попытки — около 60 секунд; готовый serial не подтверждён. Цикл ожидания serial
на 120 итераций по 2 секунды (максимум 240 секунд) завершился tool без вывода;
фактическая длительность ожидания не была надёжно сообщена.

Команда старта отдельного adb: `/home/puflik/Android/Sdk/platform-tools/adb -P 5038 start-server`.
Проверка планировалась командами `adb -P 5038 devices`,
`adb -P 5038 -s emulator-5560 shell getprop ro.kernel.qemu` и
`adb -P 5038 -s emulator-5560 shell getprop sys.boot_completed`; результат
команд в tool output отсутствует. Установок APK, runner и instrumented test
не было. Файл `app/build/yu5f-runtime-20261119/emulator.log` не сохранился;
путь к запрошенному каталогу логов создавался, но наличие каталога/файла не
подтверждено в worktree. Точный текст GUI ошибки приведён выше; вторичный
`-no-window` error text отсутствует и не реконструируется.

После проверок `ps` не выявил собственного процесса emulator/adb с портом 5560
или 5038; вывод не позволяет заключить о наличии постороннего процесса.
Порт 5037 не проверялся и не затрагивался. Требуется новый диагностический
проход после устранения ограничения запуска; попытки этого этапа исчерпаны.

### Контролируемая диагностика headless — 2026-11-19, в работе

Исполнитель: фактически `gpt-6.1-sol`, `high`, узкое назначение координатора; HEAD `372819391230fcc8ec4fe9bd72e4b84bec289598`, `work/yu5f`. Разрешён только этот отчёт; исходники, protected tests, зависимости и Gradle не затрагиваются.

Host-проверка вне sandbox в 00:36 +05:00 подтвердила постоянный AVD `/home/puflik/.android/avd/Plinth_yu5f_api35.avd` и его INI; каталог логов предыдущего этапа отсутствовал. Emulator/qemu на host не обнаружен, но оставался adb PID 28100 (запущен 00:33:10), слушающий 127.0.0.1:5038. Следовательно, прежний вывод о полной уборке неверен. На 5037/5560/5561 слушателей не было. `/dev/kvm` существует; `emulator -accel-check`: exit 0, `KVM (version 12) is installed and usable`. Host RAM 7784MiB, available 4360MiB; AVD RAM 2G. Никакие permissions/kernel-настройки не менялись.

Создан и проверен абсолютный каталог `/home/puflik/src/Plinth-yu5f/app/build/yu5f-runtime-20261119`; начальные факты сохранены в `host-before.log`. Все дальнейшие stdout/stderr сохраняются туда. Отсутствие старых логов и host emulator не устанавливает точную причину старого headless отказа; его успешный запуск также не подтверждён.

Поддержка `ANDROID_ADB_SERVER_PORT` подтверждена локально `strings .../emulator/lib64/libandroid-emu-shared.so`. Контролируемый запуск: `ANDROID_ADB_SERVER_PORT=5038 ANDROID_HOME=/home/puflik/Android/Sdk ANDROID_SDK_ROOT=/home/puflik/Android/Sdk /home/puflik/Android/Sdk/emulator/emulator -avd Plinth_yu5f_api35 -port 5560 -no-window -no-snapshot-load -no-snapshot-save -memory 2048`, через Python `Popen` с абсолютным stdout/stderr `emulator.log`, `start_new_session=True`; PID 29310, через 12 секунд процесс жив. Команда/env/PID сохранены в `launch.json`/`emulator.pid`. Emulator сам повысил guest RAM до 2560MB; KVM/system checks успешны, программный рендеринг swangle/lavapipe выбран автоматически. Старт не встретил Qt DISPLAY fatal; строки `GLAsyncSwap` и audio warning не завершили процесс. Исходная причина старого headless отказа без логов остаётся неизвестной; старое применение `adb -P5038` без emulator env не доказывало подключение эмулятора к отдельному серверу.

Boot подтверждён на `adb -P 5038 -s emulator-5560`: `ro.kernel.qemu=1` и `sys.boot_completed=1` до любых установок, `boot-ready.json`/`boot-check.log`. Первый cold boot занял около 2–3 минут; ранние пустые `sys.boot_completed` были ожиданием, перезапуска/коррекции не было. Guest logcat показывает продвижение SystemServer boot phases; предупреждение modem socket timeout не остановило boot.

Существующие APK установлены без пересборки командами `adb -P 5038 -s emulator-5560 install -r <абсолютный APK>`: app и androidTest оба exit 0/Success (`install-app.log/.exit`, `install-test.log/.exit`). Test package прочитан через build-tools/37.0.0/aapt: `io.github.puflik.plinth.debug.test`; `pm list instrumentation` подтвердил runner `io.github.puflik.plinth.debug.test/androidx.test.runner.AndroidJUnitRunner` и target `io.github.puflik.plinth.debug`. Однократно запущен `adb -P 5038 -s emulator-5560 shell am instrument -w -r -e class io.github.puflik.plinth.ui.search.Yu5fSearchSectionsScreenTest io.github.puflik.plinth.debug.test/androidx.test.runner.AndroidJUnitRunner`; точный argv в `instrument-command.json`, stdout/stderr в `instrumentation.log`, параллельный threadtime logcat в `logcat-live.log`. Результат ожидается; timeout runner 180 секунд.

**Итог runtime: BLOCKED до UI assertions, не ожидаемый RED UI.** `am instrument` завершился host exit **0**, но stdout содержит только `INSTRUMENTATION_RESULT: shortMsg=Process crashed.` и `INSTRUMENTATION_CODE: 0`. Test status/start/end и итоговое число runner отсутствуют: фактически подтверждено **0 начатых / 0 завершённых тестов**; успешный shell exit не означает PASS. `instrumentation.exit` сохраняет реальный exit 0. Повторного instrumented запуска не было.

Точная наблюдаемая причина завершения процесса — startup ANR: `logcat-live.log:39369` — `ANR in io.github.puflik.plinth.debug`; `:39371` — `Reason: Process ProcessRecord{c3d0843 2086:io.github.puflik.plinth.debug/u0a209} failed to complete startup`; `:39420` — `Killing 2086:io.github.puflik.plinth.debug/u0a209 (adj 0): bg anr`; `:39599` — `Crash of app ... running instrumentation ComponentInfo{io.github.puflik.plinth.debug.test/androidx.test.runner.AndroidJUnitRunner}`. Время ANR 00:40:07.679 +05:00, завершение instrumentation 00:40:08.289. Релевантный trace с номерами строк сохранён отдельно в `runtime-result-excerpt.log`; весь threadtime logcat — 39626 строк в `logcat-live.log`. `adb -P 5038 -s emulator-5560 logcat -b crash -d -v threadtime` сохранён в `crash.log` и пуст: native crash/Java fatal **не установлены**, `tombstoned` здесь обслуживал Java ANR backtrace. Причина задержки startup (включая возможную нагрузку первого boot) этим проходом не доказана. По назначению координатора при неоднозначности — остановка/handoff, без исходников, пересборки, новых тестов или повторов.

Уборка подтверждена **на host вне sandbox**: `adb -P 5038 -s emulator-5560 emu kill` exit 0 (`OK: killing emulator, bye bye`), затем `adb -P 5038 kill-server` exit 0. Через 3 секунды `ps -p 29310,28100 -o user,pid,ppid,stat,args` exit 1 и только заголовок, `ss -ltnp '( sport = :5037 or sport = :5038 or sport = :5560 or sport = :5561 )'` без слушателей. Всё сохранено в `cleanup.log`. Чужой 5037/телефон не изменялись, не запускались и не опрашивались adb-командами. Постоянный AVD оставлен в `/home/puflik/.android/avd/Plinth_yu5f_api35.avd`; новых постоянных AVD не создавалось.

Не проверены UI assertions, key/index/offset/viewport/text, NavHost, возврат/mini-player и критерии приёмки yu5f. Дополнительные JVM/Gradle/CI проверки не запускались: изменён только этот отчёт. Защищённые 14 файлов, production, зависимости сохранены; граф не обновлялся. Сырые runtime logs находятся в игнорируемом `app/build/yu5f-runtime-20261119` локального worktree и не входят в Git; при очистке build они будут утрачены. Следующий этап требует отдельного решения координатора по startup ANR, этот проход не устанавливает исправление.

Handoff запуска: target/app process — `io.github.puflik.plinth.debug`, UID u0a209, PID 2086; test package `io.github.puflik.plinth.debug.test`. `logcat-live.log:26349`: Start proc в **00:39:38.462**; ANR в **00:40:07.679**, через **29.217s** после Start proc. ErrorId `0292374d-c65c-4f29-9e0b-7ca8648d47ff`. В сохранённом ANR блоке: guest memory PSI `some avg10=10.50 avg60=6.62 avg300=1.96`, `full avg10=0.12 avg60=0.10 avg300=0.02`; guest CPU PSI `some avg10=91.81 avg60=83.25 avg300=40.02`; IO PSI `some avg10=10.20 avg60=14.08 avg300=7.96`, `full avg10=0.00 avg60=0.29 avg300=0.23`. CPU usage за 00:39:57.148–00:40:07.452: system_server 33%, launcher 22%, app PID2086 9.4% (8.3% user + 1% kernel; 10398 minor/2 major faults), TOTAL100% (65%user/32%kernel). Эти наблюдения не доказывают причину ANR.

Main-thread stack и файл `/data/anr` **не получены**: сохранённый logcat содержит только факт `Wrote stack traces to tombstoned`/перехвата Java backtrace; сам stack в нём отсутствует. После cleanup новых запросов/запусков не делалось. Ни SearchScreen, ни native не объявляются причиной. **Runtime UI не принят**; дальнейшая диагностика app startup требует отдельного назначения. Проверка `git diff --check` PASS; перед коммитом diff относительно входного HEAD содержит только `docs/work/yu5f.md`, source/tests не изменены.

### Получение ANR trace — 2026-11-19, в работе

Фактическая модель `gpt-6.1-sol`, effort `high`; назначен один ограниченный диагностический этап на `work/yu5f`, входной HEAD `0e922f827b6fd6910a7b4a627aa8a06961cec987`. Scope tracked — только этот отчёт; без production изменений, пересборок и изменения protected tests. Сначала проверяется сохранённый trace нашего target в постоянном AVD; один повтор instrumentation разрешён только при отсутствии trace. Логи — абсолютный `/home/puflik/src/Plinth-yu5f/app/build/yu5f-anr-20261119`. Первая запись отчёта внутри sandbox получила EROFS; последующие записи/host tools выполняются с явной эскалацией для назначенного соседнего worktree.

**Итог: BLOCKED на host setup, до подтверждения guest и чтения ANR.** На host до запуска отсутствовали listener 5038/5560/5561 и qemu/adb5038. Запущен прежний постоянный AVD тем же argv/env из предыдущего отчёта: PID **72187**, `ANDROID_ADB_SERVER_PORT=5038`, `-no-window -no-snapshot-load -no-snapshot-save -memory 2048`. Host `ps` подтвердил живой `qemu-system-x86_64-headless` с ожидаемым AVD/argv, `ss` — его listeners 5560/5561; 5038 listener отсутствовал. Эмулятор пишет `Emulator is performing a full startup`; Qt fatal отсутствует, но завершение boot не установлено.

Первый запрос `adb -P 5038 -s emulator-5560 shell getprop ro.serialno` завис и был завершён Python `subprocess.run(timeout=12)` с `TimeoutExpired` (обёртка exit 1). До `ro.kernel.qemu`/`sys.boot_completed` цикл не дошёл; serial/qemu/boot **не подтверждены**. Единственная корректировка setup — явный `adb -P 5038 start-server` — также получила `TimeoutExpired` через 25 секунд (обёртка exit 1). После повторного отказа этап остановлен; новых setup поправок, root, чтения `/data/anr`, instrumentation и сборки не было. Пустой `boot-check.log` отражает отсутствие первого завершённого запроса; точные команды/таймауты сохранены в `probe-results.json`, текст второго timeout в `adb-start.log`. Частичные stdout/stderr adb не наблюдались; внутренний exit adb при принудительном timeout не установлен. Задержка adb/start-server не доказывает причину прежнего startup ANR.

Host cleanup выполнен после остановки: SIGTERM только собственного PID72187, затем через 4 секунды `ps -p 72187 ...` exit1/только заголовок; `ss` по 5038/5560/5561 без listeners; итоговый host `ps` не содержит adb5038. Собственный adb5038 в `ps` до cleanup не обнаружен, поэтому adb kill-server не вызывался. Чужой5037/телефон не опрашивались и не изменялись. AVD не пересоздан, wipe-data/hardware/таймауты runner не менялись; userdata сохраняется. Проверка собственного AVD внутри guest так и не состоялась, поэтому никаких привилегированных guest действий не выполнялось.

Локальный архив **`/home/puflik/src/Plinth/artifacts/yu5f-anr-20261119.tar.gz`**, SHA-256 **`b6f8fa8cbc63108405e37e97a195e84b68095da4e7d49d0446034b28d83aa4aa`**, создан эксклюзивно без перезаписи, не добавляется в Git; включает launch/env/PID, emulator output, host before/setup/cleanup и probe results. Логи исходно в `/home/puflik/src/Plinth-yu5f/app/build/yu5f-anr-20261119`. Main-thread ANR stack/timestamp trace не получены; прежний ErrorId `0292374d-c65c-4f29-9e0b-7ca8648d47ff` остаётся единственным подтверждённым из предыдущего этапа. **0 новых instrumentation запусков / 0 тестов**; runtime UI acceptance не заявляется. Следующий шаг для координатора: отдельное ограниченное host-исследование зависания adb5038/start-server до повторного guest доступа, затем сохранённый `/data/anr` только нашего target; production причину по текущим данным назначить нельзя.

Проверки перед коммитом: `git diff --check` PASS; diff всех `app/src/test`, `app/src/sharedTest`, `app/src/androidTest` относительно `b52b256eae8486e6fb92c7094039460740755b25` пуст (включая14 protected paths). Diff относительно входного HEAD содержит только `docs/work/yu5f.md`; production/alltests не изменены. Gradle/JVM/CI в этом диагностическом этапе не запускались; граф не обновлялся. Push выполняет координатор; merge/rebase/release не выполняются.

Уточнение handoff координатора: следующий отдельный этап — диагностика host ADB5038 **без запуска эмулятора и тестов**; очередной boot до выяснения транспорта не назначается. Для обоих adb subprocess окружение наследовалось от host exec без env overrides; порт выбран явно аргументами `-P 5038`, serial в первом probe — `-s emulator-5560`. Полный inherited env не снимался и не реконструируется. Явные `ANDROID_ADB_SERVER_PORT`/SDK env в `launch.json` относятся только к emulator Popen. Никаких выводов о причинах задержки Android/app не сделано.


### Host ADB5038 — 2026-11-20, в работе

Назначена и фактически используется `gpt-6.1-sol`, effort `high`; один узкий host-only этап, входной HEAD `cef4e544cd3f2f88a0d7910386bfe1d7b4c0c802`. Scope tracked — только этот отчёт. Эмулятор, guest/device commands, builds/tests не запускаются; чужой5037 не изменяется. Сырые argv/exits/stdout/stderr сохраняются в абсолютный `/home/puflik/src/Plinth-yu5f/app/build/yu5f-adb-20261120`.

Первый host baseline обнаружил уже существующий listener `127.0.0.1:5038`, PID73213 (`adb`); поэтому запуск нового сервера запрещён до установления владельца и освобождения порта. `adb version/help` exit0 за <0.01s, SDK37.0.1; релевантных inherited ADB/ANDROID env нет; `/usr/bin/strace` доступен. Чужие процессы не остановлены.

**Итог: BLOCKED_OCCUPIED; причина прежнего timeout не установлена.** Уже до собственных запусков host `ss -ltnp` показал `127.0.0.1:5038`, владелец PID73213. Уточняющий `pgrep -a -x adb` и `ps -p 73213` подтвердили единственный adb, PPID1/UID1000, `adb -L tcp:5038 fork-server server --reply-fd 4`, exe `/home/puflik/Android/Sdk/platform-tools/adb`, cwd `/home/puflik/src/Plinth-yu5f`, wchan `ep_poll`; stdout/stderr направлены в `/tmp/adb.1000.log`. Первичный path-only фильтр ps не находил basename `adb`; он не доказывает отсутствия процесса, атрибуция основана на последующем прямом ps/pgrep и socket owner.

Daemon стартовал по host ps `Thu Nov 19 20:58:33 2026`, позже прежнего cleanup.log mtime `2026-11-19T20:56:21+05:00` и probe-results.json `20:57:05+05:00`; совпадение SDK/cwd не подтверждает, кем выполнен этот более поздний запуск. Текущие наблюдения датированы host `2026-11-20` около12:00+05; это фактическое время диагностики, не время исходного heartbeat01:56. На этапе не получено provenance, разрешающего объявить PID73213 собственным. Его не убивали и не заменяли; foreground launch/ADB_TRACE/strace собственного сервера не выполнены, поскольку порт5038 уже занят и сервер не атрибутирован.

`adb version` и `adb help` exit0 за0.003/0.002s; локальный help подтверждает `-P`, `-L SOCKET`, `server nodaemon`, `ADB_TRACE`. Релевантные inherited переменные `ADB_*`, `ANDROID_ADB_SERVER_*`, `ANDROID_SERIAL`, SDK env отсутствуют. Read-only **host** `adb -P 5038 server-status` exit0 за**0.037s**, без stderr: сервер37.0.1-15733141, NATIVE USB, LIBADBMDNS, ожидаемый executable. Ни один device/guest запрос не отправлен. Текущее состояние daemon отвечает; прежний startup hang не воспроизведён и не объяснён. Это не данные о причине app ANR, core/SearchScreen или hardware.

Собственных host server/дочерних фоновых процессов создано **0**; финальные `pgrep`/`ps`/`ss` подтверждают сохранённый PID73213/listener5038, отсутствие5037/5560/5561 listeners. Чужой5037/телефон не опрашивались и не изменялись. Эмулятор/AVD/guest/tests/builds не запускались; SDK, USB/kernel/groups/permissions и userdata не менялись; никаких попыток коррекции startup не было. Следующий разрешённый шаг требует атрибуции существующего PID73213 либо наблюдения свободного5038; после этого отдельный bounded foreground `ADB_TRACE` и host-only status/stop своего сервера. Если старт зависнет — установлен `/usr/bin/strace`, trace только собственного PID, без установки пакетов.

Архив **`/home/puflik/src/Plinth/artifacts/yu5f-adb-20261120.tar.gz`**, SHA-256 **`977bf3007e2d19a5edbd6a11efca2fe7bb99d00049ae86ac41053ca7c34f5381`**, создан эксклюзивно, не добавляется в Git; argv/exits/тайминги/логи и host-final сохранены в указанном абсолютном logdir. Handoff координатору — `/tmp/plinth-adb-notes-20261120.md`. Граф не обновлялся. Production/deps/tests и14 protected paths не менялись; CI/Gradle/JVM не запускались.

**Продолжение после атрибуции координатором:** выделение5038 только нашей диагностике, совпадение SDK/cwd/argv/даты и отсутствие иных поручений признаны достаточными для обслуживания PID73213; точный прежний parent не утверждается. Предварительный BLOCKED_OCCUPIED выше больше не итог этапа. Разрешены штатный kill-server только5038, один foreground запуск/status/cleanup. Первый архив уже создан до уточнения; сохраняется без перезаписи, финальные логи получат отдельный дополнительный архив.

**Окончательный итог: host-only foreground lifecycle PASS; прежняя причина start-server timeout остаётся неизвестной.** После подтверждения координатора штатный `adb -P 5038 kill-server` завершился exit0 за0.098s; host `ss` подтвердил свободный5038. Выполнен **один** foreground запуск `/home/puflik/Android/Sdk/platform-tools/adb -L tcp:localhost:5038 server nodaemon`, PID135259, stdout/stderr направлены напрямую в файлы (не pipe), per-invocation `ADB_TRACE=adb,sockets,transport,services,sysdeps`, `ADB_MDNS_AUTO_CONNECT=`. Через2s host `ss` подтвердил `127.0.0.1:5038` с тем же PID; `ps` — ожидаемый foreground argv. `adb -P 5038 server-status` exit0 за**0.088s**, ожидаемые SDK37.0.1 и trace_level. Это проверенный способ поднять/проверить/остановить свой host сервер; сравнения параметров не выполнялись, поэтому ни listen syntax, ни env override не объявляются устранённой причиной прежнего зависания.

Cleanup штатным `adb -P 5038 kill-server` exit0 за0.086s, foreground child дождались exit0. Финальный host `pgrep -a -x adb` exit1/пусто; `ss -ltnp` — **нет5037/5038/5560/5561 listeners**. Чужой5037 не опрашивали/не останавливали, собственных child/server процессов не осталось. Старт не зависал, поэтому strace/исправления/повторные старты не понадобились. Ни эмулятора, ни guest/device queries, ни tests/builds не было; hardware/app причина не назначается. Наблюдавшийся старый daemon появился позже прежнего cleanup; это отдельный факт состояния host, не доказательство причины прежнего timeout.

Финальный полный дополнительный архив **`/home/puflik/src/Plinth/artifacts/yu5f-adb-20261120-completed.tar.gz`**, SHA-256 **`f9a667e0e26ca532c562f023950b697dd074669f920daaac37ecb799550f5c91`**, создан эксклюзивно и не добавляется в Git; первоначальный `yu5f-adb-20261120.tar.gz` сохранён без перезаписи как промежуточный снимок до атрибуции. Использовать для handoff **completed** архив и `foreground-results.json`; более ранние `host-final.json`/BLOCKED описывают промежуточное состояние. Следующий шаг координатора — отдельное разрешённое продолжение ANR/guest после host preflight; данное выполнение не включает его.

Финальные проверки: `git diff --check` PASS; изменён только `docs/work/yu5f.md` относительно входного HEAD; diff всех app/src/test, app/src/sharedTest, app/src/androidTest относительно `b52b256eae8486e6fb92c7094039460740755b25` пуст (14 protected paths сохранены). CI/Gradle/JVM не выполнялись. Commit только `work/yu5f`, push — координатор; без main/merge/rebase/release.


### Сохранённый ANR собственного AVD — 2026-11-20, в работе

Назначена и фактически используется `gpt-6.1-sol`, effort `high`; входной HEAD `4a0cc159b1795be917df128b38aa3c32b9202d12`. Один узкий этап чтения уже сохранённого startup ANR target `io.github.puflik.plinth.debug` (прежний PID2086, 2026-11-19 00:40:07.679+05, ErrorId `0292374d-c65c-4f29-9e0b-7ca8648d47ff`). Scope tracked — только этот отчёт; без instrumentation, воспроизведения, сборок и исходников. Собственный существующий AVD сохраняет userdata, без wipe/recreation/hardware edits. Логи: `/home/puflik/src/Plinth-yu5f/app/build/yu5f-saved-anr-20261120`; порты5038/5560/5561, чужой5037/телефон не опрашиваются.

Foreground adb PID136766/5038 и emulator PID136777/5560 подняты с прямыми файлами stdout/stderr. Перед guest-доступом подтверждены `serial=emulator-5560`, `ro.kernel.qemu=1`, `sys.boot_completed=1`; первые пустые boot properties были ожиданием cold boot, без перезапуска/исправления. `adb root` выполнен только после идентификации своего AVD; после рестарта повторно проверены serial/qemu/boot и uid0. Метаданные `/data/anr` сохранены адресно; содержимое ещё не интерпретировано.

**Наблюдение: сохранённый target trace не найден.** `/data/anr` содержит только `anr_2026-11-19-20-55-55-401` (0 bytes) и `anr_2026-11-20-12-09-16-414` (276030 bytes). Точная проверка каждого файла по строке `Cmd line: io.github.puflik.plinth.debug` завершилась exit0, без совпадений и stderr; содержимое чужих стеков не извлекалось. Старого trace PID2086/00:40:07/указанного ErrorId нет среди сохранённых файлов. Main-thread state/stack/frames не получены; данных для причины в SearchScreen/native/hardware нет. Новая instrumentation/reproduction/build не выполнялись.

**Итог этапа: NO_SAVED_TARGET_TRACE; transport/доступ к `/data/anr` PASS, stack остаётся недоступным.** Новый файл с датой текущего boot не выдаётся за исходный ANR; почему прежний target trace отсутствует, не установлено. Коррекция setup не понадобилась; повторного boot/root/instrumentation не выполнялось. Следующее решение координатора — отдельно запланировать контролируемый startup/target-only trace capture с новым бюджетом, если требуется причина; этот этап нового воспроизведения не разрешает.

Cleanup: `emu kill` exit0 за0.146s; дождались emulator/netsimd/детей, затем `adb -P 5038 kill-server` exit0 за0.065s и выхода foreground adb. Финальная host-проверка собственных PID/process groups пуста, listeners5038/5560/5561 отсутствуют (`cleanup-results.json`). Чужой5037/телефон не опрашивались и не изменялись; живых собственных child/pipe процессов не осталось. Ноутбук не усыплялся, таймеры/агенты не создавались.

Эксклюзивно создан локальный архив **`/home/puflik/src/Plinth/artifacts/yu5f-saved-anr-20261120.tar.gz`**, SHA-256 **`e011b4bb71e2aa5ee6d53739aad82e8624cf192871260ef97649c0ab08d09a72`**, 62 entries; не перезаписан и не добавлен в Git. Абсолютные логи — указанный logdir; краткий handoff — `/tmp/plinth-saved-anr-notes-20261120.md`. Архив содержит argv/exits/timings, boot/root/uid проверки, file metadata/target selection, cleanup и verification; полного bugreport/чужих стеков нет.

Проверки: `git diff --check` PASS; diff всех `app/src/test`, `app/src/sharedTest`, `app/src/androidTest` относительно `b52b256eae8486e6fb92c7094039460740755b25` пуст, включая14 protected paths. От входного HEAD изменён только `docs/work/yu5f.md`; production/dependencies/tests не менялись. CI/Gradle/JVM не запускались, граф не обновлялся. Commit только `work/yu5f`, push выполняет координатор; main/merge/rebase/release не выполнялись.


### Контролируемый startup/ANR capture — 2026-11-20, в работе

Назначена и фактически используется `gpt-6.1-sol`, effort `high`; входной HEAD `2e3ce4f341c0f339497d6243966029786c33f635`. Разрешён один новый instrumented class run с непрерывным target/logcat capture до cleanup, без пересборки или правок production/tests/deps. Scope tracked — только этот отчёт; deadline исполнителя 07:42:52 UTC. Логи `/home/puflik/src/Plinth-yu5f/app/build/yu5f-fresh-anr-20261120`. Host preflight: 5038/5560/5561 свободны, qemu/adb5038 не обнаружены. Protected baseline `b52b256eae8486e6fb92c7094039460740755b25` сохраняется.

07:23:12 UTC подтверждены boot_completed=1/bootanim=stopped/qemu=1; после adb root повторно serial=emulator-5560/qemu=1/boot=1/uid=0. Foreground ADB PID139817, qemu PID139851. Предстартовое наблюдение CPU/memory16сек; последний sample CPUidle5%, MemFree105944kB, swap347904kB (наблюдения, не причина). `debuggerd --help` подтвердил `-j collect java traces`; оболочка kill --help вернула unsupported option, сигнала не посылала. Continuous logcat PID141061 начат07:24:43.836UTC; единственный classrun стартовал07:24:44.110UTC, hostPID141064. Если runner не начнёт тесты, поддерживаемый debuggerd-j только targetPID заменяет ранее намеченный SIGQUIT; диагностическое влияние будет отмечено.

Runner реально начал6 UI tests (targetPID3488), старт первого TestRunner12:24:54.342+05. StartupANR пока не повторился; debuggerd/SIGQUIT не вызывались. Уже получены реальные assertion failures: show_all missing search-show-all-archive.org (test110), retry missing search-retry-youtube (test161), overview шестая строка существует (ожидался AssertionError), expanded missing show-all (test137); first_and_second_player_returns_keep_the_same_visible_song_offset завершён PASS. По graphify query budget900 и адресному чтению текущего SearchScreen.kt170–188 код ещё выводит items(section.results), не take(5), и не содержит show-all/retry test tags. Это наблюдение о текущем UI RED baseline, не объяснение прежнего ANR; исходники не изменены.

**Итог: реальный UI RED baseline получен; startup ANR в новом run не повторился.** Один classrun, targetPID3488; runner начал6 и завершил6 tests, **2 PASS / 4 FAIL**, `Tests run: 6, Failures: 4`, `INSTRUMENTATION_CODE: -1`. Host exit0 не является PASS. Время команды и последующего bounded capture: 07:24:44.110–07:26:57.964 UTC,133.854сек; после этого target-only ANR selection и final guest observation закончены07:27:02.138 UTC, до cleanup. Continuous logcat начат07:24:43.836 UTC, остановлен только при cleanup. Внешний360сек предел не достигнут. Локальные debuggerd/SIGQUIT, тестовые таймауты/guards не применялись и не менялись.

- FAIL `show_all_opens_the_remaining_rows_without_asking_the_network`: отсутствует tag `search-show-all-archive.org`, `Yu5fSearchSectionsScreenTest.kt:110`; container44rows, scroll17512/max17512. До проверки сетевых повторов тест не дошёл.
- FAIL `retry_of_youtube_does_not_repeat_the_completed_archive_search`: отсутствует tag `search-retry-youtube`, test:161; provider retry/assertions о числе запросов ещё не проверены.
- FAIL `overview_contains_five_rows_per_section_and_no_sixth_row`: ожидался AssertionError при поиске шестой строки, ничего не выброшено, test:102; ограничение пяти строк отсутствует.
- FAIL `expanded_section_survives_player_return_and_resets_for_a_new_query`: отсутствует show-all tag, test:137; проверки раскрытия/сброса после нового query ещё не достигнуты.
- PASS `first_and_second_player_returns_keep_the_same_visible_song_offset` и `five_results_have_no_show_all_button` (реальные CODE0, не только TestRunner start).

Адресное чтение после `graphify query ... --budget 900` из main подтвердило конкретный следующий шаг: текущий `SearchScreen.kt:180` выводит весь `section.results`, а `:157/:170` принимает retry callback без provider; show-all/retry tags в этом package отсутствуют. Четыре падения согласуются с ещё не реализованным UI этапом из задачи. **Далее — назначить реализацию UI: отдельные секции, take(5)/inline show-all, адресный retry и сохранение состояния согласно существующему плану; protected tests сохраняются.** Этот этап самовольно source не исправлял и второй instrumented run не выполнял. PASS двух fixtures не подтверждает весь NavHost/mini-player/viewport acceptance и не закрывает yu5f.

Logcat не содержит `ANR in io.github.puflik.plinth.debug`; target-only `/data/anr` selection перед shutdown не нашёл секции target. Метаданные иных файлов сохранены без извлечения их стеков. Main-thread dump не понадобился, потому что runner начал и завершил реальные assertions; причина старого startup ANR остаётся неизвестной, выводов о native/hardware/SearchScreen как его причине нет. Финальный diagnostic `dumpsys activity instrumentation` вернул Unknown command, окружённый успешными date/ls (общий shell exit0); он не используется как доказательство результата, который установлен runner status-кодами.

Cleanup: own `emu kill`07:27:40.105 UTC exit0, own logcatPGID141061 SIGTERM, `adb -P5038 kill-server` exit0; shutdown emulator был асинхронным, поэтому первый immediate ps ещё содержал qemu/netsimd. Финальный **host**07:28:01.430 UTC: собственные process groups139817/139851/141061/141064 пусты, listeners5038/5560/5561 отсутствуют. Чужой5037/телефон не опрашивались и не изменялись, userdata/AVD config сохранены; ноутбук не усыплялся, таймеров и новых агентов нет.

Эксклюзивно создан локальный архив **`/home/puflik/src/Plinth/artifacts/yu5f-fresh-anr-20261120.tar.gz`**, SHA-256 **`74f790cb9a3c07c65aba1128695fbcf2377c7002ad7da3b8a5900814cd8df2f0`**, 375 entries; не перезаписан и не добавляется в Git. Включает команды/тайминги/status, continuous logcat, target process/resource observations, target-only ANR selection, runner assertions, scripts и host cleanup/verification. Handoff `/tmp/plinth-fresh-anr-notes-20261120.md`.

Проверки: `git diff --check` PASS; diff всех `app/src/test`, `app/src/sharedTest`, `app/src/androidTest` относительно `b52b256eae8486e6fb92c7094039460740755b25` пуст, включая14 protected paths. От входного HEAD изменён только `docs/work/yu5f.md`; production/tests/deps сохранены. Проверка runtime RED зафиксирована без source repair, потому что разрешён один диагностический run и только отчёт; повторов не было. Gradle/JVM/CI не запускались; граф не обновлялся. Commit только work/yu5f, push координатору, без main/merge/rebase/release.

### Ограниченный UI этап после реального RED — 2026-11-20

Координатор: автор разрешил ровно одного Terra сверх порога primary65%; последнее окно85%/76%. Реальный baseline861218b:6tests,2PASS/4assertionFAIL; новый startupANR не повторился. Разрешено реализовать уже принятое поведение из раздела «Пять строк и Показать все»: только SearchScreen.kt, values/strings.xml, values-ru/strings.xml (только Show all), собственный отчёт. Protected14/alltests/VM/dependencies не менять. В обзоре5строк на provider; Show all лишьпри>5, раскрывает cached results inline отдельно для provider и trimmed query через rememberSaveable; сохраняется при возврате, сбрасывается новым query. Теги search-show-all-<provider> и search-retry-<provider>; retry передаёт ID в существующий VM overload. Соседняя секция/запросы/OnlineSongs actions не меняются. Сохранить существующий успешный scroll guard, не чинить непроверенный NavHost/mini-player. Эта реализация не снимает оставшуюся живую UI/scroll приёмку и независимое Astra review.

Жёсткий предел: исполнитель заканчивает код/проверки/cleanup/report/commit до07:43:30UTC, root закрывает работу до07:46:30UTC, раньше пользовательских30минут. Никаких таймеров/сна/новых агентов. По возможности полные ktlint/detekt, нужные APK и тот же instrumented class; непроведённые проверки явно записать, PASS не выдумывать. Одна коррекция неожиданного падения, затем стоп/отчёт. Логи сохранять; при приближении срока сначала сохранить работу и завершить процессы. Код не сливать в main без независимого ревью/CI/оставшейся приёмки.

Исполнитель: фактически `gpt-6.1-terra`, `high`. Реализованы пять строк, раскрытие кэша по провайдеру и trimmed query, теги и адресный retry; проверки выполняются.

### Отдельный форматирующий этап — 2026-11-20, вечер

Автор новым «продолжай» возобновил работу после двух падений предыдущего исполнителя; его лимит коррекций исчерпан и не переигрывается. API после одного повтора12%/80%. Новый узкий этап Luna medium: сначала сохранить существующие изменения Terra как checkpoint с честным статусом NOT VERIFIED; затем исправить только ktlint multiline-expression-wrapping у ветви OnlineResults.Found в SearchScreen.kt:171, не меняя токены/поведение и не трогая tests/VM/другиефайлы. Разрешены SearchScreen.kt и собственный отчёт; существующие изменения двух strings.xml сохранить в checkpoint. Далее полные ktlintCheck/detekt и обе assemble APK без отключения native/UniFFI, с логами и проверкой защиты. Runtime этому исполнителю не поручен. Одна коррекция неожиданного сбоя, второе падение стоп/отчёт; нормальную остановку без сохранения не повторять. Следующий UI runtime назначит координатор по свежим лимитам. В текущем ручном этапе нет таймеров и сна; ориентир общего завершения до13:46:30UTC, не позже30минут от нового входа13:17:32UTC.

#### Отчёт форматирующего этапа — 2026-11-20

Исполнитель: `gpt-6.2-sol`, medium. До правки создан checkpoint коммит `5446cff` со всеми четырьмя уже изменёнными файлами; на том моменте они имели статус NOT VERIFIED. В исходном UI этапе Terra были две ошибки: сначала MaxLineLength, затем multiline-expression-wrapping. Исправлен только перенос `OnlineResults.Found` и его whitespace-отступы; поведение и тесты не менялись.

Первый полный запуск остановился на ktlint indent после первичного переноса. Использована одна разрешённая коррекция отступов; повторный полный `ktlintCheck detekt :app:assembleGithubDebug :app:assembleGithubDebugAndroidTest` завершился успешно (113 задач, 15 выполнены). Защищённые `app/src/test`, `app/src/sharedTest`, `app/src/androidTest` совпадают с baseline `b52b256eae8486e6fb92c7094039460740755b25`. Emulator/runtime не запускался.

APK Github Debug SHA-256 `d5556dffffd437322a4dc52c905a9307b3b134edffcb2f339571e5a6fb960f02`, mtime 2026-11-20 18:25:54 +0500. AndroidTest APK SHA-256 `cb5839530ba59875e276883baaeae20aca79c8400cc39f4dab17ce494157032d`, mtime 2026-11-19 00:22:07 +0500 (сборка UP-TO-DATE). Итог не подтверждает runtime/UI приёмку.

### Проверка исправленного UI — 2026-11-20, вечер

Координатор назначает один ограниченный runtime этап после ca6b73a: style/APK PASS, protected tree пустой diff относительно b52b256eae8486e6fb92c7094039460740755b25. Scope исполнителя Terra — только отчёт в этом файле и локальные логи; никаких production/tests/VM/deps изменений. Перед назначением API32%/83%. Deadline исполнителя13:40UTC, всего ручного этапа13:46:30UTC.

Один запуск готового instrumented class io.github.puflik.plinth.ui.search.Yu5fSearchSectionsScreenTest через AndroidJUnitRunner. Использовать только существующий AVD Plinth_yu5f_api35, dedicated adb5038, console5560/serial emulator-5560, host escalation. Чужой adb5037/телефон не трогать; userdata не очищать, AVD не пересоздавать. Проверить до запуска отсутствие собственных процессов/занятых5038/5560/5561; foreground adb с логами напрямую в файлы, не PIPE; emulator headless no-snapshot-load/no-snapshot-save. Проверить serial/qemu=1/boot_completed=1/bootanim stopped перед install-r двух APK. Gradle одновременно с emulator не запускать. ADB_TRACE и ANDROID_ADB_SERVER_PORT=5038 по проверенному рецепту /tmp/yu5f_fresh_diag.py; этот старый script не запускать напрямую — иначе перезапишутся прежние доказательства. Новые логи app/build/yu5f-ui-green-20261120. Не собирать debuggerd/SIGQUIT без необходимости: проверяются реальные UI assertions. Запуск ограничить360сек, не менять timeout тестов. Считать реальные начатые/завершённые tests и failures; host exit0 не равен PASS. Если run не стартует или падает, сохранить честные logs, без правки кода/повторных запусков.

Cleanup обязательно: только свой emulator/logcat/adb5038, дождаться собственного qemu/netsimd завершения и подтвердить host ps/ss5038/5560/5561. Не считать один ответ emu kill доказательством cleanup. Записать APK SHA, время, счёт tests, точные fail assertions либо6PASS, status protection, ограничения harness vs live NavHost. Прогресс сразу в /tmp/plinth-yu5f-ui-green-20261120.md; отчёт в taskfile закоммитить в work/yu5f, root публикует. Никаких таймеров/сна/новых агентов.

Исполнитель: назначена роль `plinth_ui_executor` (`gpt-6.1-terra`/`high` по конфигурации); независимое runtime-подтверждение модели недоступно. Входной HEAD `ca6b73a913549de415505c951d891b35ccd7c524`. Host baseline: 5038/5560/5561 свободны, собственных adb/qemu/netsimd нет; оба SHA-256 APK совпадают с указанными выше. В 13:28:51 UTC запущены отдельный foreground adb5038 и headless AVD; проверка продолжается, лог `app/build/yu5f-ui-green-20261120`.

Первая проверка готовности остановлена до install: ошибка экранирования `adb shell sh -c` вывела все свойства вместо четырёх отдельных значений; первая пара adb/emulator завершена, host `ps` и `ss` в 13:30:21 UTC подтвердили отсутствие собственных процессов и listeners 5038/5560/5561. Координатор разрешил одну коррекцию preflight и повтор старта собственного AVD: instrumentation ещё не запускалась. В 13:31:01 UTC повторно запущены foreground adb и тот же AVD; отдельные команды свойств и логи находятся в `attempt2`.

Исправленный preflight подтвердил `emulator-5560` как device, `ro.kernel.qemu=1`, `sys.boot_completed=1`, `bootanim=stopped` до установки; обе `install -r` завершились exit0/Success. Единственный instrumentation начат после 13:32:51 UTC, stdout и logcat сохраняются; ожидание продолжается.

**Итог runtime: FAIL до UI assertions.** Один `am instrument` 13:32:51.638–13:33:26.337 UTC вернул host exit0, но только `INSTRUMENTATION_RESULT: shortMsg=Process crashed.` и `INSTRUMENTATION_CODE: 0`; status/start/end отсутствуют, **0 начатых / 0 завершённых тестов**, 6PASS не подтверждены. `logcat.stdout:26731–26733` фиксирует startup ANR target 18:33:23.783 +05:00, PID2368, `failed to complete startup`, ErrorId `ef384115-af0d-4555-a8a1-3be5b0062a4b`; `:26767` убийство процесса и `:27037` crash instrumentation. Причина задержки не установлена. Trace из `/data/anr` не получен: cleanup завершился до отдельного указания координатора, AVD повторно не запускался. UI assertions и live NavHost/scroll/mini-player не проверены; runtime не принят.

После runner выполнен `emu kill` exit0, завершены свои logcat и foreground adb5038. Первый ps ещё содержал собственный emulator watchdog PID151880; последующая host-проверка подтвердила его завершение, отсутствие собственных qemu/netsimd/adb и listeners5038/5560/5561 (`host-final-ps.stdout`, `host-final-ss.stdout`). Чужой 5037/телефон не опрашивались, userdata не очищались. Логи обоих preflight и runner находятся локально в игнорируемом `app/build/yu5f-ui-green-20261120`; они не входят в Git и исчезнут при очистке build. Повторного instrumentation, изменений production/tests/VM/deps, Gradle, release, merge/rebase не было. Защищённые деревья `app/src/test`, `app/src/sharedTest`, `app/src/androidTest` совпадают с `b52b256eae8486e6fb92c7094039460740755b25`; `git diff --check` PASS. Graph не обновлялся.

### Сохранённый ANR после40a2d09 — 2026-11-20,23часа+05

Автор явно разрешил потратить доступный остаток лимита на посильную работу и продолжить автоматически после сброса; это новое разрешение работать выше прежних порогов, а не переиспользование исключения Terra. Начальное окно77%/90%, primary reset20ноября23:17:31+05, weekly reset24ноября20:22:12+05. Создан одноразовый heartbeat plinth на20ноября23:19:31+05 (после сброса+2мин); не дублировать, ноутбук в сон не отправлять.

Небольшой механический этап plinth_light_executor: только получить уже сохранённый ANR trace собственного target io.github.puflik.plinth.debug, PID2368, ошибка20ноября18:33:23.783+05, ErrorId ef384115-af0d-4555-a8a1-3be5b0062a4b. Исследовать причину или менять код не поручено. Scope tracked только этот отчёт; production/tests/VM/deps не менять. Входной HEAD40a2d09, защищённый baseline b52b256eae8486e6fb92c7094039460740755b25. Сверить весь test/sharedTest/androidTest tree. Deadline исполнителя18:14:30UTC, root сохраняет/публикует до18:17UTC перед автоматическим продолжением.

Использовать host require_escalated для записей в sibling worktree, adb и эмулятора. Только существующий AVD Plinth_yu5f_api35, SDK /home/puflik/Android/Sdk, выделенный foreground adb5038, console5560/serial emulator-5560. Чужой5037/телефон не трогать; AVD/userdata/config не очищать и не менять. Host preflight ps/ss5038/5560/5561: не останавливать неизвестный процесс. Проверенный argv в /tmp/yu5f_fresh_diag.py (не запускать старый script напрямую: перезапишет прежние логи); foreground adb stdout/stderr прямо в файлы, не PIPE, env ADB_TRACE=adb,sockets,transport,services,sysdeps и ADB_MDNS_AUTO_CONNECT пустой. Emulator -avd Plinth_yu5f_api35 -port5560 -no-window -no-snapshot-load -no-snapshot-save, ANDROID_ADB_SERVER_PORT=5038. Новый logdir app/build/yu5f-saved-target-20261120-night.

Проверки identity отдельными argv-командами: get-state; shell getprop ro.kernel.qemu; shell getprop sys.boot_completed; shell getprop init.svc.bootanim. Poll умеренно, до180сек, логировать компактно. Затем adb root только этому AVD; повторить serial/qemu/boot/uid0 identity. Сохранить список/stat файлов /data/anr. Извлекать только блоки с точным Cmd line: io.github.puflik.plinth.debug (SELECT из /tmp/yu5f_fresh_run.py), отдельно указать header/PID/date, размер, SHA, целевой main-thread stack если существует. Не печатать большие дампы в ответ, сохранять файлом. Если trace отсутствует или не совпадает с PID/date — так и записать, причину не угадывать. Никаких install/instrumentation/нового воспроизведения, debuggerd/SIGQUIT, сборок. Одна коррекция неожиданной ошибки команды, повторный сбой стоп/отчёт.

Cleanup до deadline: emu kill своему verified serial, дождаться собственных qemu/netsimd/watchdog; остановить свой adb5038, подтвердить host ps/ss. Прогресс сразу /tmp/plinth-yu5f-saved-target-20261120-night.md, затем полный отчёт и commit только taskfile в work/yu5f. Root публикует. Назначенную роль/effort указать, фактическую модель без telemetry не выдумывать. Других агентов и таймеров не запускать.

#### Итог сохранённого ANR — 2026-11-20

Назначенная роль `plinth_light_executor`, модель/effort по роли — `gpt-6-luna` / `medium`; фактическую модель и effort среда выполнения не показывает, поэтому они не подтверждаются. Входной HEAD `40a2d09f2a3772e7bd3b37d510e28aed6d14afff`. Выделенный AVD `Plinth_yu5f_api35` запущен на `emulator-5560` через отдельный ADB5038; preflight портов5038/5560/5561 был пуст. Проверки до и после `adb root`: serial `emulator-5560`, `ro.kernel.qemu=1`, `sys.boot_completed=1`; после root — `uid=0(root)`.

`/data/anr` прочитан только для списка, stat и извлечения блоков с точной строкой `Cmd line: io.github.puflik.plinth.debug`. Файл, датированный около целевого события, `/data/anr/anr_2026-11-20-18-33-23-245`, имеет размер0 и mtime `2026-11-20 18:33:23.712 +0500`. Селектор не нашёл ни одного точного target-блока во всех ANR-файлах. Поэтому сохранённые target header/PID/date, trace hash и main-thread stack отсутствуют; причину отсутствия не устанавливал. Список и размеры файлов, а также результат селектора сохранены локально в игнорируемом `app/build/yu5f-saved-target-20261120-night/target-extract.txt` (1276 bytes; SHA-256 `f6a862b7602c89a36f570732bba509d7044afd03aadbcb52731cad0a50fdcdc7`); блоков дампа в нём нет.

AVD остановлен командой `emu kill` для `emulator-5560`, выделенный ADB5038 остановлен. Проверка после cleanup показала отсутствие процессов этого AVD/ADB и listeners5038/5560/5561; оставшийся процесс boot poll был установлен как собственный по argv и завершён. Защищённые деревья `app/src/test`, `app/src/sharedTest`, `app/src/androidTest` совпадают с `b52b256eae8486e6fb92c7094039460740755b25`; `git diff --check` PASS. Не выполнялись сборка, установка, instrumentation, воспроизведение ANR, debuggerd или изменение кода.

### 24 ноября 2026. Проверка исхода запуска с ранним захватом стека

Один запуск Yu5fSearchSectionsScreenTest из существующих APK с source ca6b73a на Plinth_yu5f_api35 через выделенный ADB 5038, emulator-5560. План раннего захвата записан в основном клоне, docs/work/yu5f-logcat-check.md. При живом процессе приложения без первого теста спустя десять секунд проверить точный cmdline PID, один раз получить debuggerd -j и сохранить вывод до остановки. При старте тестов дать всем шести завершиться и записать имена и результаты. Максимум шесть минут на instrumentation. Владение исполнителя — новые локальные логи app/build/yu5f-live-20261124 и собственный отчёт в этом файле. После run проверить порты и завершение собственных процессов. Сохранить первый фактический результат без исправлений кода и повторов. Protected baseline b52b256e, существующие APK с SHA-256 d5556dffffd437322a4dc52c905a9307b3b134edffcb2f339571e5a6fb960f02 и cb5839530ba59875e276883baaeae20aca79c8400cc39f4dab17ce494157032d. Коммит отчёта в work/yu5f, публикация координатором.


Исполнитель раннего захвата: назначен `plinth_executor` (`gpt-6.1-sol`/`high`); отдельная telemetry фактической модели недоступна. Начало 2026-11-24T18:35:33.978369+00:00. Входной HEAD `03c967edb53c49331f8bb431e4991bb2b77f7c91`, прежние изменения задания сохранены. Новые доказательства — `app/build/yu5f-live-20261124`, прогресс `/tmp/plinth-yu5f-live-20261124.md`; source/tests не меняются.


#### Итог раннего захвата — 2026-11-24

**Результат: 6 начатых, 6 завершённых, 6 PASS, 0 FAIL, 0 ignored в единственном classrun.** Runner вывел `OK (6 tests)`, `Time: 74.955`, `INSTRUMENTATION_CODE: -1`; host exit instrumentation — 0. Это выполнение настоящих assertions защищённого класса, а не вывод по одному exit0. Startup ANR в этом запуске не повторился; причина прежних ANR и исправление startup этим не доказаны.

**Ранний stack не потребовался.** Logcat запущен в18:40:00.342UTC перед instrumentation18:40:00.344UTC. `logcat.stdout:55` фиксирует запуск target PID4852 в23:40:01.347+05; отдельный PID observer увидел4852 в18:40:02.519UTC. Первый тест начал выполняться в23:40:04.330+05 (`:577`), через2.983сек после запуска процесса и1.811сек после первого наблюдения PID, раньше десятисекундного порога. `debuggerd -j`, SIGQUIT, сверка cmdline перед stack и сбор кадров main поэтому не выполнялись; main-thread frames отсутствуют, а не объявляются пустым полученным stack. Шесть завершений подтверждены `logcat.stdout:1095,1608,2053,2665,3421,3995`; итог `:4004` в23:41:19.281+05: `6 tests, 0 failed, 0 ignored`.

| Тест | Результат |
|---|---|
| `show_all_opens_the_remaining_rows_without_asking_the_network` | PASS |
| `retry_of_youtube_does_not_repeat_the_completed_archive_search` | PASS |
| `first_and_second_player_returns_keep_the_same_visible_song_offset` | PASS |
| `overview_contains_five_rows_per_section_and_no_sixth_row` | PASS |
| `expanded_section_survives_player_return_and_resets_for_a_new_query` | PASS |
| `five_results_have_no_show_all_button` | PASS |

Подготовка: оба существующих APK проверены по полным SHA-256 из задания — `d5556dffffd437322a4dc52c905a9307b3b134edffcb2f339571e5a6fb960f02` и `cb5839530ba59875e276883baaeae20aca79c8400cc39f4dab17ce494157032d`; обе установки `install -r` завершились Success18:38:09.365/18:38:10.992UTC. Свободные5038/5560/5561 проверены до запуска. Созданы собственные foreground adb PID208156 и qemu PID208166/session208166, netsimd208233 в той же сессии; точные argv/PID/env/ps сохранены. Проверены имя AVD `Plinth_yu5f_api35`, serial `emulator-5560`, qemu1, boot1, bootanim stopped; после `adb root` повторены identity и uid0. Getprop передавались отдельными argv, без ошибочного sh-экранирования.

После завершения boot/root18:37:44.879UTC и установки APK Android получал время на первоначальную фоновую работу. Семь серий `top -b -d 5 -n 4 -m 8` и loadavg сохранены, без изменения hardware/данных или таймаутов тестов. В первых сериях normalized CPU idle составлял0–8.5%; к18:40:00.340UTC последние три показания стали44.5%,43.5%,43.0% от200% CPU capacity гостя. Это около109сек после конца установки и135сек после подтверждения boot/root; только затем исходный скрипт автоматически запустил единственный класс. После запуска PID наблюдался отдельно, без top в цикле. Координатор позднее уточнил, что CPU — наблюдение, а не обязательный критерий допуска; к тому времени исходное ожидание уже закончилось успешно.

**Отклонение процедуры наблюдения:** исполнитель преждевременно подготовил ненужную смену controller, не перечитав уже поступивший прогресс об автоматическом старте. Новый controller проверил собственные PID/session и приостановил только исходный Python-наблюдатель; AVD, logcat и instrumentation продолжали работу. Попытка handoff остановилась на проверке ещё живого дочернего наблюдения; второй classrun, повторный boot или install не выполнялись. В18:41:39.886UTC новый controller начал cleanup — через20.605сек после подтверждённого logcat завершения всех шести тестов18:41:19.281UTC. Поэтому тестовый результат получен до остановки AVD; этот факт подтверждён временами runner/logcat/cleanup, а не предположением о влиянии вмешательства. Исходный observer возобновлён; его end18:41:52.777UTC/112.433сек включает паузу наблюдения, фактическое время runner74.955сек. Вмешательство не используется как доказательство отсутствия других временных эффектов.

После этого необязательные post-run запросы target ANR/pidof из исходного controller получили25/10сек timeout на уже остановленном AVD; target ANR-файлы этим запуском не прочитаны и новый trace не заявляется. При записи повторного `host-final-ps.stdout` исходный controller завершился exit1 с FileExistsError: эксклюзивное создание сохранило прежние доказательства. Скрипты не исправлялись для повторного runtime; окончательные независимые host-проверки сохранены под новыми именами `verified-final-ps/ss.*`. Ошибки служебных controller не скрываются за PASS тестового класса.

Cleanup: `emu kill` только собственного verified serial exit0 в18:41:39.921UTC, `adb -P 5038 kill-server` exit0 в18:41:41.926UTC. Окончательная host-проверка18:42:41UTC подтвердила отсутствие qemu/netsimd/adb диагностического этапа и listeners5038/5560/5561. Старый observer завершён, дополнительный controller завершён; фоновых диагностических процессов не осталось. Чужой5037/телефон не опрашивались; wipe/recreation/userdata/hardware/dependencies не менялись.

Логи локальные игнорируемые: `app/build/yu5f-live-20261124`, progress `/tmp/plinth-yu5f-live-20261124.md`. Сохранены полные stdout/stderr/status/time, APK hashes, семь серий нагрузки, непрерывный logcat, PID observations, runner assertions, служебные ошибки и финальные host-проверки; прежние каталоги доказательств не перезаписаны. Назначена роль `plinth_executor`, `gpt-6.1-sol`/`high`; отдельная runtime telemetry фактической модели недоступна, замена не заявляется. Production/tests не менялись; Gradle/JVM/CI, source repair, release, merge/rebase и graph update не выполнялись. PASS относится к этому защищённому Compose harness; оставшаяся приёмка живого NavHost/scroll/mini-player и независимое ревью остаются координатору.

Финальные Git-проверки: `git diff --check` PASS; полный diff всех `**/src/test/**`, `**/src/sharedTest/**`, `**/src/androidTest/**` относительно `b52b256eae8486e6fb92c7094039460740755b25` пуст. От входного HEAD изменён только `docs/work/yu5f.md`; исходные четыре строки задания координатора сохранены. Коммит отчёта — только `work/yu5f`; push выполняет координатор.


## Дополнение планировщика 2026-11-24: ожидающая секция UI

- **Ветка/ворктри:** `work/yu5f`, `/home/puflik/src/Plinth-yu5f`; входной HEAD `04a4ddf`.
- **Основа:** принятое решение этого файла, строки37–55; новых продуктовых решений нет.
- **Данные пользователя:** нет; только fake repositories и Compose harness, без журнала/CRDT/миграций.
- **Планировщик:** назначен `plinth_planner`, `gpt-6.1-sol`/`xhigh`; отдельная runtime telemetry фактической модели недоступна, замена не заявляется.

### Цель, решение и инварианты

Защитить показ ожидающего Internet Archive перед уже готовым YouTube. Реальные `SearchScreen`/`SearchViewModel`, один альбом на провайдера; `CompletableDeferred` задерживает только Archive. Тест ждёт подтверждённого частичного `Found(YouTube, loading={Archive})`, проверяет быстрый результат до освобождения gate, доступные heading/progress semantics и расположение Archive→индикатор→YouTube. Поиск через `performScrollToNode` отличает отсутствующий LazyColumn item от виртуализации. После gate виден ответ Archive, индикаторов нет. Без sleep, live network и новых production test tags.

### Файлы и защищённые тесты

Менять на этом этапе только `docs/work/yu5f.md` и новый `app/src/androidTest/kotlin/io/github/puflik/plinth/ui/search/Yu5fPendingSectionScreenTest.kt`; последний становится защищённым отдельным регрессионным файлом в коммите этого этапа. Прежние baseline14, все старые instrumentation tests, production, зависимости и общие документы не менять. Производственную реализацию планировщик не выполняет. Будущий исполнитель меняет только ранее разрешённый `SearchScreen.kt`, читая это дополнение вместе с исходным решением; независимое ревью остаётся Astra xhigh.

### Проверки, критерии и остановка

`JAVA_HOME=/home/puflik/.local/opt/jdk21 ANDROID_HOME=/home/puflik/Android/Sdk GRADLE_USER_HOME=/home/puflik/.gradle RUSTUP_TOOLCHAIN=1.98.1 bash ./gradlew ktlintCheck assembleGithubDebugAndroidTest`, без отключения native. Затем один classrun `Yu5fPendingSectionScreenTest` на собственном `Plinth_yu5f_api35`, adb5038/serial emulator-5560, после явной identity/qemu/boot проверки и завершения Gradle. Цель этого этапа — компиляция и intended runtime RED отсутствующего pending header, не исправление production. Одна коррекция неожиданного сбоя; при втором остановка, отчёт/cleanup/commit.

### Отчёт планировщика — ход работы

В18:45UTC входная ветка/HEAD проверены, дерево чистое; graphify-out в этом worktree отсутствует, scoped graph query не запускался. Исходный UI обходит только `Found.sections` и не использует `Found.loading`; это ожидаемая причина RED. Новый guard написан, проверки ещё не завершены. Локальный прогресс: `/tmp/plinth-yu5f-pending-guard.md`.


### Отчёт планировщика — остановка после второй проверки

На 2026-11-24T18:51:11.801746+00:00 написан один новый тест `pending_archive_stays_before_fast_youtube_until_its_result_arrives`; production и прежние тесты не менялись. Назначение `plinth_planner`, `gpt-6.1-sol`/`xhigh`; независимая runtime telemetry модели недоступна.

Первая команда `ktlintCheck assembleGithubDebugAndroidTest` завершилась exit1 за1м23с: `compileGithubDebugAndroidTestKotlin` выполнена успешно, но ktlint нашёл оформление цепочки и длинного expression body в новом файле. Применена единственная коррекция форматирования. Повтор той же команды завершился exit1 за22с: Kotlin снова скомпилирован, `cargoNdkBuild` включён и UP-TO-DATE; ktlint выявил оставшийся перенос операнда в строке75 (`youtube?.provider == ... && result?.title == ...`). Нужный перенос перед `result` не внесён: после второго последовательного падения действует обязательный стоп. Логи: `/tmp/yu5f-pending-build.log`, `/tmp/yu5f-pending-build-retry.log`; свежая компиляция теста подтверждена, успешные ktlint и assemble не заявляются. Предупреждение устаревшего `createComposeRule` совпадает с существующим harness; API и зависимости не менялись.

Нового собранного test APK и новых SHA не заявляем. Эмулятор/adb/logcat/classrun не запускались, runtime RED **не подтверждён**, production выполнять нельзя до intended RED. Подготовленный отдельный runner `/tmp/yu5f_pending_guard_20261124.py` не запускался; APK/SHA-файл для него не создавался. Следующий шаг координатора — устранить единственное оформление в новой отдельной задаче, заново выполнить lint/assemble, записать настоящие APK SHA и подтвердить RED на собственном AVD с явным5038/5560/serial/qemu/boot; прежние тесты остаются защищены. Gradle daemon остановлен для cleanup.

Текущая находка в коде остаётся: `SearchScreen.online` обходит только завершённые `Found.sections`, ожидаемые ID из `Found.loading` не представлены заголовком/индикатором. Guard ловит это после подтверждённого частичного состояния VM, а не по таймеру. После первого успешного подтверждения RED коммит guard должен быть опорным для защиты этого файла. Независимое ревью и push выполняет координатор.

### 25 ноября. Проверка задержанного Internet Archive

В `c91d1be` планировщик остановился на одном правиле ktlint. Новый этап для планировщика: перенести последний операнд `result?.title == FAST_TITLE` условия на отдельную строку, не затрагивая утверждений. Проверить diff без пробельных изменений относительно c91d1be, ktlint и сборку тестового APK. Выполнить только `Yu5fPendingSectionScreenTest` на собственном Plinth_yu5f_api35 (ADB 5038, emulator-5560). Сценарий запуска от 24 ноября сохранился в `app/build/yu5f-live-20261124/yu5f_live_20261124.py`. Использовать новый каталог логов от 25 ноября, новый класс и проверенные SHA. Завершить с точной причиной падения или фактическим успехом. Сохранить рабочий каталог, APK и проверку завершения процессов. Только новый тест и отчёт в этом файле. Код интерфейса — отдельный этап после подтверждённого падения. Перед запуском API лимитов дважды не ответил, но клиент зарегистрировал квоты в 09:30:15 UTC (9% / 15%).


#### Отчёт планировщика — 2026-11-25, ход проверки

Назначение: `plinth_planner`, `gpt-6.1-sol`/`xhigh`; независимая runtime telemetry фактической модели/effort недоступна, назначение не выдаётся за измерение. Входной HEAD `c91d1be`, ветка `work/yu5f`. Пользовательское задание выше сохранено. Применены `graphify` (узкий запрос, граф отстаёт от нового теста) и `tdd-workflow`; нового исследования поведения и production-изменений нет.

План: только перенос последнего операнда без изменения логики; `git diff -w` теста должен быть пуст; затем `ktlintCheck` и `:app:assembleGithubDebugAndroidTest`; после завершения Gradle один инструментальный запуск только `Yu5fPendingSectionScreenTest` на собственном подтверждённом AVD `Plinth_yu5f_api35`, порт ADB5038, emulator5560. Нужный RED — assertion отсутствующего заголовка Internet Archive после подтверждённого частичного состояния VM и видимого `Fast YouTube album`. Инфраструктурное падение не считается RED; при неожиданном сбое одна коррекция, затем остановка. Evidence: `app/build/yu5f-pending-red-20261125`; progress: `/tmp/plinth-red-25.md`. Очистка ограничена собственными процессами и портами5038/5560/5561;5037 и телефон не используются.

2026-11-25T09:36:18.195328+00:00: `ktlintCheck :app:assembleGithubDebugAndroidTest` PASS (`--no-daemon`, 1m40s; 94 actionable:8 executed/86 up-to-date). Новый Kotlin-тест компилировался, test APK пересобран; app APK сохранён прежней сборки, его свежая проверка SHA ниже. `git diff -w` показывает перенос строки по устройству построчного diff; отдельное сравнение всех непробельных байтов с c91d1be PASS.

APK app: `d5556dffffd437322a4dc52c905a9307b3b134edffcb2f339571e5a6fb960f02`, 35896522 bytes, mtime 2026-11-20T13:25:54.906849+00:00.

APK test: `f916498c2ea85f4dd0d457e1029dd34d7cab820c86874028bd7079d55542016f`, 2070928 bytes, mtime 2026-11-25T09:35:39.460085+00:00.

Точный рабочий runner сохранён как `app/build/yu5f-pending-red-20261125/yu5f_pending_red_20261125.py`, pin-манифест — `apk-pinned.json`; CPU-idle/load gate удалён, чужой adb/AVD не перенимается, один controller владеет запуском и cleanup.

После форматирования условие совпало побайтно при исключении пробелов. `ktlintCheck` и `:app:assembleGithubDebugAndroidTest` прошли. SHA-256 тестового APK `f916498c2ea85f4dd0d457e1029dd34d7cab820c86874028bd7079d55542016f`. Во время загрузки AVD система перезагрузилась, instrumentation ещё не начинался. После загрузки собственные порты свободны; причина перезагрузки не установлена. Файлы первой попытки находятся в `app/build/yu5f-pending-red-20261125`. Следующий запуск использует готовые APK и отдельный каталог.

### Исполнитель: проверка ожидающей секции, 25 ноября

Входной HEAD `301c884`, работа ограничена `SearchScreen.kt` и этим отчётом; тесты защищены. Фактическая модель и effort независимо недоступны. Исходные APK сохранены; новый изолированный runner и evidence — `app/build/yu5f-pending-red-soft-20261125`. Перед запуском доступно 4,3 GiB RAM и 43 GiB диска. Повтор использует software GPU, 1536 MiB, 2 cores и nice 10, без Gradle и wipe; до исходного RED production не меняется.

Первый classrun: identity/root и SHA APK подтверждены, установка прошла; instrumentation завершилась `shortMsg=Process crashed`, `INSTRUMENTATION_CODE: 0`, тест не начался. Logcat фиксирует startup ANR и убийство PID 2400; заранее получен `debuggerd -j`. Это не RED и не доказательство причин прежних ANR. Cleanup освободил порты 5038/5560/5561. Разрешённая единственная коррекция: новый каталог `app/build/yu5f-pending-red-settle-20261125`, проверка SHA установленных APK, без переустановки, `cmd package compile -m speed -f` обоих тестовых пакетов и ожидание снижения гостевой фоновой нагрузки. Меняется только ART-кэш тестового AVD.

Повтор после SHA и ART-компиляции достиг теста: partial Found и быстрый альбом подтверждены утверждениями до строки 83; `performScrollToNode` завершился `AssertionError: No node found ... Internet Archive ... Heading`. `Tests run: 1, Failures: 1` — intended runtime RED, не инфраструктурная ошибка. Снимки, runner и CPU settle сохранены в новом каталоге; guest ART-кэш двух тестовых пакетов изменён, APK повторно не устанавливались. Cleanup подтвердил отсутствие listeners 5038/5560/5561 и собственных qemu/adb/netsimd. Производственный код пока не изменён.
