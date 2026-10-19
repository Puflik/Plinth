# Ю1 — выбор экстрактора YouTube: `rustypipe` или NewPipeExtractor

> Исследование для волны «Ю. YouTube минимум» (`tasks.md`, строки 385–392). Решает Opus.
> Написал исследователь (Sonnet 5.5), 2026-10-19, срок — 08:15 UTC. Код, зависимости и
> остальные документы не менялись; всё, что здесь сказано о внешнем мире, взято из сети в
> этот день, ссылки — в тексте. То, что не проверено, помечено «не проверено» или «оценка».
>
> **Состояние файла: часть 1 из 3 — вывод и таблица.** Части 2 (риски, что сломается первым,
> архитектура) и 3 (оценка Ю2–Ю5, порядок, вопросы автору) дописываются ниже; если их нет —
> см. раздел «Что не успел» в конце файла.

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
   `rustypipe-botguard` на V8 (десятки МБ, готовые бинарники V8 при сборке) — на Android и
   в сборке `fdroid` это не поднять. У NewPipeExtractor есть интерфейс `PoTokenProvider`, а
   реализацию на WebView (5 файлов, GPL) NewPipe уже поставляет в F-Droid.
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
| 4 | PoToken, BotGuard, подпись, n-параметр | PoToken — только внешняя программа [`rustypipe-botguard`](https://codeberg.org/ThetaDev/rustypipe-botguard) (MIT; `deno_core` + V8 + JSDOM; Android не заявлен). Подать токен снаружи нельзя: у [`RustyPipeBuilder`](https://docs.rs/rustypipe/0.11.4/rustypipe/client/struct.RustyPipeBuilder.html) есть только `botguard_bin`, `no_botguard`, `botguard_snapshot_file`, `po_token_cache`. Подпись и n — JS-движок внутри крейта (по [issue 39](https://codeberg.org/ThetaDev/rustypipe/issues) это `rquickjs`; не проверено) | Интерфейс [`PoTokenProvider`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/PoTokenProvider.java) (`getWebClientPoToken(videoId)`), реализует приложение: NewPipe — на WebView, [5 файлов `util/potoken`](https://github.com/TeamNewPipe/NewPipe/tree/dev/app/src/main/java/org/schabi/newpipe/util/potoken) (GPL, переносимы). Подпись и n — Rhino внутри экстрактора (по правилам ProGuard; не проверено глубже). После SABR-обхода экстрактор берёт другой клиент плеера (какой — [PR 1508](https://github.com/TeamNewPipe/NewPipeExtractor/pull/1508), не читал) | NewPipeExtractor |
| 5 | YouTube Music | Первоклассный: поиск, альбом, плейлист, артист, **радио**, детали трека, тексты ([README](https://codeberg.org/ThetaDev/rustypipe)) | Поиск с фильтрами `music_songs`, `music_albums`, `music_playlists` через `music.youtube.com/search` ([код](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/linkHandler/YoutubeSearchQueryHandlerFactory.java)); альбом открывается как плейлист; отдельного «радио YTM» не нашёл — есть Mix-плейлисты `RD…`, для Ю4 возможен свой запрос `next` поверх `Downloader` | `rustypipe` по API |
| 6 | Форматы аудио | Список аудиопотоков с кодеком и битрейтом | То же (`AudioStream`) | ничья. Opus ≈160 (itag 251) без входа есть; AAC 256 (itag 141), по общим сведениям, только у Premium — **проверить в спайке Ю3** |
| 7 | Прирост APK, arm64 | Оценка: +4…8 МБ в `.so` (`reqwest`, `rustls`, `tokio`, QuickJS) без botguard; с V8 — +20…30 МБ (не годится) | Оценка: +2…4 МБ в dex после R8 (экстрактор, Rhino, jsoup, protobuf-javalite, nanojson). Верхняя планка — весь APK NewPipe, 10 МиБ ([F-Droid](https://f-droid.org/en/packages/org.schabi.newpipe/)). Обе цифры — оценка, мерить в спайке | ничья (без botguard), иначе NewPipeExtractor |
| 8 | Сборка `fdroid` из исходников, метка NonFreeNet | Крейт собирается из исходников, но botguard тянет готовый V8 — F-Droid такое не принимает; без botguard PoToken нет | NewPipe собирается в F-Droid обычным `gradle` без `prebuild`, метка `NonFreeNet`, лицензия GPL-3.0-or-later (fdroiddata, ссылка выше). Метка YouTube-провайдеру нужна при любом выборе | NewPipeExtractor |
| 9 | Живые клиенты | В живых Android-клиентах не нашёл; на crates.io 48 тыс. загрузок за всё время | NewPipe (F-Droid, 0.29.1 от 2026-08-21), его форки; Piped использует его же (не проверено). [Metrolist](https://github.com/MetrolistGroup/Metrolist) (13,6k★, GPL-3.0, режим «только починки», IzzyOnDroid): свой модуль `innertube`; InnerTune, OuterTune, RiMusic/ViMusic — тоже свои `innertube` на Kotlin (по памяти, не проверено). [LibreTube](https://github.com/libre-tube/LibreTube): внешние API Piped | NewPipeExtractor |
| 10 | Устройство Plinth | `reqwest` внутри; снаружи только `build_with_client(ClientBuilder)` — подменить транспорт нельзя. Значит либо сеть и TLS в ядро (против [ADR 0008](../adr/0008-provider-trait.md): «TLS и сетевого кода в ядре нет»), либо форк | Экстрактор сам в сеть не ходит: потребитель даёт `Downloader` — Kotlin-транспорт, на котором уже стоит `HttpUrlTransport`. Но провайдер получается в Kotlin: нужен мост в `trait Provider` (часть 2) | NewPipeExtractor, с ценой |

Итого: по лицензии ничья, по API YouTube Music выигрывает `rustypipe`, по остальным семи
критериям — NewPipeExtractor, и именно эти семь определяют, будет ли YouTube играть через
месяц после выпуска.

<!-- Части 2 и 3 — ниже -->
