# yu5f — независимые секции онлайн-поиска

> **Корректирующий split завершён; candidate ожидает независимого Astra review.** Полные ktlintCheck/detekt PASS, оба compile дают только принятый constructor RED. Production не начинать; candidate ещё не принят.

- **Ветка:** `work/yu5f` · **Ворктри:** `/home/puflik/src/Plinth-yu5f`
- **Исходная основа:** `main` на `af5ecb3`; 2026-11-13 принят main `419ae48` обычным merge `257546c`, без переписывания опубликованной ветки.
- **Коммит тестов, кандидат (не принят):** `6e8c32a2e4da0cd3a4341055138406d85f79420b` — 12 защищённых путей после разрешённого split; полный style PASS, constructor compile RED; требуется независимое Astra review. Предыдущий кандидат `dd33755a463b0e344e24dd79436cc1d3638ff45e` сохранён для точной сверки 22 тел. Историческая опора семи файлов `cbe6934fcd82f203622a490031e9a0e054272fee`, первый baseline `25c1203670682420f9a51c902cd22c60556ac6f5` сохраняются для сравнения.
- **Данные пользователя:** нет: только поиск и положение UI; журнал/каталог/CRDT не меняются.
- **Планировщик фактически:** `gpt-6.1-sol`, `xhigh`, 2026-11-04 и 2026-11-14; последний проход — readiness guards, без production.
- **Ограничения запуска:** ровно один агент, без вложенных агентов, реализации и массовых сборок. Перед запуском 75% пятичасового / 12% недельного окна; автор разрешил разовое исключение. Лимиты сообщил координатор.

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
- Новые дополнительные тесты, если нужны: `app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5fAdditionalSearchTest.kt`, `app/src/androidTest/kotlin/io/github/puflik/plinth/ui/search/Yu5fAdditionalSearchScreenTest.kt`.
- `docs/work/yu5f.md` — только «Отчёт исполнителя».

**Защищённые тесты и fixtures (12 путей нового baseline; не менять/удалять/глушить исполнителю):**

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

```powershell
git diff <коммит-тестов> HEAD -- app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5fProgressiveSearchTest.kt app/src/androidTest/kotlin/io/github/puflik/plinth/ui/search/Yu5fSearchSectionsScreenTest.kt app/src/test/kotlin/io/github/puflik/plinth/ui/search/SearchViewModelCancelTest.kt app/src/test/kotlin/io/github/puflik/plinth/ui/search/SearchViewModelRetryTest.kt app/src/test/kotlin/io/github/puflik/plinth/ui/search/SearchViewModelTest.kt app/src/sharedTest/kotlin/io/github/puflik/plinth/online/FakeOnlineRepository.kt app/src/sharedTest/kotlin/io/github/puflik/plinth/online/TestYoutubeOnline.kt
git diff --check
```

Первый diff пуст. Если защищённые тесты изменены планировщиком до реализации по принятому решению, новый hash явно заменить в шапке и независимо проверить миграцию, не сравнивать со старым случайным hash.

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

_Не заполнялся: реализация не запускалась. Исполнитель пишет фактическую модель/effort, изменения, проверки fresh/cache, коммиты, ограничения и живую приёмку._

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
