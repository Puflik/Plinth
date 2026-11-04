# yu5f — независимые секции онлайн-поиска

> Рабочий файл планировщика; дополняется по мере проверки. Production-код не меняется.

- **Ветка:** `work/yu5f` · **Ворктри:** `C:\claude\Plinth-yu5f`
- **Основа:** `main` на `af5ecb389b16478abe13769730ff6342e99b0198`
- **Коммит тестов:** `25c1203670682420f9a51c902cd22c60556ac6f5` (первый в ветке; план и оба новых файла тестов).
- **Данные пользователя:** нет: только поиск и положение UI; журнал/каталог/CRDT не меняются.
- **Планировщик фактически:** `gpt-6.1-sol`, `xhigh`, 2026-11-04.
- **Ограничения запуска:** ровно один агент, без вложенных агентов, реализации и массовых сборок. Перед запуском 75% пятичасового / 12% недельного окна; автор разрешил разовое исключение. Лимиты сообщил координатор.

## Цель

Секции Internet Archive и YouTube появляются независимо по мере ответов, в таком порядке. В обзоре максимум пять строк каждой секции и «Показать все». Возврат из плеера сохраняет положение списка.

## Проверено планировщиком

- `graphify query "SearchViewModel online provider sections searchIn search scroll return player" --budget 1500` упал с `ModuleNotFoundError: No module named 'graphify'`. Граф основного repo отстаёт (`built_at_commit=7c5192a`); пакеты не устанавливались, граф не перестраивался. Связи сверены по ограниченным диапазонам исходников.
- `SearchViewModel.answerTo` ждёт общего `online.search`, кеширует только окончательный ответ. `OnlineProviderIds.ALL` уже задаёт нужный порядок.
- `CoreOnlineRepository.searchIn` пользуется `outliving` с отдельным `OnlineCancel`; `Registry::search_in` и общий поиск пользуются одним `ask`, то есть здоровье/отдых/deadline/cancel сохраняются. Правки ядра не нужны.
- Ветка и ворктри созданы от указанного main; скопирован только `local.properties`, сборочные каталоги не переносились. Незакоммиченные решения основного repo не затронуты.

## Обнаруженное ограничение перед реализацией

`SearchViewModelCancelTest.SilentOnline` переопределяет лишь общий `search`; `searchIn` делегируется внутреннему fake. При переходе VM на `searchIn` старый mock перестанет наблюдать отмену. `SearchViewModelRetryTest` также требует общий `OnlineResults.Searching` и общий счётчик `search`. Производственный код не должен делать лишний общий сетевой запрос ради такого mock. Существующие защищённые тесты в этой ветке не менялись; вопрос о корректной миграции передан координатору.

## Контекст исполнителя

Читать только нужные диапазоны: `docs/work/yu5a.md:561–571, 666–680`, `docs/decisions.md:6970–6977`, `tasks.md:395`, `docs/work/README.md`, `AGENT_ROLES.md`; далее `SearchViewModel.kt:50–135, 215–285`, `SearchScreen.kt:110–205`, `MainActivity.kt:133–180`, `online/CoreOnlineRepository.kt:35–94`, `OnlineProviderIds.kt`, `OnlineSettings.kt`, `core/providers/src/registry.rs:60–115`. Номера на основе af5ecb3. Код и решения первичны: граф отстаёт.

Действующий порядок — **yu5f → yu5b → yu5c → yu5d → yu5e → Ю6**. Подсказка при выключенных провайдерах — yu5b; страницы/плейлисты YouTube — yu5e. Здесь не менять ядро, предел 20 сетевых результатов и очередь/журнал.

## Решение для ревью координатором до реализации

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

`sections` — только завершённые непустые по присутствию секции, в порядке ALL; `loading` — ID ещё ожидаемых. UI берёт объединение ID sections/loading и рисует его в ALL order: заголовок и собственное «Ищем…» сразу у каждого. Завершённая пустая секция — «ничего не нашлось»; `null` от searchIn — секции нет, убрать ID из loading и пометить завершённым в памяти. `null` не превращать в вечное ожидание. Пустые/ошибочные секции соседей не стирать.
4. Память — по текущим trimmed query и набору активных ID, отдельно на провайдера: получен ответ (включая problem и null) или ещё ждём. На новом settled query/изменении набора/выключении отменить предыдущую группу и инвалидировать её generation. Старый ответ не может обновлять новый запрос. Очистка поля отменяет сразу, без debounce. Не менять библиотечный поиск и SongState/OnlineSongs.
5. `WhileSubscribed(5_000)` сохранить. Уход > 5 с отменяет только ещё работающие запросы, завершённые ответы остаются. При возврате первым кадром прежний snapshot; повторно запустить только отменённых незавершённых провайдеров. Возврат в пределах grace не создаёт повторов. Проблема также завершённый ответ: повторяется только вручную.
6. Новая `onRetryOnline(provider)` запрашивает только названного активного провайдера для текущего settled query; его секцию заменяет loading, соседние ответы/ожидание/работа остаются. Повтор во время loading не плодит запросы. Чужой ID, пустой query или выключенные источники — no-op. Существующую `onRetryOnline()` оставить как программное обновление всех активных секций; UI использует только overload с ID. Отдельная generation попытки защищает от старого retry. Возвращённый provider должен соответствовать запрошенному ID; нарушение не принимать за секцию соседа.

### Пять строк и «Показать все» — требует выбора координатора

Предложенный минимальный вариант: раскрывать **внутри поиска только выбранную секцию**, без нового route, запроса сети и каталожных действий. В обзоре `results.take(5)`; кнопка только при `results.size > 5`. После раскрытия показывать уже полученный полный ответ (до прежних 20), кнопку убрать; соседняя секция остаётся ограниченной пятью. Раскрытие живёт в `rememberSaveable` на trimmed query и provider: возврат из плеера/альбома сохраняет его; новый query сбрасывает. Существующие onSong/onOpenAlbum продолжают работать из обеих форм. Не добавлять новый экран, вкладки и «Свернуть» без принятого решения.

Это технически простая навигация в текущем экране, однако исходное решение автора не определяет inline против отдельного экрана. **Вопрос координатору: принять описанное inline-раскрытие или нужен отдельный экран?** Новый защищённый UI тест `show_all_opens...` закрепляет предложенный inline-вариант; до принятия не начинать реализацию. Если выбран другой вариант, тесты правит планировщик отдельным согласованным коммитом, новый hash — опорный.

Для UI-тестов нужны стабильные теги существующих кнопок, без пользовательского текста с техническими именами: `search-show-all-<provider>` и `search-retry-<provider>`. Подписи «Show all»/«Показать все» — строковые ресурсы `search_show_all`, одинаковый ключ в values и values-ru.

### Сдвиг после плеера

На main `SearchScreen` создаёт LazyColumn в двух ветках, состояние по умолчанию находится внутри них. `MainActivity` после первого старта игры добавляет мини-плеер и уменьшает viewport. Историческое наблюдение: первый возврат дал +≈209 px, второй — нет. Это **гипотеза о месте сбоя, а не доказанная причина**.

Первый шаг исполнителя: воспроизвести на явно выбранном эмуляторе; записать до/после firstVisibleItem key, index, scrollOffset и высоту viewport при первом/втором возврате (верх, середина и конец длинного списка). Проверить также приход второй секции, когда пользователь уже прокрутил первую. Держать один явный `rememberLazyListState()` над ветками, одни provider-prefixed стабильные ключи у заголовков/rows/loading/error/Show all; не вызывать scrollToItem(0) при ответе сети/возврате. Сохранение key+offset должно переживать навигационный save/restore и появление мини-плеера. Уменьшение окна само по себе допустимо, скачок верхней видимой строки — нет. Если источник окажется вне SearchScreen, остановиться и запросить расширение списка файлов; `MainActivity`/NavHost пока не разрешены.

Новый UI тест выполняет настоящий SearchScreen/действие PLAY с fake audio, удаляет и восстанавливает поиск через SaveableStateHolder, меняет доступную высоту на 64 dp. Это приближает жизненный цикл навигации; не заменяет живой NavHost и не доказывает исторический дефект без выполнения. Точность — та же видимая песня и top текста ±1 px после первого и второго возврата, без новых запросов.

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

Все пути ниже от корня ворктри `C:\claude\Plinth-yu5f`.

**Менять исполнителю после снятия блокеров:**

- `app/src/main/kotlin/io/github/puflik/plinth/ui/search/SearchViewModel.kt` — orchestration/cache/retry/state.
- `app/src/main/kotlin/io/github/puflik/plinth/ui/search/SearchScreen.kt` — independent section UI, preview/раскрытие, saved list state/keys.
- `app/src/main/res/values/strings.xml` и `app/src/main/res/values-ru/strings.xml` — только подпись Show all; общие файлы явно назначены этой задаче.
- Новые дополнительные тесты, если нужны: `app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5fAdditionalSearchTest.kt`, `app/src/androidTest/kotlin/io/github/puflik/plinth/ui/search/Yu5fAdditionalSearchScreenTest.kt`.
- `docs/work/yu5f.md` — только «Отчёт исполнителя».

**Защищённые новые тесты (не менять/удалять/глушить исполнителю):**

- `app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5fProgressiveSearchTest.kt`.
- `app/src/androidTest/kotlin/io/github/puflik/plinth/ui/search/Yu5fSearchSectionsScreenTest.kt`.

**Не трогать:** всё прочее, в частности старые тесты и sharedTest fixtures, `OnlineRepository`, `OnlineProviderIds`, Gated/Core repositories, core, MainActivity, PlinthNavHost, OnlineSongs, TrackActions, очередь, зависимости, AGENTS/.codex, CHANGELOG/tasks/decisions, plan. Защищённые тесты прошлых задач также сохраняют защиту. Никаких копий сборок/target основного repo.

## Миграция старых тестов — блокер отдельного решения

Текущий контракт тестовых адаптеров не совместим с новым транспортом и состоянием. Менять production ради вызова старого mock нельзя. Координатору требуется разрешить отдельную точную миграцию с сохранением бизнес-проверок и обновить опорные hash защищённых файлов **до реализации**:

- `app/src/sharedTest/kotlin/io/github/puflik/plinth/online/FakeOnlineRepository.kt`: searchIn должен обслуживать одного provider без двойного счётчика общего поиска; текущий вызывает `search(query)` даже для несуществующего ID. Не ослаблять общий OnlineRepositoryContractTest.
- `app/src/sharedTest/kotlin/io/github/puflik/plinth/online/TestYoutubeOnline.kt`: добавить эквивалентный searchIn, сейчас переопределён только search, и поиск YT через новый путь уйдёт в внутренний архивный fake. Его тесты yu5a не должны терять смысл.
- `app/src/test/kotlin/io/github/puflik/plinth/ui/search/SearchViewModelCancelTest.kt`: SilentOnline должен наблюдать searchIn(provider, query), проверять cancel всех активных вместо общего вызова; сроки/отмена/ротация сохраняются.
- `app/src/test/kotlin/io/github/puflik/plinth/ui/search/SearchViewModelTest.kt` и `SearchViewModelRetryTest.kt` там же: старое равенство `Searching` и счётчик общего транспорта заменить проверкой всех ожидаемых provider; память/error/clear/library assertions сохранить.

Это **не** разрешённые исполнителю файлы текущего плана. Вопрос: разрешена ли такая отдельная миграция защищённого тестового контракта координатором/планировщиком с независимым ревью? При отрицательном ответе нужен иной согласованный план, иначе общий набор тестов будет красным даже после правильной реализации.

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
git diff <коммит-тестов> HEAD -- app/src/test/kotlin/io/github/puflik/plinth/ui/search/Yu5fProgressiveSearchTest.kt app/src/androidTest/kotlin/io/github/puflik/plinth/ui/search/Yu5fSearchSectionsScreenTest.kt
git diff --check
```

Первый diff пуст. Если защищённые тесты изменены планировщиком до реализации по принятому решению, новый hash явно заменить в шапке и независимо проверить миграцию, не сравнивать со старым случайным hash.

## Готово, когда

- Сняты три блокера ниже; план и опорные тесты независимо прочитаны Astra `xhigh` до реализации. Для пользовательских данных здесь max не нужен: данные не затрагиваются.
- Секции приходят независимо, фиксированный порядок, собственные loading/error/retry; все 12 JVM тестов зелёные; при передаче из baseline ни одно утверждение не ослаблено.
- Реальная UI проверка подтверждает пять/Show all и сохранение top anchor после первого и второго возврата. Причина сдвига и до/после факты записаны, не выдана гипотеза за исправление.
- Старые поисковые/song тесты и полный выбранный набор зелёные после отдельной принятой миграции. CI work/yu5f зелёный перед слиянием (сам planner push ожидаемо RED до реализации).
- Изменены только разрешённые файлы; protected diff пуст; ни зависимости, ни журнал/ядро не менялись. Исполнитель коммитит/пушит только свою ветку, не main, не rebase/merge.

## Остановиться и написать, если

- Нет решения о миграции старого защищённого контракта, UI-навигации Show all или коррекции стиля новых protected tests.
- Для исправления scroll нужны MainActivity/NavHost/другие неразрешённые файлы либо новая продуктовая семантика.
- Тест кажется неверным; не исправлять/игнорировать/ослаблять, задать точный вопрос.
- Проверка упала второй раз после одной коррекции; отчёт, коммит и обычный push, без дальнейших попыток.
- Недоступна прямая сеть или push отклонён — не использовать proxy/force и не обходить отклонение.

## Статус передачи 2026-11-04

**План не готов к реализации; код не начинать.** Точные блокеры:

1. Координатор должен принять минимальную миграцию старых protected mocks/state assertions с сохранением смысла (список выше).
2. Нужен выбор inline Show all либо отдельный экран. Новый UI тест пока закрепляет предложенное inline.
3. Оставшиеся ktlint переносы нового JVM protected file требуют отдельной коррекции планировщика по разрешению после исчерпанной попытки; runtime baseline уже доказан. После изменения обновить hash.

**Планировщик:** фактически Sol `gpt-6.1-sol`, `xhigh`; без вложенных агентов. Производственные файлы и прежние protected tests не менял. Предлагаемый исполнитель после утверждения — Sol `high` для конкурентной VM-логики; проверка экрана Terra `high` отдельным последовательным этапом, независимый reviewer Astra `xhigh`. Фактические назначения следующих этапов пока отсутствуют; не считать их выполненными.

## Отчёт исполнителя

_Не заполнялся: реализация не запускалась. Исполнитель пишет фактическую модель/effort, изменения, проверки fresh/cache, коммиты, ограничения и живую приёмку._

## Ревью

_Независимый reviewer ещё не запускался. До кода снять перечисленные блокеры; после кода сверить protected diff, список файлов, UI и CI._
