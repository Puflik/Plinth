# Ю1 — выбор экстрактора YouTube: `rustypipe` или NewPipeExtractor

> Исследование для волны «Ю. YouTube минимум» (`tasks.md`, строки 385–392). Решает Opus.
> Написал исследователь (Sonnet 5.5), 2026-10-19, срок — 08:15 UTC. Код, зависимости и
> остальные документы не менялись; всё, что здесь сказано о внешнем мире, взято из сети в
> этот день, ссылки — в тексте. То, что не проверено, помечено «не проверено» или «оценка».
>
> **Состояние файла: все части написаны** (вывод и таблица — разделы 1–2, изменения в Plinth
> и риски — 3–5, оценка и порядок — 6, вопросы автору — 7, лицензии — 8). Что не проверено и
> что не успел — раздел 9.

## 1. Вывод

**NewPipeExtractor (JVM, Kotlin-сторона), с тонким Rust-адаптером в реестре провайдеров.**
`rustypipe` по API лучше подходит под YouTube Music, но как зависимость сегодня не годится:
проект остановился, а PoToken на Android у него закрыт.

Решают три довода, остальное — приложение к ним:

1. **Живость.** У `rustypipe` последний релиз `0.11.4` — 2025-04-23, код не менялся с
   2025-06-18, а в issue про сломанный разбор подписи (2026-03-07) никто не ответил. У
   NewPipeExtractor за последние 12 месяцев — 9 релизов, коммиты до 2026-10-13. Самая
   нестабильная часть YouTube — разбор страницы плеера и защита потоков; брать на себя её
   поддержку (форк `rustypipe`) Plinth не должен.
2. **PoToken и F-Droid.** У `rustypipe` PoToken делает отдельная программа
   `rustypipe-botguard` на V8. Готового V8 для Android нет: [`rusty_v8`](https://github.com/denoland/rusty_v8)
   собирают из исходников (около получаса, тянет `gn`, `ninja`, `clang`), для обычных целей
   скачивают готовую библиотеку с GitHub — в сборке `fdroid` не пройдёт ни то ни другое.
   NewPipeExtractor PoToken сегодня **не использует вовсе**: `YoutubeStreamExtractor` берёт
   потоки у клиента `VISIONOS` без токена, а `setPoTokenProvider` — пустышка «до появления
   SABR» (разобрано по исходнику ветки `dev`, 2026-10-19). WebView-генератор NewPipe
   (5 файлов, GPL) — запасной путь, в минимум волны не входит.
3. **ADR 0008.** В ядре сети нет по решению, `rustypipe` же ходит в сеть сам через `reqwest`
   и подменить транспорт не даёт. У NewPipeExtractor сеть — у потребителя (`Downloader`), что
   ложится на нынешнее устройство (HTTP выполняет Kotlin).

Цена выбора: YouTube-провайдер живёт в Kotlin, а не в ядре «рядом с Internet Archive». Реестр,
модель ответа, здоровье провайдера и каталог остаются в ядре: Kotlin отдаёт ядру
внешний `trait` через UniFFI `with_foreign` — так уже сделаны `online_api.rs`,
`scan_api.rs` и `logging.rs`. Подробности — в части 2.

## 2. Таблица по критериям

| # | Критерий | `rustypipe` (Rust, ядро) | NewPipeExtractor (JVM, Kotlin) | Перевес |
|---|---|---|---|---|
| 1 | Лицензия, AGPL-3.0 | GPL-3.0 ([crates.io](https://crates.io/crates/rustypipe), [Codeberg](https://codeberg.org/ThetaDev/rustypipe)) — совместима: GPLv3 §13 разрешает объединять с AGPLv3, ADR 0003 «GPLv3 — да» | GPL-3.0-or-later ([README](https://github.com/TeamNewPipe/NewPipeExtractor), метаданные NewPipe в [fdroiddata](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/org.schabi.newpipe.yml)) — совместима так же | ничья |
| 2 | Живость, 12 мес. | Релизов **0**: `0.11.4` — 2025-04-23 ([версии](https://crates.io/crates/rustypipe/versions)). Кодовых коммитов после 2025-06-18 нет; единственный позже (2026-08-03) — смена записи лицензии, CI на нём красный. Один мейнтейнер. Открыто 15 issue | Релизов **9**: v0.25.0 (2026-01-11) … v0.26.5 (2026-08-15), в среднем раз в ~27 дней ([лента релизов](https://github.com/TeamNewPipe/NewPipeExtractor/releases.atom)); коммиты до 2026-10-13 ([dev](https://github.com/TeamNewPipe/NewPipeExtractor/commits/dev)); 2,0k★, 620 форков | NewPipeExtractor, решающе |
| 3 | Скорость починок | Сломанный разбор подписи — [issue 75](https://codeberg.org/ThetaDev/rustypipe/issues/75) («could not extract sig fn name», 2026-03-07): без ответа и без починки больше семи месяцев. SABR и PoToken в issue не упоминаются | v0.25.1 вышел через 17 дней после v0.25.0 (убрал клиент TVHTML5, починил nsig); обход SABR — [v0.26.3](https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/v0.26.3) (2026-06-09), в приложении — NewPipe 0.28.8 (2026-06-11, [F-Droid](https://f-droid.org/en/packages/org.schabi.newpipe/)). Оговорка: между v0.24.8 (2025-07-31) и v0.25.0 тегов нет 164 дня — приложение брало коммиты `dev` | NewPipeExtractor |
| 4 | PoToken, BotGuard, подпись, n-параметр | PoToken — только внешняя программа [`rustypipe-botguard`](https://codeberg.org/ThetaDev/rustypipe-botguard) (MIT; `deno_core` + V8 + JSDOM; Android не заявлен; готового V8 для Android нет, он собирается из исходников). Подать токен снаружи нельзя: у [`RustyPipeBuilder`](https://docs.rs/rustypipe/0.11.4/rustypipe/client/struct.RustyPipeBuilder.html) есть только `botguard_bin`, `no_botguard`, `botguard_snapshot_file`, `po_token_cache`. Подпись и n — JS-движок QuickJS: `rquickjs 0.9` — единственный движок в [Cargo.toml](https://codeberg.org/ThetaDev/rustypipe/src/branch/main/Cargo.toml) (что именно он исполняет — по названию зависимости, прямо не проверено) | **PoToken сегодня не нужен**: [`YoutubeStreamExtractor`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeStreamExtractor.java) берёт `streamingData` у клиента `VISIONOS` без токена, `setPoTokenProvider` — пустышка до появления SABR (пересказ исходника `dev`, 2026-10-19). Интерфейс [`PoTokenProvider`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/PoTokenProvider.java) (`getWebClientPoToken(videoId)`) остался, реализация NewPipe на WebView — [5 файлов `util/potoken`](https://github.com/TeamNewPipe/NewPipe/tree/dev/app/src/main/java/org/schabi/newpipe/util/potoken) (GPL, переносимы) — запасной путь. Подпись и n (применяется к каждому аудиопотоку; поток без разобранного n экстрактор отбрасывает — отдал бы 403) — Rhino: в [`build.gradle.kts`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/build.gradle.kts) зависимости `rhino-core` и `rhino-engine`, плеер JS кэшируется, а сама функция исполняется через обёртку `JavaScript` ([`YoutubeJavaScriptPlayerManager`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeJavaScriptPlayerManager.java)). Обход SABR — [PR 1508](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1508) (открыт 2026-06-08, влит 2026-06-09): с `ANDROID_VR`, попавшего под принуждение, экстрактор перешёл на клиент `VISIONOS`, «ещё не задетый»; PoToken он не требует — это сказано в связанном коммите форка, не в самом PR. Не поддержаны ролики «для детей» | NewPipeExtractor |
| 5 | YouTube Music | Первоклассный: поиск, альбом, плейлист, артист, **радио**, детали трека, тексты ([README](https://codeberg.org/ThetaDev/rustypipe)) | Поиск с фильтрами `music_songs`, `music_albums`, `music_playlists` через `music.youtube.com/search` ([код](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/linkHandler/YoutubeSearchQueryHandlerFactory.java)); альбом открывается как плейлист; отдельного «радио YTM» не нашёл — есть Mix-плейлисты `RD…`, для Ю4 возможен свой запрос `next` поверх `Downloader` | `rustypipe` по API |
| 6 | Форматы аудио | Список аудиопотоков с кодеком и битрейтом | То же (`AudioStream`) | ничья. Opus ≈160 (itag 251) без входа есть; AAC 256 (itag 141), по общим сведениям, только у Premium — **проверить в спайке Ю3** |
| 7 | Прирост APK, arm64 | Оценка: +3…6 МБ в `.so` на каждую архитектуру (`reqwest`, TLS, `tokio`, QuickJS; профиль ядра уже `opt-level = "z"`, `lto`, `strip`); в `app/build.gradle.kts` ABI четыре (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`), в универсальном APK прирост умножается. С V8 для botguard — ещё +20…30 МБ на архитектуру (не годится) | Оценка: +2…4 МБ в dex после R8, один раз для всех архитектур (экстрактор, Rhino, jsoup, protobuf-javalite, nanojson — [зависимости](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/build.gradle.kts)). Верхняя планка — весь APK NewPipe, 10 МиБ ([F-Droid](https://f-droid.org/en/packages/org.schabi.newpipe/)). Обе цифры — оценка без сборки, мерить в спайке | NewPipeExtractor |
| 8 | Сборка `fdroid` из исходников, метка NonFreeNet | Крейт собирается из исходников, но botguard тянет готовый V8 — F-Droid такое не принимает; без botguard PoToken нет | NewPipe собирается в F-Droid обычным `gradle` без `prebuild`, метка `NonFreeNet`, лицензия GPL-3.0-or-later (fdroiddata, ссылка выше). Метка YouTube-провайдеру нужна при любом выборе | NewPipeExtractor |
| 9 | Живые клиенты | В живых Android-клиентах не нашёл; на crates.io 48 тыс. загрузок за всё время | NewPipe (F-Droid, 0.29.1 от 2026-08-21), его форки; Piped использует его же (не проверено). [Metrolist](https://github.com/MetrolistGroup/Metrolist) (13,6k★, GPL-3.0, режим «только починки», IzzyOnDroid): свой модуль `innertube`; InnerTune, OuterTune, RiMusic/ViMusic — тоже свои `innertube` на Kotlin (по памяти, не проверено). [LibreTube](https://github.com/libre-tube/LibreTube): внешние API Piped | NewPipeExtractor |
| 10 | Устройство Plinth | `reqwest` внутри; снаружи только `build_with_client(ClientBuilder)` — подменить транспорт нельзя. Значит либо сеть и TLS в ядро (против [ADR 0008](../adr/0008-provider-trait.md): «TLS и сетевого кода в ядре нет»), либо форк | Экстрактор сам в сеть не ходит: потребитель даёт `Downloader` — Kotlin-транспорт, на котором уже стоит `HttpUrlTransport`. Но провайдер получается в Kotlin: нужен мост в `trait Provider` (часть 2) | NewPipeExtractor, с ценой |

Итого: по лицензии ничья, по API YouTube Music выигрывает `rustypipe`, по остальным семи
критериям — NewPipeExtractor, и именно эти семь определяют, будет ли YouTube играть через
месяц после выпуска.

## 3. Что придётся менять в Plinth

Как устроено сейчас (прочитано в коде): `trait Provider` в `core/providers/src/provider.rs` —
синхронный; сети в ядре нет, её делает платформа через `NetTransport`
(`core/ffi/src/api/online_api.rs`) — **один GET**; `connect_online(transport)` собирает
`Registry` с Internet Archive; Media3 берёт адрес потока из `StreamRequest` в `OnlineResolver`
перед каждой загрузкой; `Format` уже знает `Opus` и `Aac`; сборки — `github` и `fdroid`, ABI —
все четыре.

### 3.1. Рекомендуемый путь — NewPipeExtractor в Kotlin

| # | Что | Где | Размер |
|---|---|---|---|
| 1 | Зависимость `com.github.TeamNewPipe:NewPipeExtractor:<тег>` (JitPack) с `includeGroup` в `settings.gradle.kts`; правила R8 для Rhino и экстрактора (взять у NewPipe) | `settings.gradle.kts`, `app/build.gradle.kts` | мало |
| 2 | `Downloader` на Kotlin: GET **и POST** (запросы InnerTube — POST с JSON, а `NetTransport` умеет только GET, так что через него нельзя), gzip, куки согласия, `User-Agent` клиента, разбор 429 и `Retry-After` | новый пакет `…/youtube` | средне |
| 3 | `YoutubeSource` — узкий интерфейс (`search`, `resolve`, `stream`) и единственное место, знающее про экстрактор; исключения экстрактора → `Network` / `Parse` / `Unavailable` | там же | средне |
| 4 | Внешний `trait` в ядре (`#[uniffi::export(with_foreign)]`, как `NetTransport`, `ScanApi` и `Logging`) и Rust-адаптер, реализующий `Provider`; `connect_online` принимает внешних провайдеров; `provider_contract!` гоняется на фейковом внешнем провайдере | `core/ffi/src/api/online_api.rs`, `core/providers` | средне |
| 5 | Воспроизведение: `OnlineResolver` уже отдаёт `url + headers`; у YouTube `User-Agent` должен совпасть с клиентом, выдавшим ссылку, ссылки живут часы, а запросы иногда нужно резать на куски `range=` (у NewPipe для этого свой `DataSource`) — хватит ли `DefaultHttpDataSource`, **не проверено** | `audio/media3/*` | мало…средне |
| 6 | PoToken — **сегодня не нужен** (экстрактор берёт `VISIONOS` без токена); порт пяти файлов `util/potoken` из NewPipe — запасной путь на случай клиента с токеном (GPL → AGPL допустимо при сохранении копирайта и пометке в заголовках файлов) | новый пакет | 0 сейчас; +5…8 ч, если понадобится |
| 7 | Обе сборки получают YouTube; метка `NonFreeNet` — в метаданных F-Droid (как у NewPipe); переключатель «Онлайн-источники» становится по-провайдерным | `app/…`, настройки | мало |
| 8 | Ю2: разбор 403/429/503 нужен и в `HttpClient` ядра, и в Kotlin-`Downloader`; чтобы не писать дважды, в Ю2 вынести таблицу «статус → ошибка» в одно место (Kotlin или данные, общие для обоих) | `core/providers/src/http`, Kotlin | входит в Ю2 |

Не меняется: `Registry` (порядок, здоровье, срок поиска), `SearchResult` / `RemoteTrack` /
`SourceOption`, каталог `Track` / `Version` / `Source`, лайки и «В плейлист…» для сетевых
треков, `playback_order`.

Следствие для плана: провайдер YouTube не проверяется одним `cargo test` — экстрактор в
JVM. Тесты — на записанных ответах через подставной `Downloader` (JUnit) плюс ручная
проверка на телефоне (Ю6). Раздел 6.3 `docs/plan/06-providers.md` предполагал удалённый
конфиг с шаблонами разбора; к NewPipeExtractor это не прикладывается — разбор зашит в Java.
В v1.0 (эпики B–C) конфиг сможет нести только флаги: выключатель провайдера и выбор клиента.

### 3.2. Для сравнения — `rustypipe` в ядре

1. Новый крейт с `rustypipe`, `reqwest`, `tokio`, `rquickjs`: `plinth-providers` по ADR 0008
   зависит только от `plinth-types`. В ядре появляется сеть и TLS — [ADR 0008](../adr/0008-provider-trait.md)
   надо пересматривать (`HttpClient`, срок и отмена поиска, `FixtureTransport` на этот провайдер
   не распространяются).
2. У `reqwest` в `rustypipe` TLS по умолчанию нативный (`default = ["default-tls"]`): для NDK нужен
   признак `rustls-tls-*`, `tokio` — внутри синхронного `trait` (блокировать текущим потоком).
3. PoToken — без botguard остаются клиенты, которым он не нужен; какие из них `rustypipe` ещё
   умеет, не проверено. Подпись (issue 75) — чинить самим, в форке.
4. Плюс: один язык, `cargo test` на записях, радио YouTube Music и альбомы «из коробки».

## 4. Что сломается первым и как чинится

Удалённого конфига нет: любая починка — **новая версия экстрактора → сборка → APK**. Версия
экстрактора лежит в одном месте (`gradle/libs.versions.toml`), и это единственное, что меняется
в типовом случае.

| # | Что ломается | Признак | Чинится | Срок после выхода починки |
|---|---|---|---|---|
| 1 | **Клиент плеера попадает под принуждение** (SABR / PoToken). Уже было: `ANDROID_VR` → в v0.26.3 перешли на `VISIONOS` (**он и есть следующий под удар**); ранее убирали `TVHTML5` (v0.25.1), а 2026-08-06 — `ANDROID`, `IOS`, `WEB_EMBEDDED_PLAYER` ([dev](https://github.com/TeamNewPipe/NewPipeExtractor/commits/dev), по списку коммитов) | Нет аудио или только 360p, 403 на потоке | Поднять тег экстрактора; когда «не задетые» клиенты кончатся — нужен SABR (абзац ниже) | Выход тега — дни после поломки (PR 1508: открыт 06-08, влит 06-09, тег v0.26.3 — 06-09); `github` — сразу, `fdroid` — по циклу сборки F-Droid (у NewPipe 0.28.8 с обходом SABR появился в [F-Droid](https://f-droid.org/en/packages/org.schabi.newpipe/) 06-11, через два дня после тега; у Plinth срок не измерен: дни…недели) |
| 2 | Разбор страницы плеера (сигнатура, n-параметр) | «deobfuscation error», 403 | Поднять тег; v0.25.1 — через 17 дней после v0.25.0 | дни…недели |
| 3 | Разметка выдачи YouTube Music (lockup view models и т. п.) | Пустой или кривой поиск, `Parse` | Поднять тег (v0.26.1–0.26.2 — такие) | дни…недели |
| 4 | **PoToken** — сегодня не нужен; понадобится, когда экстрактор перейдёт на клиента с токеном | 403 при чтении потока | Поднять тег, если там это уже есть; иначе включить порт генератора на WebView (формат вызовов BotGuard меняется, порт обновлять по NewPipe) | недели, если порта ещё нет |
| 5 | Отказ по сети: 429, «подтвердите, что вы не бот» на мобильной сети, VPN или с адреса дата-центра | 403/429 при исправном экстракторе | Кодом не лечится; Ю2 показывает «сервер отказал», а не «нет сети», и уважает `Retry-After` | — |
| 6 | Экстрактор перестанет выходить (все добровольцы NewPipe) | Нет тегов 2–3 месяца при живых поломках | Запасные пути (раздел 5) | недели |

**Главный долгий риск — не конкретная поломка, а направление.** YouTube переводит клиентов на
SABR (потоковый протокол с токеном): `ANDROID_VR` уже накрыт, у yt-dlp «web» отдаёт только SABR-форматы
и требует токен ([PO Token Guide](https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide), правка от
2026-07-12). Пока остаются клиенты с прямыми ссылками, NewPipeExtractor их переключает за дни. Когда
они кончатся, спасёт только SABR-клиент — отдельный большой кусок работы; в NewPipeExtractor он
«в будущем» (Javadoc `setPoTokenProvider`), у `rustypipe` его нет вовсе. Эту часть не закрывает ни
один из кандидатов; различие в том, что у NewPipeExtractor есть команда, которая это сделает первой.
План на такой день — раздел 5.

Что сделать заранее, чтобы поломка не была неожиданностью: (а) версию экстрактора и клиента держать
в одном файле; (б) ручной скрипт «живой YouTube» (поиск → поток → 206 на первом диапазоне) — гонять
перед выпуском и по желанию автора в ночной задаче CI; (в) в «О приложении» показывать версию
экстрактора, чтобы отчёт о сбое её нёс.

## 5. Запасные пути

1. Свой слой InnerTube на Kotlin за тем же `YoutubeSource` — как у Metrolist, InnerTune и RiMusic
   (модули `innertube`, GPL-3.0, код можно брать с сохранением копирайта). Работа: недели, но
   интерфейс остаётся.
2. `rustypipe`, если проект оживёт или Plinth возьмёт форк: он на Rust, так что это второй
   путь (раздел 3.2) с пересмотром ADR 0008, а не замена за `YoutubeSource`.
3. Отказаться от YouTube в `fdroid`, оставить в `github` (решение автора, вопрос 2 ниже).

## 6. Оценка волны Ю и порядок

Оценка — плановые часы, ±50 %, без спайка; ориентир — эпик E v0.2 (интерфейс провайдера +
Internet Archive + UI, 20–30 ч, `tasks-v0.2.md`, строка 312). Для NewPipeExtractor
(рекомендуемый путь, без PoToken):

| Задача | Состав | Часы |
|---|---|---|
| **Ю1** | Этот файл + решение Opus | сделано |
| **Ю2** (= Р1.11г) | Как в плане: «сервер отказал» ≠ «нет сети», 429 с `Retry-After`, «Повторить», холодный старт. Добавка из этого исследования: таблицу «статус → ошибка» сделать общей для `HttpClient` ядра и Kotlin-`Downloader` | 6–10 (оценка; состав Р1.11г не пересматривал) |
| **Ю3а** | Спайк-гейт (одноразовый код, не в `main`): `Downloader`, поиск `music_songs` / `music_albums`, поток Opus, 60 с игры на телефоне автора, замер прироста APK и холодного старта | 2–4 |
| **Ю3б** | `YoutubeSource`, `Downloader` с POST и разбором статусов, выбор потока (Opus ≈160 → AAC), тесты на записанных ответах | 6–9 |
| **Ю3в** | Внешний `trait`, адаптер `Provider`, `connect_online`, контракт `provider_contract!`, здоровье в реестре | 5–8 |
| **Ю3г** | Gradle, R8, обе flavor, `NonFreeNet`, переключатель по провайдерам, заголовки лицензий, `NOTICE` | 3–5 |
| **Ю4** | Радио: метод `related` в `trait` (в ADR 0008 он обещан с Bandcamp — приходит раньше), Mix-плейлист `RD…` или свой запрос `next`, продолжение очереди, «Радио» в меню ⋮ | 6–10 |
| **Ю5** | Секция YouTube в поиске, экран альбома и плейлиста YouTube Music (экран альбома IA переиспользуется), лайк и «В плейлист…» уже есть с v0.2 | 10–16 |
| **Ю6** | Приёмка на телефоне: поиск, игра в фоне, радио, поворот, уход в фон | 3–5 |
| **Итого Ю2–Ю6** | | **41–67 ч**, около месяца при 60 ч в месяц |

Порядок и зависимости:

1. **Ю3а — первым** (гейт). Если без PoToken не играет или Media3 не берёт поток — решение Opus до
   остального: порт PoToken (+5…8 ч), свой `DataSource` или смена пути.
2. **Ю2 — параллельно Ю3а**: не зависит от Ю1, файлы не пересекаются (Ю2 — `core/providers/src/http`,
   Kotlin онлайн-экраны; Ю3а — новый пакет).
3. **Ю3б → Ю3в → Ю3г.** Ю3в трогает `online_api.rs` и ошибки — после Ю2.
4. **Ю4 после Ю3в** (нужен `related` и провайдер в реестре). **Ю5** — после Ю3в; пункт «Радио» —
   после Ю4. Ю4 и Ю5 на разных файлах (ядро и очередь против экранов) — параллельно.
5. **Ю6 — последним**, как и в плане.

Что в оценке Ю3 **не учтено**: SABR-клиент (раздел 4), PoToken (раздел 3.1, п. 6), YouTube Music с
входом в Google (лента, лайки аккаунта) — по решению автора её нет.

### Что доказывает или опровергает спайк Ю3а

1. Поиск `music_songs` и `music_albums` по латинице и кириллице отдаёт название, исполнителя,
   длительность.
2. Какие аудиопотоки приходят без входа: Opus (itag 251) и AAC (140 / 141), с какими битрейтами.
3. Поток играет в Media3 минуту на TECNO CAMON 30, перемотка работает, 403 нет; хватает ли
   `DefaultHttpDataSource` (заголовок `User-Agent`, куски `range=`).
4. Время первого поиска и первого потока после холодного старта (прогрев Rhino).
5. Прирост release-APK `github` на arm64 против `main`.
6. Что отдаёт Mix `RD…` для радио (длина, продолжение).

## 7. Вопросы автору

1. **Где живёт YouTube-провайдер.** Рекомендую Kotlin (NewPipeExtractor) и внешний `trait` в
   ядре, Internet Archive остаётся в Rust. Это отступление от «в ядро рядом с IA»: YouTube
   целиком не проверить `cargo test`. Согласен?
2. **YouTube в обеих сборках?** Рекомендую `github` и `fdroid` с меткой NonFreeNet (как NewPipe и
   LibreTube). Запасной вариант — `fdroid` без YouTube, если F-Droid откажет: нужен флаг сборки.
3. **Потолок качества.** Без входа в Google — Opus ≈160 и AAC ≈128; AAC 256, по общим сведениям,
   только у Premium (спайк проверит). Принимаешь?
4. **Срок реакции.** «Чинит новый APK» означает: `github` — через дни после тега экстрактора,
   `fdroid` — по циклу F-Droid. Нужна ли ночная проверка «живого YouTube» в CI?
5. **День SABR.** Когда не останется клиентов с прямыми ссылками, нужен свой SABR-клиент
   (недели). Заложить его в v1.0, эпик A, сейчас или решать в тот день?
6. **Bandcamp и SoundCloud.** NewPipeExtractor умеет и их (в релизах — правки Bandcamp и
   SoundCloud). Сейчас не решаем, но выбор Kotlin-экстрактора оставляет вариант «одна
   библиотека на три провайдера» открытым.

## 8. Лицензии зависимостей NewPipeExtractor

Сам экстрактор — GPL-3.0-or-later (раздел 2). Остальное — по общим сведениям, в этой сессии не
проверялось: Rhino — MPL-2.0 (в MPL-2.0 есть явная совместимость с (A)GPL), jsoup — MIT,
protobuf-javalite — BSD-3-Clause, nanojson (форк NewPipe) — Apache-2.0. Все — допустимые по
[ADR 0003](../adr/0003-license-agplv3.md). Перед выпуском сверить по `gradle`-отчёту
лицензий и вписать в `NOTICE`.

## 9. Что не проверено и что не успел

- **Ничего не запускалось.** Только чтение кода Plinth и сети; размеры APK — оценка; работает ли
  экстрактор против живого YouTube сегодня, покажет только спайк Ю3а.
- Часть сведений получена через WebFetch (пересказ страницы малой моделью): список коммитов `dev`,
  `YoutubeStreamExtractor`, PR 1508, `PoTokenProvider`. Ссылки даны — перед решением стоит
  открыть `YoutubeStreamExtractor` и убедиться, что клиент — `VISIONOS` и токена нет.
- Живые клиенты: прочитан только [Metrolist](https://github.com/MetrolistGroup/Metrolist)
  (свой `innertube`, режим «только починки», нет в основном F-Droid); InnerTune, OuterTune,
  RiMusic/ViMusic, Seal — по памяти. LibreTube — по README, без деталей про источник данных.
- JitPack-зависимость и сканер F-Droid: вывод из того, что NewPipe собирается в F-Droid обычным
  `gradle` (метаданные без `srclibs` и `prebuild`); сам файл зависимостей NewPipe не нашёл.
- Радио YouTube Music (`RDAMVM…`): не проверено ни у одного кандидата, кроме перечисления
  в README `rustypipe`.
- Не просмотрено: открытые issue NewPipeExtractor про живую поломку на сегодня; отдельная
  проверка, не вышли ли после 2026-10-13 коммиты, ломающие `VISIONOS`.
- Срок: исследование уложено в ~45 минут.
