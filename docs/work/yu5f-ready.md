# yu5f-ready — подтверждение применённой online-конфигурации

> Файл задачи для исполнителя по `docs/work/TEMPLATE.md`.
> Планировщик пишет план и защищённые RED-тесты; production здесь не реализуется.

- **Ветка:** `work/yu5f-ready` · **Ворктри:** `/home/puflik/src/Plinth-yu5f-ready` (Linux Mint)
- **Основа:** `main` на `d250290` · **Окончательный коммит защищённых тестов:** `005ba5ea092f5c8858749761b59a3e0e9f3fd731` (назначен координатором 2026-11-09 после независимого B1 review PASS; исходный первый коммит — `9e008680e56a5f2a9ca6152c83523066e473ae71`, Linux compile RED подтверждён 2026-11-07).
- **Откуда:** `docs/work/yu5f.md` в ветке `work/yu5f`, «Передача будущему планировщику yu5f-ready», строки 238–270.
- **Данные пользователя:** нет; online lifecycle/registry, без журнала, CRDT, миграций, FFI/Rust.
- **Исследование и ревью:** `gpt-6-astra`, `xhigh`; независимое согласование плана до исполнителя.
- **Планировщик, фактически:** `gpt-6.1-sol`, `xhigh`.
- **Исполнитель:** `gpt-6.1-sol`, `xhigh`: ограниченная конкурентность и cancellation.
- **План до реализации:** Astra xhigh согласовал контракт 2026-11-08; B1 закрыт независимым узким review 2026-11-09, PASS; разрешение координатора на CoreOnlineRepository.setEnabled получено.
- **Статус:** реализация `1ded175` опубликована 2026-11-09; 66 JVM-тестов, AndroidTest compile и независимое ревью реализации PASS. На 2026-11-12 CI `42517435908` (`1ded175`) и `43063804434` (`bd04e34`) FAIL в ktlint защищённого теста; Rust последнего run PASS. Прежний scoped ktlint PASS не подтверждает полный стиль. Планировщик исправил только перенос строки; полный штатный ktlint PASS (34s), независимое ревью и новый baseline ожидаются; слияние ожидает GREEN.

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
   отдельные Step задерживают OFF и новую BOTH: после старого completion и после OFF
   newestWaiter ещё pending, registry остаётся OFF до release новой BOTH; итоговая
   revision совпадает с текущим ack, физические inner calls сериализованы по порядку.
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
По Linux checkpoint компиляция остановилась именно на объявленной новой API и
связанных ошибках вывода типов; runtime корректность fixtures ещё не проверена.
**На исходном Windows-проходе RED не был подтверждён. Linux compile RED подтверждён 2026-11-07; runtime tests не выполнялись (см. актуальный checkpoint ниже).**

## Проверки

### Исторические Windows-команды (не выполнять на Linux)

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
проходе **не выполнены**. Это исторический Windows-статус: Linux compile RED получен 2026-11-07;
актуальный блокер допуска исполнителя — B1 в ревью 2026-11-08.

## Готово, когда

- Детерминированные tests отдельно; валидный compile RED подтверждён на Linux 2026-11-07; перед production закрыт B1.
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

Начат 2026-11-09, Sol xhigh; worktree `/home/puflik/src/Plinth-yu5f-ready`,
ветка `work/yu5f-ready`, входной HEAD `6ff822f`, защищённый baseline `005ba5e`.
Подагенты не запускались; изменяются только пять разрешённых production-файлов
и этот отчёт. Защищённые тесты не меняются.

Реализованы API/capability, общая нормализация с копированием множества,
монотонные попытки до ожидания физической очереди, адресные waiter deferred,
отбрасывание stale completion и порядок physical apply. Очередь резервируется
вместе с revision; отменённая queued попытка сохраняет зависимость следующей
от предшественницы. Каждый waiter имеет собственный timeout и удаляется при
отмене, чужие применения не отменяет. Core.setEnabled пробрасывает ошибку;
Switch продолжает после non-cancellation Exception. DI aliases используют
один singleton Gated; initial gate/stream не менялись.

Первая запись в соседний worktree остановлена read-only filesystem; ни один
файл не был изменён. Продолжение использует разрешённую escalation для worktree.
Первый JVM runtime запуск: PASS, 2m13s, 45 tasks (15 executed,
30 up-to-date), суммарно 66/66 без failures/errors/skips;
`Yu5fOnlineReadinessTest`: 12/12, source guards: 2/2.
Лог `/tmp/plinth-yu5f-ready-jvm-run1-20261109.log`.
После него добавлена повторная проверка revision при возобновлении waiter,
чтобы уже разбуженный waiter также не вернул stale ack; предстоит финальный
повтор suite. Перенесена длинная строка DI. Это развитие реализации до
итоговой приёмки, падений runtime/compile пока не было.
Scoped ktlint финальных пяти production и двух новых protected tests: PASS,
57s, 11 tasks executed. Использован проверенный LAPTOP init, расширен только
внешний `/tmp` file filter; правила не отключались. Лог
`/tmp/plinth-yu5f-ready-lint-run1-20261109.log`.
Итоговый повтор после последней production-правки: PASS, 2m06s,
55 tasks (20 executed, 35 up-to-date). В одном Gradle запуске выполнены
`:app:testGithubDebugUnitTest --rerun` с шестью согласованными `--tests`
фильтрами и `:app:compileGithubDebugAndroidTestKotlin --rerun`.
JVM: 66 tests, 0 failures, 0 errors, 0 skipped; из них readiness 12 и source
guards 2. AndroidTest Kotlin реально компилировался, не FROM-CACHE.
Лог `/tmp/plinth-yu5f-ready-final-20261109.log`.

Окружение взято из LAPTOP: JDK21, Android SDK `/home/puflik/Android/Sdk`,
GRADLE_USER_HOME `/home/puflik/.gradle`, Rust `1.98.1`, IPv4 JVM option.
Исключена только `:app:cargoNdkBuild`; `uniffiKotlin` не исключалась,
штатно UP-TO-DATE. APK/device/native runtime не запускались. Предупреждения
старых тестов о coroutine opt-in и Compose deprecations не менялись.

`git diff --check` PASS. Diff от полного protected baseline
`005ba5ea092f5c8858749761b59a3e0e9f3fd731` по целиком `app/src/test`,
`app/src/sharedTest`, `app/src/androidTest` пуст — покрывает все 13 protected
paths. Production diff содержит только пять разрешённых файлов; Core diff
ограничен setEnabled, OnlineModule — singleton wiring. В taskfile изменён
только этот отчёт. Собственные Gradle sessions завершились exit 0,
фоновых сборок не оставлено. Неожиданных compile/runtime/lint FAIL не было;
первичный filesystem отказ произошёл до Gradle/записи production.

Локальный commit создаётся в `work/yu5f-ready`; push/независимое ревью/merge
оставлены координатору. Main, соседние worktree, граф, зависимости и
пользовательские данные не менялись. Граф остаётся позади production до
планового обновления волны. Исторический RED не повторялся.

## Ревью

Независимый Astra xhigh проверил план 2026-11-08; B1 закрыт 2026-11-09. Отдельное ревью реализации 2026-11-09 — PASS, полный отчёт ниже. CI и слияние проверяет координатор.

## Проверка координатора 2026-11-06

После отчёта планировщика исправлен только внешний init: `findProject(':app')`
и ранний возврат для included build-logic. Тесты и production не менялись.
Выполнен один ограниченный запуск с прежним окружением и исключением Rust tasks:
`compileGithubDebugUnitTestKotlin --rerun ktlintCheck --continue`.
Лог: `C:/claude/Plinth-yu5f-ready-coordinator-check.log`.

- Included-build ошибка снята. Следующая блокировка возникает раньше test compile,
  в `compileGithubDebugKotlin`: `Querying the mapped value of flatmap(provider(task
  'uniffiKotlin', class UniffiKotlin)) before task ':app:uniffiKotlin' has completed
  is not supported`. Исключение генератора через `-x` не завершает его provider;
  простой замены outputDir недостаточно. Других запусков координатор не делал.
- Scoped ktlint выполнен: 13 замечаний только в `Yu5fOnlineReadinessTest.kt:373`
  (12 argument-list-wrapping, 1 max-line-length). Изменять защищённые тесты исполнителю
  не разрешено; форматирование и новый опорный hash остаются подготовке/ревью.
- Команда завершилась BUILD FAILED за 24s: 47 tasks, 8 executed, 21 FROM-CACHE,
  18 UP-TO-DATE. Свежий compile RED и runtime-проверки не получены.
- Diff защищённых тестов относительно `9e00868` пуст. Ветка содержит только
  два новых файла тестов и задачу; source-contract проверки не заменяют native runtime.
- После планировщика окно 68%, неделя 39%. По правилу автора нового агента
  не запустили; сброс окна 2026-11-06 14:09:34 +05:00. Следующая ограниченная
  работа — настройка JVM-запуска на Luna, затем подготовка проверенного RED,
  форматирование и независимое Astra-review; production пока не начинать.

## Подготовка стиля 2026-11-08

- Фактическая модель/effort: `gpt-6-luna`, `medium`.
- Единственное изменение теста: разрешённые переносы цепочки `containsExactly(...).inOrder()` в `Yu5fOnlineReadinessTest.kt` около строки 373; аргументы и утверждение сохранены.
- `ktlintCheck` ограничивался init-фильтром двумя новыми `Yu5f*Readiness*Test.kt`. Без повышенного доступа первая попытка остановилась на read-only Gradle cache; после разрешённого доступа init-скрипт ошибся разрешением класса внутри closure. Одна коррекция с import завершилась ошибкой компиляции init: `BaseKtlintCheckTask` недоступен init classpath. По лимиту попыток остановился; результат ktlint не получен.
- `git diff --check` чисты; изменены только разрешённые тест и этот раздел отчёта. `git diff -w` относительно `9e00868` остаётся непустым из-за переносов строк; условие коммита не выполнено, поэтому коммит не создан.

## Linux checkpoint 2026-11-07 (актуализирован 2026-11-08)

Источник: основной checkout, `docs/work/CODEX.md`, «Точка продолжения», и
`docs/work/LAPTOP.md`, «Проверка Linux 2026-11-07». Это git-tracked checkpoint
координатора; `/tmp/plinth-yu5f-ready-linux-compile.log` к ревью отсутствует.
Повторно JVM/Rust/NDK/APK/устройство и полный Gradle tests не запускались.

- `uniffiKotlin` и `compileGithubDebugKotlin` — PASS 7 ноября.
- `compileGithubDebugUnitTestKotlin` — ожидаемый compile RED на отсутствующих
  `OnlineConfiguration`, `OnlineReadiness`, `OnlineReady`, `awaitConfiguration`
  и связанных ошибках вывода типов; exit 1 за 17m34s.
- Runtime 12 readiness tests, два source guards и существующая JVM-база
  ещё не выполнены; этот compile RED не является runtime-проверкой.
- JDK 21.0.13: `/home/puflik/.local/opt/jdk21`; SDK:
  `/home/puflik/Android/Sdk`; Gradle cache: `/home/puflik/.gradle`;
  `RUSTUP_TOOLCHAIN=1.98.1`, `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`.
  Команда 7 ноября: `bash ./gradlew --no-daemon -x :app:cargoNdkBuild :app:compileGithubDebugUnitTestKotlin --rerun`.
- Старый Windows init и `-x :app:uniffiKotlin` не переносить: нужны настоящие
  generated bindings текущего checkout. До реализации повторный compile не нужен.
  После реализации scoped JVM GREEN запускается с Linux-окружением выше,
  `-x :app:cargoNdkBuild`, прежними `--tests` фильтрами новых и защищённых suites;
  `uniffiKotlin` не исключать. Нативные runtime проверки этим не подтверждаются.

## Ревью плана 2026-11-08

Фактически `gpt-6-astra`, `xhigh`; независимый researcher, без подагентов.
Вход: HEAD `6fa3e6b`, исходные tests `9e00868`, незакоммиченные переносы Luna.
Окна при собственной проверке 25%/76% (перед запуском координатор видел 18%/75%).
Прочитаны правила основного checkout и ограниченные контексты; `graphify query`
в основном root с budget 650 завершился, граф устарел, вывод сверялся с кодом.
Экстракция/обновление графа не выполнялись; токены семантической экстракции — 0.

**Вердикт: BLOCKED до устранения B1; production не разрешён.** API и выбранное
разделение lifecycle/readiness согласованы, но имеющийся ABA-тест не защищает
ключевой запрет раннего Applied. Семантика плана и защищённых tests этим ревью
не изменяется; требуемую доработку выполняет отдельный планировщик.

**B1 — ложное раннее Applied проходит защищённый ABA-сценарий.**
`Yu5fOnlineReadinessTest.kt:277–314`, тест
`new desired revision supersedes bound waiter and old completion cannot win ABA`:
задержана только старая попытка. После `delayed.release.complete(Unit)` один
`runCurrent()` завершает старую, OFF и новую BOTH; проверяется уже окончательный
registry. Утверждение о revision требует лишь `> firstRevision`.
Реализация, ошибочно публикующая Applied текущей revision при завершении старой
попытки BOTH, разбудит newestWaiter раньше применения новой BOTH, затем оставшиеся
применения поправят registry до assertions. Даже возврат старой revision > первой
этим утверждением не исключён. Это статический контрпример, не выполненный mutant test.

Точная доработка планировщику: перед продолжением старой попытки поставить в
`inner.steps` отдельные заблокированные Step для OFF и новой BOTH. Освободить
старую попытку, выполнить `runCurrent()`, проверить старт OFF и незавершённый
newestWaiter: это наблюдаемая граница сразу после старого matching completion.
Затем освободить OFF, выполнить `runCurrent()`, проверить старт новой BOTH,
registry OFF и всё ещё незавершённый newestWaiter; consumer search, если добавлен,
также не должен начаться. Затем освободить только новую BOTH и проверить
Applied, корректный registry и соответствие возвращённой revision актуальному ack.
Сохранить проверки Superseded старого waiter, порядка физических вызовов и ABA;
после изменения теста обновить baseline и повторно согласовать этот сценарий.

Сверенные инварианты и границы:

- `OnlineRepository.kt` сохраняет основной API; capability отдельная. Snapshot
  нормализуется копией множества, OFF даёт emptySet, неизвестные ID исключаются
  перед distinct и сравнением ack; новый direct setEnabled создаёт новую revision.
- `GatedOnlineRepository.kt:35–46` открывает исходные one-shot ворота в finally;
  это сохраняется независимо от нового lifecycle. Pending/Failed не дают Applied;
  mutex нельзя держать во время awaitConfiguration. Регистрация revision и очередь
  физических apply должны сохранять порядок: один mutex без контроля регистрации
  при конкурентных потоках сам по себе ещё не доказывает этот инвариант.
- 12 тестов покрывают пассивное ожидание, предваряющий Switch запрос, timeout
  4999/5000ms, waiter cancellation без отмены apply, failure mapping/recovery,
  одинаковые прямые попытки и нормализацию. B1 ограничивает доказательство ABA.
  Отмена самого apply, копирование mutable Set и реальная многопоточность отдельно
  не проверены; при реализации нужны осмотр этих ветвей и соблюдение записанного
  контракта, без превращения CancellationException в Failed.
- `CoreOnlineRepository.kt:40–49`: разрешённого изменения одного setEnabled
  достаточно для withContext(io) и передачи исключений; общие attempt/quietly
  не менять. Два source guards проверяют форму forwarding, не native failure.
  NETWORK→NO_NETWORK, UNAVAILABLE→PROVIDER_DOWN, остальные Exception→BROKEN;
  Throwable не ловить. OnlineSwitch должен сохранить collect после non-cancellation
  Exception; отмена scope должна выйти наружу, автоматический retry отсутствует.
- `OnlineModule.kt` сейчас создаёт Gated внутри OnlineRepository provider;
  нужен concrete singleton и два alias к нему. В двух новых tests DI wiring
  не проверяется; равенство экземпляров должно быть подтверждено ревью реализации,
  а генерация Hilt — соответствующей компиляцией. Второй Gated создаёт другой barrier.
- База Gated tests защищает failed initial apply, timeout, cancellation и stream
  interruption; radio/searchIn suites сохраняют исходный gate. Shared/native
  контракты остаются защищёнными; compile не заменяет их runtime. Текущая правка
  не затрагивает production, журнал, CRDT, миграции, relinking или recovery данных;
  data-reviewer не требуется для этого объёма. При расширении — отдельный Astra max.

Подготовка стиля: ошибочный перенос `inner.attempts` возвращён в исходную форму;
исправлен только соседний длинный `assertThat(revision(awaitConfiguration(...)))`
переносами и trailing commas. Значения/вызовы/assertions сохранены. Непустой
`git diff -w` при переносе строк не доказывает семантическое изменение.
Cached plugin JAR содержит `BaseKtLintCheckTask` (KtLint, другой регистр);
временный init использует проверенный `setSource(Object)` через runtime-задачу,
без typed import. Проверяются ровно два новых файла, перед выполнением сверяется
полный source set; compile/test/cargo/uniffi tasks приложения запрещены guard.
Настройка: `/tmp/plinth-ready-review-ktlint.init.gradle`;
лог: `/tmp/plinth-ready-review-ktlint.log`.

**Scoped ktlint: PASS, exit 0, BUILD SUCCESSFUL in 2m 10s, 11 tasks executed.**
Команда (с Linux-окружением выше):
`bash ./gradlew --no-daemon -I /tmp/plinth-ready-review-ktlint.init.gradle :app:plinthReadyScopedLint --rerun-tasks`.
Выбрана существующая `:app:runKtlintCheckOverAndroidTestDebugSourceSet`, но её
source явно заменён ровно двумя новыми JVM test-файлами; имя задачи не означает
проверку androidTest. Лог подтверждает оба filename и выполнение проверки.
Вспомогательный included build-logic перекомпилировался; app compilation,
unit-test runtime, Rust/UniFFI, NDK, APK и устройство не запускались.
Потребовалась одна новая обоснованная настройка, коррекция после запуска не нужна.

`git diff --check` — PASS; просмотренный test diff содержит только переносы
в одном утверждении и три trailing commas. По сравнению с `9e00868` единственная
правка в `app/` — этот тест; production, второй новый тест, fixtures и вся старая
защищённая база неизменны. В локальный коммит входят только taskfile и стиль;
итоговый hash baseline назначает координатор после коммита. Push не выполнялся.


## Доработка B1 планировщиком 2026-11-09

Автор после сообщения о недельном пороге потребовал продолжить: «ну дальше то
работай». Координатор трактует это как указание выполнить ограниченную доработку
B1 и её независимое ревью сейчас; постоянные бюджетные правила не изменяются.
Окна перед передачей — 17%/88%. Один агент, свежий контекст, без подагентов.

Планировщик: Sol xhigh. Работать в существующем worktree и ветке
`work/yu5f-ready`, вход `b0e2373`. Разрешено менять только:
- ABA-тест `new desired revision supersedes bound waiter and old completion
  cannot win ABA` в `app/src/test/kotlin/io/github/puflik/plinth/online/Yu5fOnlineReadinessTest.kt`;
- этот taskfile: отчёт доработки и уточнение сценария в перечне тестов при необходимости.

Выполнить точный trace B1 из предыдущего раздела: отдельные заблокированные
Step для OFF и новой BOTH, проверки незавершённого newestWaiter после старого
завершения и после OFF, registry OFF до завершения новой BOTH, финальный Applied
соответствует текущему ack. Сохранить прежние утверждения Superseded и порядок
применений. Не менять другие тесты, production, API-контракт и зависимости.
Окончательный baseline назначает координатор после независимого ревью.

Проверка: git diff --check, просмотр ограниченного diff, scoped ktlint ровно двух
новых тестов по проверенному init из основного docs/work/LAPTOP.md. Нет нужды
повторять заведомый compile RED отсутствующей API или собирать APK/Rust. Отчёт
явно разделяет выполненный lint и невыполненный runtime; объяснить, на какой новой
промежуточной assertion ловится контрпример B1. После одного исправления повторная
ошибка проверки означает стоп. Работу зафиксировать локальным коммитом через -F;
публикация — координатором. Цель: не более 10–12 вызовов, без широкого аудита.

### Отчёт доработки B1 — планировщик

Фактически `gpt-6.1-sol`, `xhigh`; отдельный планировщик, без подагентов.
Вход `b0e2373`, ветка `work/yu5f-ready`; незакоммиченный раздел координатора
выше сохранён и включается в локальный коммит. Прочитаны правила основного
checkout, шаблон и ограниченные разделы задачи/CODEX/LAPTOP; новый вопрос к
кодовой базе не возник, ранее выполненный координатором graphify query не повторялся.

На входе подтверждён статический пробел B1: прежний тест отпускал старую попытку
и проверял только итог после всех применений. План доработки: поставить отдельные
Step для OFF и новой BOTH, проверить границы до каждого release, после новой
BOTH сравнить revision waiter с текущим ack. Production/API/остальные тесты
не изменены. Runtime и compile RED повторно не выполнялись по назначенному
объёму, исходный Linux compile RED 2026-11-07 — исторический.

Доработан только назначенный ABA-тест: OFF/новая BOTH удерживаются отдельными
Step; после старого completion проверяются старт OFF, отсутствие старта новой
BOTH и pending newestWaiter; после OFF — старт новой BOTH, registry OFF и pending
newestWaiter. После release новой BOTH проверяется Applied, равенство revision
текущему ack, прежние Superseded и порядок применений сохранены. Первая новая
`assertThat(newestWaiter.isCompleted).isFalse()` после `delayed.release` ловит
описанный B1 ранний Applied до запуска новой BOTH; следующая — ранний ack от OFF.
Это статическая проверка trace, не выполненный mutant/runtime test.

Фактическая проверка 2026-11-09:
- `git diff --check` — PASS; ограниченный diff просмотрен.
- Сравнение до/после выделенного ABA-метода подтвердило неизменность остального
  test-файла; список изменённых tracked-файлов — только этот тест и taskfile.
- Единственный scoped lint по init из `docs/work/LAPTOP.md` — PASS, exit 0,
  `BUILD SUCCESSFUL in 1m 7s`, 11 tasks executed, `--rerun-tasks`.
  Команда: `bash ./gradlew --no-daemon -I /tmp/plinth-ready-ktlint.init.gradle
  :app:plinthReadyScopedLint --rerun-tasks` с согласованным Linux env.
  Проверены ровно `Yu5fCoreReadinessContractTest.kt` и `Yu5fOnlineReadinessTest.kt`;
  init подтвердил точный набор source-файлов и отсутствие app compile/test,
  cargo/UniFFI задач. Gradle пересобрал build-logic, runtime этим не проверен.
  Лог: `/tmp/plinth-yu5f-ready-b1-ktlint-20261109.log`.
- Fresh compile/runtime RED, mutant run, APK, Rust и устройство не запускались;
  новый trace проверен статически, readiness API по-прежнему отсутствует.

Доработка подготовлена к независимому Astra xhigh review; production-исполнитель
не допущен этим отчётом. Окончательный baseline назначает координатор после
ревью. Локальный коммит включает раздел координатора; push оставлен координатору.


## Узкое независимое ревью B1 2026-11-09

Фактически `gpt-6-astra`, `xhigh`; независимый researcher, без подагентов.
Вход: чистый worktree `work/yu5f-ready`, HEAD `005ba5e`; предмет — только diff
`b0e2373..005ba5e` ABA-метода `Yu5fOnlineReadinessTest.kt`. Другие контракты
остаются в объёме предыдущего ревью 2026-11-08. Назначено только дополнение
этого раздела, без production-кода, коммитов и push. Окна 28%/89% проверены
координатором перед запуском; разовое продолжение B1/review разрешено автором.

Прочитаны AGENTS.md основного checkout/worktree, README, AGENT_ROLES, CODEX
и ограниченные разделы задачи. Выполнен `graphify query` с budget 1000;
актуальный сценарий в отстающем графе не найден, проверяется прямой test diff.
Граф не обновлялся. На входе diff добавляет независимые Step для OFF/newest
и две промежуточные проверки незавершённого newestWaiter; прежние финальные
assertions сохранены либо усилены. Сверка helper/registry завершена ниже.

**Вердикт: PASS в узком объёме B1; блокер предыдущего ревью устранён.**
Проверен test diff на `005ba5e`; окончательный baseline и переход к исполнителю
назначает координатор. Это согласование защищённого сценария до реализации,
не подтверждение runtime, CI или корректности будущей production-реализации.

Доказательство по текущему `Yu5fOnlineReadinessTest.kt`:

- Строки 283–302 сохраняют исходный ABA: старая BOTH физически заблокирована,
  OFF уже объявляет новую revision и завершает oldWaiter как Superseded,
  затем объявлена newest BOTH; physical attempts остаются двумя. Поэтому
  добавленные в очередь `offStep`/`newestStep` (304–307) ещё никто не потребил.
- `Step` (431–436) содержит независимые незавершённые started/release;
  `ControlledOnline.setEnabled` (447–463) сначала пишет physical attempt,
  забирает Step, отмечает started и ждёт release; registry меняется только
  после release. Это делает каждую новую границу наблюдаемой. У используемого
  FakeOnlineRepository setEnabled сначала записывает enabled/disabled, затем
  applied (95–101); последняя запись applied=false вместе с disabled=emptySet
  действительно означает registry OFF, а не лишь журнал начала вызова.
- После release старой BOTH и runCurrent (308–318) oldApplication завершён,
  OFF вошёл в inner и заблокирован, newest BOTH ещё не вошла. Контрпример B1,
  публикующий Applied текущей revision из старого matching completion,
  завершит newestWaiter и нарушит assertion на строке 316. Последующие apply
  теперь не могут успеть исправить registry до этой проверки.
- После release OFF и runCurrent (320–335) OFF завершён, newest BOTH только
  стартовала и заблокирована; строка 326 опять требует незавершённый waiter,
  строки 327–328 подтверждают registry OFF. История attempts проверяет точный
  физический порядок initial YOUTUBE_ONLY → старая BOTH → OFF → новая BOTH.
- Только release newest BOTH (337–349) разрешает итоговый Applied. Helper
  revision (418–420) проверяет сам тип результата; сравнение с firstRevision
  сохранено (342), сравнение с повторным awaitConfiguration(BOTH) (343)
  привязывает возвращённый результат к актуальному ack после применения.
  Финальные Superseded, завершённые application jobs и история registry
  сохранены. Равенство двух ack само по себе не доказывает всю монотонность
  revision; здесь оно дополняет две границы и прежний набор контрактов.

Ошибочных новых требований не обнаружено: последовательные physical apply
и объявление revision до сериализации уже прямо заданы контрактом (72–75).
Прямые overlapping setEnabled здесь разрешены для ABA. runCurrent разделяет
готовые continuations без advanceTimeBy, polling или произвольной задержки;
новые проверки не требуют искусственного таймаута и не меняют API-семантику.
Consumer search в этом сценарии отсутствовал и не добавлен; для B1 достаточно
проверки самого readiness waiter, которая теперь наблюдает ранний Applied.

Фактические проверки этого ревью:
- Статическая трассировка указанного diff и относящихся к нему helper/registry.
- Программное сравнение `b0e2373`/`005ba5e`: весь test-файл вне ABA-метода
  побайтно совпадает; рабочий test-файл совпадает с `005ba5e`. Единственная
  переписанная старая assertion — сравнение revision, её условие сохранено.
- `git diff --check b0e2373..005ba5e` и `git diff --check` — PASS. В проверяемых
  коммитах изменены только назначенный test-файл и taskfile; этим reviewer
  изменён только новый раздел taskfile. Production и другие tests не тронуты.
- Scoped ktlint PASS принят из отчёта планировщика (два файла, 11 tasks,
  1m 7s); заново Gradle/compile/Rust/APK/устройство не запускались.
  Runtime и mutant не выполнялись: readiness API ещё отсутствует;
  обнаружение B1 обосновано статическим trace, не выполненным mutant test.
- Семантическая экстракция графа не выполнялась, её token cost — 0;
  граф остаётся отстающим. Новых решений по CRDT/миграциям/relinking/recovery
  нет, data-reviewer для этой правки не требуется.


## Приёмка подготовки координатором 2026-11-09

Независимый Astra xhigh review B1 — PASS. Зафиксирован окончательный baseline
защищённых тестов `005ba5ea092f5c8858749761b59a3e0e9f3fd731`; последующие правки
исполнителя сравнивать с ним по полному списку защищённых путей выше.
Сравнение `9e00868..005ba5e -- app core` показывает только назначенный новый
readiness test; существующая база и production сохранены. Утверждение о
закрытии B1 основано на независимом статическом trace, не runtime/mutant.
Scoped ktlint новой подготовки PASS, diff check PASS. План можно передавать
исполнителю Sol xhigh; реализация, её runtime проверки и последующее независимое
ревью остаются отдельным этапом. Старые BLOCKED ниже заголовка — история,
действующий статус указан в шапке. Граф не обновлялся.

## Ревью реализации 2026-11-09

Фактически `gpt-6-astra`, `xhigh`; независимое ограниченное ревью, без подагентов.
Вход: опубликованный HEAD `1ded1758d4b171d96cd2c2fc065ede4ebd8c9902`,
production diff от `6ff822f`, protected baseline `005ba5ea092f5c8858749761b59a3e0e9f3fd731`.
Координатор передал разрешение автора продолжить в остатке бюджета; перед запуском
77%/97%. Прочитаны правила основного checkout, контракт и отчёт исполнителя;
`graphify query` выполнен с budget 400, граф отстаёт, доказательства сверяются с кодом.
Семантическая экстракция/обновление графа не запускались (токены экстракции: 0).

Промежуточный trace: reservation physical apply и revision меняются вместе под
`lifecycleLock`; отменённый queued вызов завершает свой tail только после
предшественника. `finish` отбрасывает чужую Attempt, а waiter повторно проверяет
revision после пробуждения. Проверка остальных границ продолжается.
Gradle/Rust не повторяются; тестовые результаты берутся из отчёта исполнителя.

**Вердикт реализации: PASS. Блокирующих замечаний в назначенном diff не найдено.**
Это заключение по реализации и локальным свидетельствам; CI опубликованного HEAD
не проверялся этим reviewer, PASS не означает CI GREEN и не выполняет слияние.

Проверенные инварианты (все пути относительно корня worktree):

- `app/src/main/kotlin/io/github/puflik/plinth/online/GatedOnlineRepository.kt:97`:
  revision, current и applyTail резервируются одним monitor до suspension. Поэтому
  перестановка потоков между begin и await не переставляет physical apply. На
  `:53` попытка ждёт предшественницу; на `:69` отменённая queued B сохраняет
  зависимость C от незавершённой A через A.finished → B.finished → C. Вызов inner
  отменённой B не выполняется; CancellationException пробрасывается (`:56`).
- Там же `:112`: чужое завершение не меняет current. На `:97` сначала обновляются
  current и все привязки, затем завершаются старые waiters; Unconfined reentry
  видит целостную revision. На `:88` уже разбуженный waiter проверяет revision
  снова: begin между complete и resume даёт Superseded, включая ABA. Snapshot
  списков исключает обход изменяемого набора во время reentry. Усиленный ABA
  runtime-сценарий — `app/src/test/kotlin/io/github/puflik/plinth/online/Yu5fOnlineReadinessTest.kt:277`;
  обе промежуточные границы не допускают раннего Applied.
- Там же `:75`: timeout начинается на каждом await, охватывает Unobserved и
  Pending; до совпадения configuration revision остаётся null, чужая попытка
  не выдаёт преждевременный Superseded. Timeout/отмена удаляют только собственный
  waiter; deferred не связан с apply Job. Отменённая apply не превращается в
  Failed: без следующей попытки её Pending ограничен timeout каждого waiter.
  Readiness не вызывает setEnabled/search.
- `app/src/main/kotlin/io/github/puflik/plinth/online/OnlineRepository.kt:147`:
  intersect создаёт отдельное множество известных ID, выключенная конфигурация
  имеет emptySet. Одинаковая функция используется обоими входами Gated и
  `app/src/main/kotlin/io/github/puflik/plinth/online/OnlineSwitch.kt:20`; mutation
  исходного множества после нормализации не меняет сохранённую конфигурацию.
  Это snapshot через Kotlin read-only Set, не runtime-unmodifiable wrapper;
  контракт не требует защиты от намеренного cast/mutation внутри inner.
  enabled=true со всеми отключёнными остаётся отличным от enabled=false.
- `app/src/main/kotlin/io/github/puflik/plinth/online/GatedOnlineRepository.kt:56`:
  cancellation отделена от Exception; NETWORK → NO_NETWORK, UNAVAILABLE →
  PROVIDER_DOWN, остальные ошибки → BROKEN, исходное исключение rethrow. Fatal
  JVM Error не ловится. `app/src/main/kotlin/io/github/puflik/plinth/online/CoreOnlineRepository.kt:40`
  меняет только forwarding setEnabled на withContext(io).
  `app/src/main/kotlin/io/github/puflik/plinth/online/OnlineSwitch.kt:23` сохраняет
  последовательный collect, distinct нормализованных значений и продолжение
  после Exception без retry; cancellation завершает collector.
- `app/src/main/kotlin/io/github/puflik/plinth/online/GatedOnlineRepository.kt:67`
  сохраняет открытие initial deferred/latch при любом исходе, делегирование и
  cold stream `:171` не менялись.
  `app/src/main/kotlin/io/github/puflik/plinth/di/OnlineModule.kt:38` создаёт один
  scoped Gated, а providers `:47` и `:50` возвращают именно его; отсутствие
  отдельного scope на alias не создаёт дополнительных экземпляров.

Проверки и пределы: reviewer не запускал Gradle/Rust и не менял source/tests.
Прочитаны имеющиеся XML: 9 JVM suites, **66 tests, 0 failures/errors/skipped**;
из отчёта исполнителя — 12 readiness + 2 source guards внутри этого набора.
Существующий `/tmp/plinth-yu5f-ready-final-20261109.log` подтверждает реальные
`:app:testGithubDebugUnitTest` и `:app:compileGithubDebugAndroidTestKotlin`,
BUILD SUCCESSFUL, 2m06s, 20 executed/35 up-to-date.
`/tmp/plinth-yu5f-ready-lint-run1-20261109.log` подтверждает PASS, 57s, 11 executed;
scope пяти production и двух tests взят из отчёта исполнителя. Неизменность
всех test/sharedTest/androidTest относительно baseline ранее проверена координатором.
Diff просмотрен: только пять разрешённых production-файлов и отчёт исполнителя;
Core — только setEnabled, OnlineModule — только wiring.

Отмена queued apply, мутация исходного Set после копирования и precise
complete→resume race проверены статическим trace, отдельного runtime-теста
этих границ в 12 readiness tests нет; многопоточный stress не выполнялся.
Source guards не доказывают native CoreFailure runtime; AndroidTest compile
не доказывает работу на устройстве. Native runtime, телефон, CI и слияние
остаются за пределами этого ревью. CRDT, миграции, relinking/recovery и данные
пользователя этим diff не меняются; решений о них reviewer не принимал.


## Возобновление координатором 2026-11-11

Реализация `1ded175` опубликована 9 ноября; независимый отчёт Astra PASS
сохранился незакоммиченным после исчерпания окна. Сейчас он фиксируется вместе
с актуальным статусом. Повторно подтверждены diff check и полная неизменность
`app/src/test`, `app/src/sharedTest`, `app/src/androidTest` относительно `005ba5e`.
9 ноября автоматическая проверка разрешения не смогла выполнить чтение GitHub CI
из-за исчерпанного лимита; это не отказ по безопасности реализации. На входе
11 ноября окна 11%/2%, прямой gh api вновь не подключается к api.github.com.
CI run `42517435908` относится к `1ded175`; финальный результат ещё не подтверждён.
До результата GREEN слияния нет; локальные проверки и review заново не запускаются.


## Корректировка планировщика: полный ktlint 2026-11-12

Фактически `gpt-6.1-sol`, `xhigh`; узкая задача планировщика, без подагентов.
Входной HEAD `bd04e34ee48ea2bf23b250dda79b877d51659eca`, ветка `work/yu5f-ready`,
worktree `/home/puflik/src/Plinth-yu5f-ready`; перед изменением tracked clean.
Разрешены только несемантический перенос `.inOrder()` в защищённом
`app/src/test/kotlin/io/github/puflik/plinth/online/Yu5fOnlineReadinessTest.kt:318`
и этот отчёт со статусом CI. Production и остальные тесты не меняются.

Координатор подтвердил CI `43063804434` на `bd04e34`: FAIL в Android ktlint,
Rust PASS; production run `42517435908` на `1ded175` также FAIL.
Лог `/tmp/plinth-ready-ci-job-20261112.log` содержит единственную диагностику
`Yu5fOnlineReadinessTest.kt:318:110 Expected newline before '.'`.
Полный локальный baseline-запуск координатора завершился до этой правки:
exit 1, 1m11s, 30 tasks (19 executed, 1 from cache, 10 up-to-date), та же
единственная диагностика. Лог `/tmp/plinth-ready-full-ktlint-20261112.log`.
Прежний scoped init больше не используется; его PASS не доказывает чистый стиль.

План: ровно один перенос `.inOrder()` на следующую строку, один полный
`bash ./gradlew --no-daemon ktlintCheck` с окружением актуального
`/home/puflik/src/Plinth/docs/work/LAPTOP.md`, без init/filter/exclusions;
затем diff check, whitespace diff и literal token equivalence с baseline
`005ba5ea092f5c8858749761b59a3e0e9f3fd731`. Новые регрессионные тесты
для чистого форматирования не нужны: существующий полный ktlint воспроизвёл
ошибку; сценарии и assertions должны совпасть. JVM повтор не требуется.
При повторном FAIL дальнейших исправлений и запусков не будет.

Выполнен только перенос перед `.inOrder()`. Единственный полный повтор:
**PASS**, exit 0, 34s, 29 tasks (2 executed, 27 up-to-date).
`:app:runKtlintCheckOverTestSourceSet` и `:app:ktlintTestSourceSetCheck`
реально выполнены, без FROM-CACHE/UP-TO-DATE. Команда:
`bash ./gradlew --no-daemon ktlintCheck`, без scoped init, фильтров и исключений.
Лог `/tmp/plinth-ready-format-full-ktlint-20261112.log`, exit code
`/tmp/plinth-ready-format-full-ktlint-20261112.exit`.
Окружение LAPTOP: JDK21, Android SDK `/home/puflik/Android/Sdk`,
GRADLE_USER_HOME `/home/puflik/.gradle`, Rust `1.98.1`, IPv4 JVM option.
JVM/AndroidTest/Rust не повторялись для whitespace; Gradle сессия завершена,
фоновых сборок нет. По сообщению координатора, старый scoped init удалён
из основной LAPTOP-памятки; новые дополнительные проверки не добавлялись.

`git diff --check` PASS. `git diff -w` теста показывает только разделение
одной строки на две (сам перенос строки этим флагом не скрывается).
Literal/token equivalence относительно полного baseline `005ba5e` PASS:
байты файла точно равны baseline после единственной разрешённой вставки
newline/indent перед `.inOrder()` вне строковых литералов. Все токены,
literals и assertions сохранены; остальные байты теста совпадают.
Все другие `app/src/test`, `app/src/sharedTest`, `app/src/androidTest`
неизменны относительно baseline; production неизменен относительно входного HEAD.
В diff только целевой защищённый тест и этот taskfile.

Новый protected baseline пока не назначается: это решение координатора
после независимого ревью. Commit этого этапа создаётся локально;
push до ревью не разрешён. CI на новой правке ещё не запускался,
последний опубликованный CI остаётся FAIL; слияние не выполняется.


## Корректировка планировщика: detekt ABA 2026-11-12

Фактически `gpt-6.1-sol`, `xhigh`; узкая задача планировщика без подагентов.
Входной HEAD `a0fc4e9`, ветка `work/yu5f-ready`,
worktree `/home/puflik/src/Plinth-yu5f-ready`. По решению координатора
разрешены только ABA-метод, один private helper в
`app/src/test/kotlin/io/github/puflik/plinth/online/Yu5fOnlineReadinessTest.kt`
и этот отчёт. Имеющаяся правка `GatedOnlineRepository.kt` принадлежит
координатору; она не редактируется и не включается в коммит планировщика.

Исходное падение подтверждено существующим полным журналом
`/tmp/plinth-ready-detekt-20261112.log`: `:app:detekt` FAIL, exit 1,
LongMethod ABA-теста 66 при максимуме 60; отдельное production-замечание
TooGenericExceptionCaught закрывает координатор. Новый поведенческий тест
не требуется: рефакторинг сохраняет существующий защищённый сценарий,
а регрессией формата служит штатный detekt.

План и выполненная правка: три существующих assertions сразу после проверки
`newestWaiter.isCompleted == false` после `offStep.release` извлечены в
несуспендирующий `assertOffAppliedBeforeNewestCompletion(inner)` на том же
месте. Выражения assertions и их порядок неизменны; helper ничего не
изменяет и не переключает dispatcher. Остальные assertions, gates и
`runCurrent()` сохранены. Программное обратное преобразование полностью
восстановило исходные байты теста.

Назначены ровно один полный `bash ./gradlew --no-daemon ktlintCheck detekt`
и затем свежий `:app:testGithubDebugUnitTest --rerun` для readiness-класса
с единственным исключением `-x :app:cargoNdkBuild`; `uniffiKotlin`
не исключается. Логи в `/tmp`; после FAIL дальнейшие коррекции запрещены.
Новый protected baseline и независимое ревью оставлены координатору.

Полная общая проверка `bash ./gradlew --no-daemon ktlintCheck detekt`
завершилась PASS, exit 0. Лог:
`/tmp/plinth-ready-detekt-extraction-check-20261112.log`, exit-file рядом.
Проверена текущая совокупность тестовой правки планировщика и annotation
координатора; production в коммит планировщика не включается.
Следующий назначенный запуск — свежие readiness JVM-тесты.

Итог назначенных проверок:

- Полный `ktlintCheck detekt`: PASS, exit 0, 1m31s;
  30 tasks (4 executed, 26 up-to-date). `:app:detekt`,
  `:app:runKtlintCheckOverMainSourceSet` и
  `:app:runKtlintCheckOverTestSourceSet` реально выполнены.
- `bash ./gradlew --no-daemon -x :app:cargoNdkBuild :app:testGithubDebugUnitTest --rerun --tests io.github.puflik.plinth.online.Yu5fOnlineReadinessTest`:
  PASS, exit 0, 1m48s; 45 tasks (11 executed, 34 up-to-date).
  XML подтверждает **12 tests, 0 failures/errors/skipped**, включая ABA.
  `:app:testGithubDebugUnitTest` реально выполнена; `:app:uniffiKotlin`
  включена в граф и UP-TO-DATE. Лог
  `/tmp/plinth-ready-detekt-extraction-jvm-20261112.log`, exit-file рядом.
- `git diff --check` PASS; hash production-файла до/после совпал.
  Все выражения и порядок assertions сохранены точным извлечением,
  остальные байты теста проверены обратным преобразованием.

Локальный коммит содержит только тест и этот taskfile, push не выполняется.
Новых поведенческих сценариев не добавлено; AndroidTest/Rust/native runtime
не повторялись для извлечения helper. Обе Gradle-сессии завершены;
новый protected baseline и независимое ревью ещё не назначены.
