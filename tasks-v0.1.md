# Plinth v0.1 «Звук» — файловая декомпозиция

> **Родительский документ:** [tasks.md](tasks.md) · **Требования:** [plan.md](plan.md)
> **Объём волны:** 59–97 ч · **Файлов:** ~105
> **Стек волны:** Kotlin + Compose + Media3 + Room. Rust появляется в v0.2.

---

## Как читать

Каждый файл привязан к задаче из `tasks.md` — колонка **ID**. Описание отвечает на вопрос «за что этот файл отвечает», а не «что в нём написано»: реализация — дело разработки, здесь фиксируются границы ответственности.

**Обозначения:** 📌 критический путь · 🔁 будет выброшен или переписан в v0.2 · ⚠️ требует решения или несёт риск

---

## Дерево проекта

```
plinth/
├── .github/workflows/
│   ├── ci.yml
│   └── release.yml
├── config/
│   └── detekt.yml
├── docs/
│   ├── ARCHITECTURE.md
│   └── adr/
│       ├── 0001-stack.md
│       ├── 0002-audio-engine-abstraction.md
│       ├── 0003-license-agplv3.md
│       ├── 0004-di-framework.md
│       └── 0005-tag-source-v01.md
├── gradle/
│   └── libs.versions.toml
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── kotlin/io/github/puflik/plinth/
│       │   │   ├── PlinthApplication.kt
│       │   │   ├── MainActivity.kt
│       │   │   ├── core/
│       │   │   ├── audio/
│       │   │   │   ├── engine/
│       │   │   │   └── media3/
│       │   │   ├── library/
│       │   │   │   ├── permission/
│       │   │   │   ├── scan/
│       │   │   │   ├── db/
│       │   │   │   └── sort/
│       │   │   ├── queue/
│       │   │   ├── diagnostics/
│       │   │   │   ├── log/
│       │   │   │   └── vendor/
│       │   │   ├── settings/
│       │   │   ├── startup/
│       │   │   ├── di/
│       │   │   └── ui/
│       │   │       ├── theme/
│       │   │       ├── navigation/
│       │   │       ├── common/
│       │   │       ├── onboarding/
│       │   │       ├── library/
│       │   │       ├── search/
│       │   │       ├── player/
│       │   │       └── settings/
│       │   └── res/
│       ├── github/kotlin/io/github/puflik/plinth/flavor/
│       ├── fdroid/kotlin/io/github/puflik/plinth/flavor/
│       ├── test/kotlin/io/github/puflik/plinth/
│       ├── sharedTest/kotlin/io/github/puflik/plinth/
│       └── androidTest/kotlin/io/github/puflik/plinth/
├── .editorconfig
├── .gitignore
├── build.gradle.kts
├── settings.gradle.kts
├── LICENSE
├── README.md
├── README.ru.md
├── CONTRIBUTING.md
└── CHANGELOG.md
```

---

# Эпик A. Каркас проекта

## A1. Инициализация репозитория

| Файл | ID | Ответственность |
|---|---|---|
| `.gitignore` | A1.1 | Стандартный Android + Gradle + IDE |
| `LICENSE` | A1.1 | Полный текст **AGPLv3** |
| `README.md` | A1.1 | Заглушка: что это, статус, как собрать. Наполняется к v0.5 |
| `README.ru.md` | A1.1 | Русская версия |
| `CHANGELOG.md` | A1.1 | Формат Keep a Changelog, первая запись — v0.1 |
| `settings.gradle.kts` | A1.2 | Модули проекта, репозитории зависимостей |
| `build.gradle.kts` (корень) | A1.2 | Плагины, общие настройки |
| `gradle/libs.versions.toml` | A1.2 | Version catalog — все версии зависимостей в одном месте |
| `app/build.gradle.kts` | A1.2, **A1.3** 📌 | `minSdk 26`, `targetSdk` актуальный, Compose, **два product flavor** |
| `.editorconfig` | A1.4 | Отступы, окончания строк |
| `config/detekt.yml` | A1.4 | Правила статического анализа |
| `.github/workflows/ci.yml` | A1.5 | Сборка обоих flavor + detekt + ktlint + unit-тесты на каждый push |
| `.github/workflows/release.yml` | A1.5 | Тег → подписанный APK → GitHub Release. Пишется сейчас, используется в H5 |

### Flavor-специфичные файлы 📌

| Файл | ID | Ответственность |
|---|---|---|
| `src/github/kotlin/.../flavor/FlavorConfig.kt` | A1.3 | Константы сборки `github`: чекер обновлений включён, API-ключи из `BuildConfig` |
| `src/fdroid/kotlin/.../flavor/FlavorConfig.kt` | A1.3 | Константы сборки `fdroid`: чекер обновлений **выключен**, API-ключи вводит пользователь |

> ⚠️ **Самая дорогая задача для откладывания во всей волне.** Добавить flavor позже — значит переделать конфигурацию сборки, CI, подпись релизов и логику доступа к ключам. Сейчас это полчаса, потом — день.

## A2. Скелет приложения

| Файл | ID | Ответственность |
|---|---|---|
| `AndroidManifest.xml` | A2.1 | Разрешения, `Application`, `Activity`, `PlaybackService`, `allowBackup` |
| `PlinthApplication.kt` | A2.1 | Точка входа, инициализация DI и логгера |
| `MainActivity.kt` | A2.1 | Single activity, хост Compose |
| `ui/navigation/Destination.kt` | A2.1 | Перечень экранов приложения |
| `ui/navigation/PlinthNavHost.kt` | A2.1 | Граф навигации |
| `ui/navigation/BottomNavigationBar.kt` | A2.3 | Вкладки `Библиотека` · `Поиск` · `Настройки` |
| `ui/theme/Color.kt` | A2.2 | Цветовые токены светлой и тёмной тем |
| `ui/theme/Type.kt` | A2.2 | Типографика |
| `ui/theme/Theme.kt` | A2.2 | Сборка темы, реакция на системную |
| `di/AppModule.kt` | A2.4 ⚠️ | Граф зависимостей |
| `di/AudioModule.kt` | A2.4 | Привязка `AudioEngine` → `Media3Engine` |
| `di/LibraryModule.kt` | A2.4 | Репозитории, сканер, база |

> ⚠️ **Решение A2.4 — Hilt или ручной граф.** Рекомендую **Hilt**: по той же логике, по которой выбран Kotlin с Compose — огромный корпус примеров, а значит лучшая работа AI-агента. Ручной граф чище, но агент будет чаще ошибаться. Записать в ADR `0004`.

## A3. Документация

| Файл | ID | Ответственность |
|---|---|---|
| `docs/ARCHITECTURE.md` | A3.1 | Карта модулей, потоки данных, границы слоёв. **Обновляется при каждом новом модуле** |
| `docs/adr/0001-stack.md` | A3.2 | Почему Kotlin + Compose + Media3, почему не Tauri для мобилки |
| `docs/adr/0002-audio-engine-abstraction.md` | A3.2 📌 | Почему воспроизведение за интерфейсом: десктоп, движок R3, тестируемость |
| `docs/adr/0003-license-agplv3.md` | A3.2 | SaaS loophole в GPL, почему AGPL |
| `docs/adr/0004-di-framework.md` | A3.2 | Hilt или ручной граф |
| `docs/adr/0005-tag-source-v01.md` | A3.2 | Источник тегов в v0.1 — см. эпик C |
| `CONTRIBUTING.md` | A3.3 | Как собрать, стиль кода, что принимается в PR |

> `ARCHITECTURE.md` и ADR — **рабочий вход для AI-агента**, а не формальность. Без них агент каждую сессию переизобретает уже принятые решения.

---

# Эпик B. Аудиоядро 📌

## B1. Абстракция `AudioEngine` 📌

| Файл | ID | Ответственность |
|---|---|---|
| `audio/engine/AudioEngine.kt` | B1.1 📌 | **Интерфейс**: `prepare` · `play` · `pause` · `seek` · `setVolume` · `release` |
| `audio/engine/AudioSource.kt` | B1.1 | Описание источника звука: путь к файлу или URL + заголовки. **Не знает о провайдерах** |
| `audio/engine/PlaybackParams.kt` | B1.1 | Параметры подготовки: стартовая позиция, gapless-соседи |
| `audio/engine/PlaybackState.kt` | B1.2 | Состояние: idle / buffering / playing / paused / ended / error |
| `audio/engine/PlaybackEvent.kt` | B1.2 | События: смена позиции, буферизация, конец трека, ошибка |
| `audio/engine/PlaybackError.kt` | B1.2 | Типизированные ошибки: файл недоступен, формат не поддержан, сеть, неизвестно |
| `sharedTest/audio/engine/FakeAudioEngine.kt` | B1.3 | Управляемая реализация для тестов, без Android |
| `sharedTest/audio/engine/AudioEngineContractTest.kt` | B1.4 | Контрактные тесты: любая реализация обязана их проходить |
| `test/audio/engine/EngineBoundaryTest.kt` | B1.1 📌 | Страж границы: ни одного импорта Android в `audio/engine` |
| `test/audio/engine/FakeAudioEngineTest.kt` | B1.3 | Прогон контракта на фейке плюс его собственная управляющая поверхность |

> 📌 ⚠️ **Правило, нарушение которого убивает и десктопный клиент, и будущий движок R3:** ни один тип из `androidx.media3` не пересекает эту границу. `Media3Engine` знает об интерфейсе — интерфейс о Media3 не знает никогда.
>
> Контрактные тесты (`B1.4`) существуют именно для того, чтобы через год Rust-движок можно было подставить, прогнав тот же набор.
>
> Набор `sharedTest` подключён и к `test`, и к `androidTest`: `FakeAudioEngine` проходит контракт на JVM, `Media3Engine` (B2) — на эмуляторе, одним и тем же классом.

## B2. `Media3Engine`

| Файл | ID | Ответственность |
|---|---|---|
| `audio/media3/Media3Engine.kt` | B2.2 | Реализация `AudioEngine` поверх `ExoPlayer` |
| `audio/media3/ExoPlayerFactory.kt` | B2.1 | Создание и конфигурация плеера: буферы, атрибуты звука, gapless |
| `audio/media3/MediaItemMapper.kt` | B2.2 | `AudioSource` → `MediaItem` |
| `audio/media3/PlayerListenerAdapter.kt` | B2.2 | События ExoPlayer → `PlaybackEvent` |
| `androidTest/audio/FormatSupportTest.kt` | B2.4 | Прогон тестовых файлов: MP3 · AAC · FLAC · ALAC · OGG · Opus · WAV |
| `androidTest/assets/` | B2.3, B2.4 | Эталонные файлы, включая склеенную пару для проверки gapless |

## B3. `MediaSessionService` и фон 📌

| Файл | ID | Ответственность |
|---|---|---|
| `audio/media3/PlaybackService.kt` | B3.1 📌 | `MediaSessionService`, жизненный цикл foreground-сервиса |
| `audio/media3/PlaybackSessionCallback.kt` | B3.2 | Обработка команд извне: уведомление, гарнитура, Bluetooth |
| `audio/media3/MediaMetadataBuilder.kt` | B3.2 | Метаданные для сессии: название, артист, обложка, длительность |
| `audio/media3/PlaybackNotificationProvider.kt` | B3.3 | Кастомизация уведомления-плеера и его действий |
| `audio/media3/NotificationChannels.kt` | B3.1 | Каналы уведомлений: воспроизведение, сканирование, диагностика |
| `library/permission/NotificationPermission.kt` | B3.5 | Запрос `POST_NOTIFICATIONS` при первом воспроизведении |
| `audio/media3/PlaybackWakeLocks.kt` | B3.6 | `WakeLock` и `WifiLock` |

> Экран блокировки (`B3.4`) отдельного файла не требует — он отображается системой на основе `MediaSession` и метаданных из `MediaMetadataBuilder`. Отдельная задача — проверить, что обложка доходит.

## B4. Системные взаимодействия

| Файл | ID | Ответственность |
|---|---|---|
| `audio/media3/AudioFocusConfig.kt` | B4.1 📌 | Аудиофокус через `setAudioAttributes(handleAudioFocus = true)` + обработка крайних случаев |
| `audio/media3/BecomingNoisyHandler.kt` | B4.3 | Пауза при отключении наушников |
| `audio/PlaybackController.kt` | B2–B4 | **Фасад для UI**: единственная точка, через которую экраны управляют воспроизведением |

> `B4.2` (кнопки гарнитуры) и `B4.4` (метаданные Bluetooth) обслуживаются `MediaSession` — новых файлов нет, но обе требуют отдельной проверки на живом устройстве.

---

# Эпик C. Локальная библиотека

## C1. Разрешения

| Файл | ID | Ответственность |
|---|---|---|
| `library/permission/MediaPermission.kt` | C1.1 | Ветвление `READ_MEDIA_AUDIO` (API 33+) / `READ_EXTERNAL_STORAGE` |
| `library/permission/PermissionState.kt` | C1.1 | Состояние: выдано / отказано / отказано навсегда |
| `ui/common/PermissionRationaleScreen.kt` | C1.2 | Объяснение при отказе + кнопка повторного запроса и перехода в настройки |

## C2. Сканер

| Файл | ID | Ответственность |
|---|---|---|
| `library/scan/LibraryScanner.kt` | C2.1 🔁 | Обход источников, сбор кандидатов |
| `library/scan/MediaStoreSource.kt` | C2.1 ⚠️ 🔁 | Чтение из `MediaStore` |
| `library/scan/TagReader.kt` | C2.2 🔁 | Извлечение тегов |
| `library/scan/ScanDiff.kt` | C2.3 | Инкрементальность: сравнение по `mtime`, что добавить, обновить, пометить пропавшим |
| `library/scan/ScanWorker.kt` | C2.4 | `WorkManager`: фон, прогресс, отмена |
| `library/scan/ScanProgress.kt` | C2.4 | Модель прогресса для UI |
| `library/scan/FolderConfig.kt` | C2.5 | Включённые и исключённые папки, умолчание `Music` + `Download` |
| `ui/settings/FolderSettingsScreen.kt` | C2.5 | Экран выбора папок |

> ⚠️ **Решение C2 — откуда брать теги в v0.1** (ADR `0005`).
>
> | Вариант | Плюс | Минус |
> |---|---|---|
> | **`MediaStore`** ← рекомендую | Ноль зависимостей, быстро, укладывается в 30 с на 5 000 треков | Видит только то, что нашёл системный сканер. Файлы в немедийных папках пропускаются |
> | Ручной обход + библиотека тегов | Видит всё | Тянет зависимость, которая **выбрасывается в v0.2** вместе с переходом на Rust |
>
> Поскольку весь этот эпик переписывается в v0.2 под Rust, вкладываться в него не стоит. `MediaStore` даёт рабочий v0.1 малой кровью; полноценный обход папок приходит вместе с ядром.

## C3. Хранение 🔁

| Файл | ID | Ответственность |
|---|---|---|
| `library/db/PlinthDatabase.kt` | C3.1 🔁 | Room-база, версия схемы |
| `library/db/entity/TrackEntity.kt` | C3.1 🔁 | Упрощённая модель трека. **Не `Track/Version/Source`** — она приходит в v0.2 |
| `library/db/entity/AlbumEntity.kt` | C3.1 🔁 | |
| `library/db/entity/ArtistEntity.kt` | C3.1 🔁 | |
| `library/db/dao/TrackDao.kt` | C3.1, C3.2 🔁 | Запросы и индексы на `artist`, `album`, `title_normalized` |
| `library/db/dao/AlbumDao.kt` | C3.1 🔁 | |
| `library/db/dao/ArtistDao.kt` | C3.1 🔁 | |
| `library/db/Converters.kt` | C3.1 🔁 | Преобразования типов |
| `library/LibraryRepository.kt` | C3.3 | **Фасад над хранилищем.** Единственный файл эпика, который переживёт v0.2 — внутренности заменятся вызовами Rust-ядра, интерфейс останется |

> 🔁 **Весь эпик C3 — временный.** Цель — работающий v0.1, а не фундамент. Единственное, что делается «на века», — `LibraryRepository` как граница: если UI ходит только через него, замена Room на ядро в v0.2 не затронет экраны.

## C4. Списки и сортировка

| Файл | ID | Ответственность |
|---|---|---|
| `library/sort/NaturalOrder.kt` | C4.3 | Естественная сортировка: `Track 2` перед `Track 10` |
| `library/sort/ArticleStripper.kt` | C4.3 | Игнорирование артиклей: `The` `A` `An` `Der` `Die` `Das` `Le` `La` `Los`. Список настраиваемый |
| `library/sort/SortOption.kt` | C4.3 | Варианты сортировки для каждого вида списка |
| `ui/library/LibraryScreen.kt` | C4.1 | Хост вкладок библиотеки |
| `ui/library/LibraryViewModel.kt` | C4.1 | Состояние списков, запросы к репозиторию |
| `ui/library/tabs/TracksTab.kt` | C4.1, C4.2 | Список треков, виртуализированный |
| `ui/library/tabs/AlbumsTab.kt` | C4.1 | Сетка альбомов |
| `ui/library/tabs/ArtistsTab.kt` | C4.1 | Список артистов |
| `ui/library/tabs/FoldersTab.kt` | C4.1 | Файловая структура как есть |
| `ui/library/components/TrackRow.kt` | C4.2 | Строка трека — переиспользуется во всех списках и поиске |
| `ui/library/components/AlbumCard.kt` | C4.2 | |
| `ui/search/SearchScreen.kt` | C4.4 | Экран поиска |
| `ui/search/SearchViewModel.kt` | C4.4 | Мгновенный поиск с дебаунсом |

---

# Эпик D. Очередь и воспроизведение

| Файл | ID | Ответственность |
|---|---|---|
| `queue/PlaybackQueue.kt` | D1.1 📌 | **Модель очереди**: контекст + вручную добавленное, которое выживает при смене контекста |
| `queue/QueueContext.kt` | D1.1 | Источник контекста: альбом, плейлист, папка, результаты поиска |
| `queue/QueueItem.kt` | D1.1 | Элемент очереди + признак «добавлен вручную» |
| `queue/QueueAction.kt` | D1.2 | Действия: `играть следующим` · `в конец` · `заменить очередь` |
| `queue/ShuffleOrder.kt` | D1.3 📌 | **Порядок обхода**, не перемешивание самой очереди. Выключил shuffle — вернулся к исходному порядку, не потеряв позицию |
| `queue/RepeatMode.kt` | D1.4 | off / one / all |
| `queue/QueuePersistence.kt` | D1.5, D2.1 | Сохранение очереди и позиции |
| `queue/QueueRestorer.kt` | D2.2 | Восстановление при старте |
| `test/queue/PlaybackQueueTest.kt` | D1 | Тесты: смена контекста сохраняет ручные добавления, shuffle обратим, позиция не теряется |

---

# Эпик E. UI плеера

| Файл | ID | Ответственность |
|---|---|---|
| `ui/player/MiniPlayer.kt` | E1.1 | Слой **D0**: строка поверх навигации |
| `ui/player/MiniPlayerGestures.kt` | E1.2 | Свайп вверх → полноэкранный, свайп вбок → смена трека |
| `ui/player/PlayerScreen.kt` | E2 | Слой **D1**: компоновка полноэкранного плеера |
| `ui/player/PlayerViewModel.kt` | E2 | Состояние плеера, команды в `PlaybackController` |
| `ui/player/components/Artwork.kt` | E2.1 | Обложка, плейсхолдер при отсутствии |
| `ui/player/components/TrackTitleBlock.kt` | E2.1 | Название, артист (кликабелен), место под бейдж версии — **бейдж появится в v0.3** |
| `ui/player/components/ProgressSlider.kt` | E2.2 | Прогресс, скраббинг, тап по времени переключает `прошло ⇄ осталось` |
| `ui/player/components/TransportControls.kt` | E2.3 | shuffle · prev · play/pause · next · repeat |
| `ui/player/components/LikeButton.kt` | E2.4 | Лайк — **единственная система оценки**, звёзды отклонены |
| `ui/player/PlayerGestures.kt` | E2.5 | Жесты и их настраиваемые действия |
| `ui/player/components/PanelTabs.kt` | E3.1 | Точки-табы под обложкой |
| `ui/player/panels/QueuePanel.kt` | E3.2 | Очередь: drag & drop, свайп для удаления, превью следующего |
| `ui/player/panels/LyricsPanel.kt` | E3.3 | **Заглушка**, наполняется в v0.7 |
| `ui/player/panels/SimilarPanel.kt` | E3.3 | **Заглушка**, наполняется в v0.9 |
| `ui/player/panels/InfoPanel.kt` | E3.3 | **Заглушка**, наполняется в v0.7 |
| `ui/player/PlayerMenu.kt` | E4.1 | Слой **D3**: в плейлист · к альбому · к артисту |
| `settings/GestureSettings.kt` | E2.5 | Хранение настроек жестов |
| `ui/settings/GestureSettingsScreen.kt` | E2.5 | Экран настройки жестов по образцу AIMP |

**Умолчания жестов** (зафиксированы в `plan.md` 12.11): свайп по обложке = смена трека · свайп вверх = очередь · свайп вниз = свернуть · **тап по обложке = текст ⇄ обложка** · двойной тап по половине = ±10 с · долгое удержание next/prev = перемотка.

---

# Эпик F. Онбординг и пустое состояние

| Файл | ID | Ответственность |
|---|---|---|
| `ui/onboarding/OnboardingScreen.kt` | F1 | Хост мастера, навигация между шагами, кнопка «пропустить всё» |
| `ui/onboarding/OnboardingStep.kt` | F1.4 📌 | **Абстракция шага**: заголовок, содержимое, пропускаемость, условие показа |
| `ui/onboarding/steps/PermissionStep.kt` | F1.1 | Шаг 1, обязательный |
| `ui/onboarding/steps/FolderStep.kt` | F1.2 | Шаг 2, пропускаемый |
| `ui/onboarding/OnboardingState.kt` | F1.3 | Какие шаги пройдены, какие пропущены |
| `ui/onboarding/DeferredPrompts.kt` | F1.4 | **Реестр отложенных предложений**: пропущенный шаг возвращается контекстно, когда становится нужен |
| `ui/library/EmptyLibraryScreen.kt` | F2 | Пустое состояние |
| `ui/library/components/FolderPicker.kt` | F2.1 | Проводник для ручного выбора папки |
| `ui/library/components/RoadmapBlock.kt` | F2.2 | Честное «что дальше»: «онлайн-поиск появится в 0.2» |
| `settings/StartScreenPreference.kt` | F3.1 | Настройка `Авто / Библиотека / Открытия / Продолжить` |
| `startup/StartDestinationResolver.kt` | F3.2 | **Правила режима «Авто»** — в v0.1 без ветки «Открытия» |
| `ui/common/DecisionExplanation.kt` | F3.3 | Переиспользуемый компонент «почему приложение так решило» |

> 📌 **`OnboardingStep.kt` и `DeferredPrompts.kt` — задел на будущее.** В v0.1 шагов два, к v1.0 их семь. Если мастер написан как жёсткая последовательность двух экранов, каждый новый шаг будет переписыванием.
>
> `DecisionExplanation.kt` — та же логика: сквозной принцип объяснимости появляется в v0.1 на одном экране, но к v1.0 используется в полутора десятках мест.

---

# Эпик G. Надёжность

## G1. Логирование

| Файл | ID | Ответственность |
|---|---|---|
| `diagnostics/log/Logger.kt` | G1.1 | Фасад логирования, уровни |
| `diagnostics/log/LogBuffer.kt` | G1.1 | Кольцевой буфер в памяти |
| `diagnostics/log/LogFileWriter.kt` | G1.1 | Файл, ротация, лимит 5 МБ |
| `diagnostics/log/LogRedactor.kt` | G1.2 🔒 | **Вырезание запрещённых данных**: токены · полные пути · поисковые запросы · содержимое библиотеки |
| `diagnostics/log/LogExporter.kt` | G1.3 | Выгрузка в файл + предзаполненный шаблон issue с версией, устройством, API |
| `diagnostics/CrashHandler.kt` | G1.3 | Локальное сохранение стектрейса, предложение приложить при следующем запуске |
| `ui/settings/DiagnosticsScreen.kt` | G1.3 | Экран диагностики, кнопка выгрузки |
| `test/diagnostics/LogRedactorTest.kt` | G1.2 🔒 | **Тесты на утечки.** Без них «выгрузить лог» рискует стать сливом личных данных в публичный issue |

## G2. Вендорские ограничения (фича 141)

| Файл | ID | Ответственность |
|---|---|---|
| `diagnostics/vendor/ServiceKillDetector.kt` | G2.1 | Определение, что сервис был убит не нами |
| `diagnostics/vendor/VendorDetector.kt` | G2.2 | Определение производителя: Xiaomi, Samsung, Huawei, OPPO, прочие |
| `diagnostics/vendor/VendorIntents.kt` | G2.3 | Интенты в настройки автозапуска и оптимизации батареи под каждого вендора |
| `ui/settings/VendorGuideScreen.kt` | G2.2, G2.4 | Экран «Почему музыка останавливается», ссылка на dontkillmyapp.com |
| `diagnostics/vendor/VendorPromptState.kt` | G2.4 | Показать **один раз**, запомнить |

## G3. Обработка ошибок

| Файл | ID | Ответственность |
|---|---|---|
| `core/AppError.kt` | G3 | Типизированные ошибки приложения |
| `core/ErrorPresenter.kt` | G3 | **Решение, показывать ли ошибку пользователю.** Принцип: тихая деградация там, где система справляется сама |
| `library/TrackAvailability.kt` | G3.3 📌 | Состояние доступности трека. **Пропавший файл помечается, а не удаляется** — фундамент концепции «каталог не умирает» |

---

# Эпик H. Приёмка

Новых файлов почти нет — это прогон и замеры.

| Артефакт | ID | Назначение |
|---|---|---|
| `androidTest/acceptance/BackgroundPlaybackTest.kt` | H2 📌 | Автоматизируемая часть теста фона |
| `androidTest/acceptance/ScanPerformanceTest.kt` | H3 | Замер сканирования 5 000 треков |
| `docs/testing/v0.1-checklist.md` | H1 | Ручной чек-лист: звонок, наушники, перезапуск, батарея |
| `CHANGELOG.md` | H5 | Запись релиза |
| `fastlane/metadata/android/en-US/` | H5 | Каркас метаданных F-Droid. Наполняется к v0.5 |

> **H2 и H4 полностью автоматизировать нельзя.** Два часа фона с выключенным экраном и замер расхода батареи проверяются вручную на живом устройстве — желательно на нескольких, включая Xiaomi или Samsung, где убийцы процессов наиболее злые.

---

# Ресурсы

| Файл | Содержание |
|---|---|
| `res/values/strings.xml` | Английские строки — **основной язык** |
| `res/values-ru/strings.xml` | Русские строки |
| `res/values/themes.xml` | Тема приложения, splash |
| `res/xml/backup_rules.xml` | Пустой каркас — наполняется в v0.2 вместе с CRDT-журналом |
| `res/drawable/` | Иконки транспорта, плейсхолдер обложки, иконки вкладок |
| `res/mipmap-*/ic_launcher` | Иконка приложения — временная, финальная в промо-материалах |

---

# Порядок работы

Внутри волны задачи не независимы. Рекомендуемая последовательность:

```
1.  A1 → A2        Каркас и скелет. Без них некуда класть остальное
2.  A1.3           📌 Flavor — строго здесь, позже дороже
3.  B1             📌 Абстракция AudioEngine — до любой реализации
4.  B2 → B3        Media3 и фон. Здесь появляется первый звук
5.  C1 → C2 → C3   Библиотека. Появляется что играть
6.  C4             Списки
7.  D1 → D2        Очередь
8.  E1 → E2        UI плеера
9.  B4             Системные взаимодействия — проверяются, когда всё играет
10. E3 → E4        Панели и меню
11. F              Онбординг — делается последним, когда ясно, что настраивать
12. G              Надёжность
13. A3             Документация — дописывается по факту решений
14. H              Приёмка и релиз
```

**Почему онбординг последним:** мастер настраивает то, что уже существует. Написать его в начале — значит переписать в конце.

**Почему документация предпоследней:** ADR пишутся по факту принятых решений, а не до. Но `ARCHITECTURE.md` стоит начать на шаге 1 и дописывать по ходу — иначе AI-агент каждую сессию будет восстанавливать картину с нуля.

---

# Что из v0.1 переживёт v0.2

Полезно знать заранее, чтобы не вкладываться в обречённое.

| Останется | Будет переписано 🔁 |
|---|---|
| `audio/engine/*` — абстракция и модели | `library/db/*` — Room целиком уходит в Rust |
| `audio/media3/*` — реализация движка | `library/scan/*` — сканер переезжает в ядро |
| `queue/*` — модель очереди | `library/db/entity/*` — заменяется на `Track/Version/Source` |
| `ui/*` — все экраны | `library/scan/TagReader.kt` — теги читает Rust |
| `diagnostics/*` | |
| `LibraryRepository.kt` — интерфейс останется, внутренности заменятся | |

**Вывод:** эпик C — расходный материал, кроме `LibraryRepository`. Эпики B, D, E — фундамент. Вкладываться в качество стоит именно в них.
