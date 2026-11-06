# yu5f-ready — подтверждение применённой online-конфигурации

> Файл задачи для исполнителя по `docs/work/TEMPLATE.md`.
> Планировщик пишет план и защищённые RED-тесты; production здесь не реализуется.

- **Ветка:** `work/yu5f-ready` · **Ворктри:** `C:\claude\Plinth-yu5f-ready`
- **Основа:** `main` на `d250290` · **Коммит тестов:** `9e008680e56a5f2a9ca6152c83523066e473ae71` (первый в ветке; RED не подтверждён).
- **Откуда:** `C:\claude\Plinth-yu5f\docs\work\yu5f.md`, «Передача будущему планировщику yu5f-ready», строки 238–270.
- **Данные пользователя:** нет; online lifecycle/registry, без журнала, CRDT, миграций, FFI/Rust.
- **Исследование и ревью:** `gpt-6-astra`, `xhigh`; независимое согласование плана до исполнителя.
- **Планировщик, фактически:** `gpt-6.1-sol`, `xhigh`.
- **Исполнитель:** `gpt-6.1-sol`, `xhigh`: ограниченная конкурентность и cancellation.
- **План до реализации:** ожидает Astra; разрешение координатора на CoreOnlineRepository.setEnabled получено.

## Цель

Дать будущему yu5f явное truthful acknowledgement применения нужной конфигурации.
Повторное включение не допускает searchIn в registry предыдущей конфигурации.

## Контекст

Исследовательская передача указана выше; соседний worktree не менять и не сливать.
Прочитаны AGENTS.md, README.md, TEMPLATE.md, AGENT_ROLES.md; CODEX.md проверяется по разделам.
`graphify query "GatedOnlineRepository OnlineSwitch setEnabled configuration applied readiness tests" --budget 1200`
завершился `ModuleNotFoundError: No module named 'graphify'`; дальше только узкие исходники.
`graphify update .` запрещён. Начало работ: main d250290, tracked clean; чужие untracked не трогать.

## Решение

Выбран отдельный suspend readiness capability к тому же singleton Gated; без unconditional default Applied в OnlineRepository.
Окончательная API сигнатура зафиксирована до добавления тестов, в `OnlineRepository.kt`:

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

interface OnlineReadiness {
    suspend fun awaitConfiguration(configuration: OnlineConfiguration): OnlineReady
}
```

`GatedOnlineRepository(inner, waitLimit = 60.seconds)` реализует `OnlineReadiness`;
его constructor и существующий OnlineRepository API не меняются. Прямые Core/Fake не
реализуют capability и не получают фиктивного default Applied. DI предоставляет один
`@Singleton GatedOnlineRepository`, а OnlineRepository/OnlineReadiness — два alias binding к нему;
не приводить произвольный OnlineRepository через unsafe cast. Будущий yu5f инъецирует capability явно.

Внутренний lifecycle (не публичный Flow): Unobserved или Attempt(configuration, revision,
Pending/Applied/Failed). Pending — внутреннее состояние, suspend-barrier не возвращает его.
Revision > 0 и строго растёт для каждой начатой setEnabled-попытки, даже при одинаковом
значении конфигурации; OnlineSwitch distinctUntilChanged убирает только семантически
одинаковые settings emissions. Повторное непосредственное применение создаёт новую попытку.
Нормализация для обоих входов: enabled=false → disabledProviders=emptySet(); иначе пересечение
с OnlineProviderIds.ALL, копия множества. Неизвестные ID не создают лишнего apply и не ломают ack.
enabled=true + все disabled остаётся отдельной логической конфигурацией, как текущий Switch.

Waiter сначала ждёт появления нужной нормализованной конфигурации; несовпадающий текущий
ack ещё не Superseded. При совпадении он привязывается к revision; Applied этой текущей
revision даёт успех, Failed — явную ошибку, более новая revision — Superseded, даже при ABA.
Не хранить старый Applied как разрешение для config, когда актуален другой Pending/Failed.
Новые обращения после failed той же текущей revision получают Failed сразу; автоматического retry нет.
При overlapping direct setEnabled вызовах revision объявляется до ожидания сериализации physical
inner apply; старые завершения не перетирают более новый desired. OnlineSwitch в production
остаётся последовательным владельцем применения; tests могут вызвать setEnabled напрямую для ABA.
Это правило защищает от stale completion без расширения интерфейса lifecycle.

Failed mapping в Gated: CoreFailure(error.problem NETWORK) → NO_NETWORK,
UNAVAILABLE → PROVIDER_DOWN, прочие CoreFailure → BROKEN; прочие Exception → BROKEN.
CancellationException всегда rethrow; не превращать её в Failed, не ловить Throwable/fatal JVM errors.
OnlineSwitch ловит non-cancellation Exception и продолжает collect следующей конфигурации;
он не перезапускает неудачную попытку автоматически. setEnabled Gated тоже rethrow после записи Failed.
Core.setEnabled использует withContext(io) без nullable swallowing helper; CoreFailure идёт наружу.

WaitLimit отсчитывается отдельно от начала каждого awaitConfiguration и действует также до
первой наблюдаемой конфигурации; TimedOut не меняет lifecycle, не отменяет apply и не запускает search.
Cancellation waiter пробрасывается, не отменяя чужую apply coroutine. Срок не реализуется polling/delay.
Readiness не запускает setEnabled/search; единственный production-владелец применения — OnlineSwitch.
Pending публикуется до inner.setEnabled, Applied только после успешного возврата текущей попытки.
Failed/TimedOut/Superseded не разрешают search. Нормализация: выключено означает пустой disabledProviders;
неизвестные provider IDs отфильтровываются одинаково с OnlineSwitch.
Ожидание до наблюдения запрошенной конфигурации не возвращает преждевременный Superseded.
Cancellation waiter не отменяет application-scoped apply; failure не останавливает следующие settings.
Первоначальный CountDownLatch/CompletableDeferred gate и cold stream сохраняются.
CoreOnlineRepository.setEnabled обязан передавать CoreFailure наружу; общие quietly/attempt не менять.

## Инварианты

- Старый Applied не проходит через новый Pending/Failed; revisions монотонны, ABA различается.
- Timeout ограничен существующим waitLimit (60 секунд по умолчанию).
- Без polling, arbitrary delay, повторного setEnabled из ViewModel или автоматического бесконечного retry.
- SearchViewModel и немедленная отмена удалённых провайдеров относятся к последующему yu5f.

## Файлы

**Планировщик меняет только:**
- `docs/work/yu5f-ready.md`.
- `app/src/test/kotlin/io/github/puflik/plinth/online/Yu5fOnlineReadinessTest.kt` (новый).
- `app/src/test/kotlin/io/github/puflik/plinth/online/Yu5fCoreReadinessContractTest.kt` (новый source contract guard: production final FFI фасад без JVM fake seam, зависимости/FFI менять запрещено).

**Исполнитель меняет только:**
- `app/src/main/kotlin/io/github/puflik/plinth/online/OnlineRepository.kt`.
- `app/src/main/kotlin/io/github/puflik/plinth/online/GatedOnlineRepository.kt`.
- `app/src/main/kotlin/io/github/puflik/plinth/online/OnlineSwitch.kt`.
- `app/src/main/kotlin/io/github/puflik/plinth/online/CoreOnlineRepository.kt` — только метод setEnabled.
- `app/src/main/kotlin/io/github/puflik/plinth/di/OnlineModule.kt` — только wiring capability к тому же Gated singleton, если необходимо.
- `docs/work/yu5f-ready.md` — только «Отчёт исполнителя».

**Не трогать:** всё остальное; явно SearchViewModel, Rust/CRDT/FFI, зависимости, существующие fixtures,
AGENTS.md, CHANGELOG.md, tasks*, decisions, план и соседний yu5f.

**Защищённые тесты:** оба новых файла выше плюс следующая неизменная база из d250290:
- `app/src/test/kotlin/io/github/puflik/plinth/online/GatedOnlineRepositoryTest.kt`.
- `app/src/test/kotlin/io/github/puflik/plinth/online/GatedOnlineRepositoryRadioTest.kt`.
- `app/src/test/kotlin/io/github/puflik/plinth/online/Yu4gGatedSearchInTest.kt`.
- `app/src/test/kotlin/io/github/puflik/plinth/online/OnlineSwitchTest.kt`.
- `app/src/test/kotlin/io/github/puflik/plinth/online/OnlineSwitchProvidersTest.kt`.
- `app/src/test/kotlin/io/github/puflik/plinth/online/FakeOnlineRepositoryTest.kt`.
- `app/src/test/kotlin/io/github/puflik/plinth/online/FakeOnlineRepositoryRadioTest.kt`.
- `app/src/sharedTest/kotlin/io/github/puflik/plinth/online/OnlineRepositoryContractTest.kt`.
- `app/src/androidTest/kotlin/io/github/puflik/plinth/online/CoreOnlineRepositoryContractTest.kt`.
- `app/src/androidTest/kotlin/io/github/puflik/plinth/online/CoreOnlineRepositoryCancelTest.kt`.
- `app/src/androidTest/kotlin/io/github/puflik/plinth/online/Yu4gSearchInCancelTest.kt`.

Git blob SHA-1 существующей базы, `git ls-tree -r d250290 -- <paths>`:

| Файл (пакет online, если не указано другое) | Blob |
|---|---|
| test/GatedOnlineRepositoryTest.kt | 6a7977a5f8b7ca32e8d3d8cad95617dc1b272c3e |
| test/GatedOnlineRepositoryRadioTest.kt | 6e7b001ede6d4ebdbbd78d8c3d072080c2e1b2cc |
| test/Yu4gGatedSearchInTest.kt | f4e85e2fd8729198f0380d8f3fbe14cf9a5376e4 |
| test/OnlineSwitchTest.kt | 5767fdb821fe62172b8953b3ed60b4c5c3905bfc |
| test/OnlineSwitchProvidersTest.kt | a1a1905780f88930a29fd4ca22e0a3251ee0cb30 |
| test/FakeOnlineRepositoryTest.kt | cb71d78238931ab00b8b6a818eb4984a40d0826f |
| test/FakeOnlineRepositoryRadioTest.kt | 89f6ef93afafb2edaf652328640390156ba93b8c |
| sharedTest/OnlineRepositoryContractTest.kt | 84e22c3dcd4b368dd52743a142dd66d57f772d21 |
| androidTest/CoreOnlineRepositoryContractTest.kt | 075e2579d0dbfb4dd300a2a567961846e1f13a67 |
| androidTest/CoreOnlineRepositoryCancelTest.kt | 20fbdda8351d244b1dbb83660238832b4aec8711 |
| androidTest/Yu4gSearchInCancelTest.kt | 2edf54e7e6c1e9af4eed315e0f095153583eb2b5 |

При проверке защищены полные пути из списка выше, не только имена; taskfile может
пополняться разрешёнными отчётами, тесты не менять/не удалять/не игнорировать/не ослаблять.

## Тесты (написаны в коммите тестов)

`Yu5fOnlineReadinessTest` — 12 сценариев с реальным Gated, а для пути приложения —
реальным OnlineSwitch/FakeOnlineSettings, controlled inner и kotlinx.coroutines virtual clock:

1. Успешное enabled=true с disabled={IA}; второе включение обоих стоит перед registry change;
   readiness и consumer search ждут, inner.searchInCalls пуст. После release — новая Applied
   revision и ровно один непустой IA ответ; устаревший null не принимается.
2. После первого ack запрошенная другая конфигурация ещё не наблюдалась Switch: waiter
   остаётся pending; последующее settings emission завершает его Applied, не Superseded.
3. Await до старта Switch ждёт первое применение и сам ничего не применяет.
4. Второй apply NETWORK failure → Failed(NO_NETWORK), без search и ложного Applied;
   тот же Failed виден следующему waiter. Без автоматического retry; off/on в settings
   проходит через тот же живой Switch collector и восстанавливает поиск.
5. NETWORK/UNAVAILABLE/INTERNAL и unexpected Exception → оговорённые проблемы;
   Gated.setEnabled пробрасывает то же исключение, registry не обновлён.
6. Задержанное второе применение: 4999ms ещё pending, 5000ms TimedOut; search не вызван,
   apply не отменён; позднее release применяет config, но не запускает timed-out search.
7. До первого lifecycle observation тот же ограниченный timeout; пассивное ожидание.
8. Отмена одного waiter пробрасывает CancellationException; второй waiter и apply живы.
9. Привязанный pending waiter получает Superseded от более новой попытки, даже при ABA;
   физические inner calls сериализованы, старое завершение не перезаписывает новый desired.
10. Applied A → B, новое ожидание A не получает исторический ack; новое A имеет большую revision.
11. Два прямых применения одинакового значения получают разные revisions.
12. Неизвестные ID не создают лишних apply; disabled config нормализуется к пустому множеству.

`Yu5fCoreReadinessContractTest` — два source-contract теста, не native runtime:
setEnabled не использует attempt/quietly/runCatching, не ловит исключение, сохраняет connect/disconnect.
Существующий `attempt(...)` нарушает первый договор; runtime CoreFailure mapping проверяет
отдельный controlled inner из online-suite. Guard нужен, потому что PlinthCore/CoreOnline final,
JVM injection seam отсутствует, mocking/FFI/dependencies вне scope. Он намеренно фиксирует
выбранную простую forwarding-реализацию без catch; не заявляет доказательства native failure.

Ожидаемая причина compile RED online-suite: отсутствуют только заранее записанные
OnlineConfiguration, OnlineReady, OnlineReadiness и Gated.awaitConfiguration; reflection нет.
После устранения инфраструктурной блокировки до production нужно подтвердить compile RED
именно по этой новой API, исключив любые независимые синтаксические/fixture ошибки.
**На этом проходе RED не подтверждён: новые tests не компилировались и не исполнялись.**

## Проверки

Scoped JVM/compile/style, Gradle --no-daemon; JAVA_HOME `C:\Program Files\Android\openjdk\jdk-21.0.8`;
SDK `C:\Users\User\AppData\Local\Android\Sdk`; логи вне Git `C:\claude\Plinth-yu5f-ready-*.log`.
Сборки/кэш не копировать; без full app/Rust/phone. После неожиданного сбоя одна коррекция, затем стоп.

PowerShell окружение (включая Gradle cache path, иначе wrapper выбрал C:\.gradle):

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/openjdk/jdk-21.0.8'
$env:ANDROID_HOME = 'C:/Users/User/AppData/Local/Android/Sdk'
$env:GRADLE_USER_HOME = 'C:/Users/User/.gradle'
```

Команда baseline существующих защищённых JVM suites (последний запуск, **FAILED до compile**):

```powershell
./gradlew.bat --no-daemon -I C:/claude/Plinth-yu5f-ready-jvm.init.gradle -x :app:uniffiKotlin -x :app:cargoNdkBuild :app:testGithubDebugUnitTest --rerun --tests 'io.github.puflik.plinth.online.GatedOnlineRepository*Test' --tests 'io.github.puflik.plinth.online.Yu4gGatedSearchInTest' --tests 'io.github.puflik.plinth.online.OnlineSwitch*Test' --tests 'io.github.puflik.plinth.online.FakeOnlineRepository*Test' *> C:/claude/Plinth-yu5f-ready-base.log
```

Ограничение среды: временный init переназначает outputDir задачи uniffiKotlin на уже
существующие **generated Kotlin sources** main `C:/claude/Plinth/app/build/generated/uniffi/kotlin`,
а обе Rust-задачи исключены. Это read-only reuse sources, не перенос build caches.
`git diff d250290 main -- app/src/main/kotlin/io/github/puflik/plinth/ffi core build-logic`
пуст в момент проверки. Для воспроизведения на чистой машине нужен отдельно разрешённый
источник соответствующих generated Kotlin bindings; этот локальный init не переносимый.

Init `C:/claude/Plinth-yu5f-ready-jvm.init.gradle` содержит callback `gradle.projectsEvaluated`,
который без проверки вызывает `gradle.rootProject.project(':app')`. Он вызывается и для
included build-logic. Результат: plugin com.android.application не разрешён, потому что
`Project with path ':app' could not be found in project ':build-logic'`; log выше, BUILD FAILED in 6s.
Минимальная предлагаемая коррекция **для отдельной разрешённой задачи среды, здесь не применена**:

```groovy
gradle.projectsEvaluated {
    def app = gradle.rootProject.findProject(':app')
    if (app != null) {
        app.tasks.named('uniffiKotlin') {
            outputDir.set(new File('C:/claude/Plinth/app/build/generated/uniffi/kotlin'))
        }
    }
}
```

После исправления среды нужно подтвердить RED новых файлов (на baseline API compile RED
ожидаем), затем executor должен пройти тот же набор GREEN:

```powershell
./gradlew.bat --no-daemon -I C:/claude/Plinth-yu5f-ready-jvm.init.gradle -x :app:uniffiKotlin -x :app:cargoNdkBuild :app:testGithubDebugUnitTest --rerun --tests 'io.github.puflik.plinth.online.Yu5f*Readiness*Test' --tests 'io.github.puflik.plinth.online.GatedOnlineRepository*Test' --tests 'io.github.puflik.plinth.online.Yu4gGatedSearchInTest' --tests 'io.github.puflik.plinth.online.OnlineSwitch*Test' --tests 'io.github.puflik.plinth.online.FakeOnlineRepository*Test'
./gradlew.bat --no-daemon -I C:/claude/Plinth-yu5f-ready-jvm.init.gradle -x :app:uniffiKotlin -x :app:cargoNdkBuild :app:compileGithubDebugAndroidTestKotlin --rerun
./gradlew.bat --no-daemon -I C:/claude/Plinth-yu5f-ready-jvm.init.gradle ktlintCheck --rerun
git diff --check
```

Init ktlint filter допускает только два новых filename; production стиль исполнителя проверить
ещё в его разрешённых изменённых файлах (расширять внешний filter, не отключать правила).
AndroidTest command — compile защищённых core/cancellation tests, не их runtime execution;
телефон/эмулятор не запускались. Новые JVM suites, native compile и scoped ktlint на этом
проходе **не выполнены**. До реализации coordinator обязан разрешить/поручить исправление
среды и получить валидный RED; текущий локальный commit — определение неподтверждённой базы.

## Готово, когда

- Детерминированные tests отдельно; валидный RED подтверждён перед production (пока заблокирован средой).
- При runtime тестах 12 readiness и два source guards GREEN, существующая JVM база GREEN;
  защищённые native contracts компилируются, initial gate/stream interrupt/timeout неизменны.
- Applied revision truthful, pending/failure/timeout/superseded не дают consumer search;
  failure не убивает Switch, cancellation waiter не отменяет apply.
- DI aliases получают один Gated singleton; прямые Core/Fake не обходят readiness default.
- Перед исполнителем независимое Astra-согласование сигнатур/ABA; перед merge
  `git diff <коммит тестов> origin/work/yu5f-ready -- <все защищённые пути>` пуст и CI зелёный.

## Остановиться и написать, если

- Нужно расширять production scope или сомнителен защищённый тест/API.
- Проверки неожиданно упали повторно после единственной коррекции.
- Ошибка применения не может быть честно выведена из разрешённого Core.setEnabled.

## Отчёт планировщика

Фактически `gpt-6.1-sol`, `xhigh`; создание отдельного worktree потребовало разрешённой sandbox escalation для Git lock.
Дополнительные агенты не запускались. Созданы только taskfile и два согласованных новых test-файла;
production/fixtures/dependencies не менялись, чужие untracked не затронуты.

Ход проверок и честные пределы:
- Первый wrapper выбрал C:\.gradle и не создал lock (не compile/test failure).
- Одна коррекция GRADLE_USER_HOME + разрешённый cache access запустила JVM command,
  но автоматическая зависимость uniffiKotlin начала host Rust compilation. Немедленный
  Ctrl+C остановил цепочку; Rust-тестов, NDK, release/full app build не выполнялось.
  Прямой запуск JVM без исключения uniffiKotlin оказался шире разрешённого scope.
- Get-CimInstance Win32_Process с фильтром Plinth-yu5f-ready после остановки не нашёл
  собственных дочерних процессов; чужие процессы не трогались.
- Coordinator разрешил отдельную коррекцию окружения: excluded Rust tasks + reuse generated
  Kotlin, проверка равенства ffi/core/build-logic исходников main/base выполнена (diff пуст).
- Следующая попытка упала на callback included build; по требованию coordinator никаких
  дальнейших Gradle запусков. Тесты дописаны после остановки, **нет подтверждённого RED**.
- `git diff --check` выполнен; перед первым commit проверяется также staged diff всех трёх
  новых файлов. Только локальные commits; push оставлен coordinator после проверки baseline.
- Лог последней попытки `C:/claude/Plinth-yu5f-ready-base.log`; ранний wrapper/Rust вывод
  записывался в тот же файл и перезаписан последней попыткой, отдельного лога не осталось.

**Передача coordinator:** отдельная ограниченная коррекция init, затем baseline compile/runtime
и scoped style; только после валидного RED и Astra review запуск executor. Вопрос о scope
пользовательских данных отсутствует; для нового выявленного риска сохранности нужен Astra max.
Открытое review-решение: подтвердить объявление revision до mutex при overlapping direct
setEnabled и DI capability alias; это явный контракт плана, не скрытая реализация.

## Отчёт исполнителя

_Не начат; только после согласования плана._

## Ревью

_Не начато; независимый Astra xhigh. Если выяснится влияние на пользовательские данные — Astra max до кода._
