[← Оглавление плана](README.md) · [Корень проекта](../../plan.md)

# 4. Модель данных

## 4.1. Сущности

| Сущность | Ключевые поля |
|---|---|
| **Track** | id, canonical_title, canonical_artist, mbid_work, created_at, liked, play_count, last_played_at, rating, availability_state |
| **Version** | id, track_id, label (original/clean/remaster/live/acoustic/radio_edit), is_explicit, duration_ms, fingerprint, mbid_recording, release_year |
| **Source** | id, version_id, provider, external_id, format, bitrate, is_lossless, availability, cache_state, local_path, last_checked_at |
| **Artist** | id, name, mbid, bio, image, aliases |
| **Album** | id, title, artist_id, mbid_release, year, label, country, cover_path, disc_count |
| **Playlist** | id, name, kind (manual/smart), rules, created_at, position_list |
| **PlayEvent** | id, track_id, version_id, source_id, started_at, listened_ms, completion_ratio, skip_position, context (time_of_day, weekday, output_device, previous_track) |
| **TasteProfile** | веса по артистам, жанрам, тегам, эпохам, аудио-характеристикам; временные срезы |
| **MergeDecision** | id, track_a, track_b, decision (merged/split), by (auto/manual), confidence, created_at |
| **EnrichmentTask** | id, entity, kind, state, attempts, next_retry_at |
| **DownloadTask** | id, source_id, state, priority, progress, created_at |

## 4.2. Идентичность и склейка

Четыре уровня разрешения идентичности, применяются каскадом:

| Уровень | Механизм | Надёжность |
|---|---|---|
| **Золотой** | **MBID** — идентификатор MusicBrainz. Глобальный, стабильный, канонический | 100% |
| **Серебряный** | **AcoustID / Chromaprint** — акустический отпечаток звука. Работает даже при полностью вранных тегах | ~95–99% |
| **Бронзовый** | Нормализованная строка `artist + title` + длительность ±3 с | ~80% |
| **Ручной** | Пользователь говорит «это одно и то же» / «разъединить» | 100% |

**Критическое требование: склейка обратима.** Ошибочно слитые треки — адская боль, если откатить нельзя. Каждое решение о склейке пишется в `MergeDecision` и может быть отменено.

**Спорные случаи не решаются автоматически.** Они копятся в очередь, пользователь разбирает свайпом. Решение запоминается навсегда и переносится между устройствами через синк.

## 4.3. Журнал операций и CRDT

**Ключевое проектное решение, принимаемое на старте:** состояние библиотеки хранится не как снимок БД, а как **журнал операций (CRDT)**.

**Почему это важно:** синхронизация становится безразлична к транспорту. LAN, P2P, флешка, ручной экспорт — всё работает одинаково. Добавить новый транспорт позже = ноль изменений в модели данных.

Что попадает в журнал: лайки, плейлисты, история прослушиваний, решения о склейке, настройки, чёрный список рекомендаций.
Что не попадает: сами аудиофайлы, кэш, локальные пути (они привязаны к устройству).

Кандидаты библиотек: `automerge`, `yrs` (порт Yjs).

## 4.4. Хранилище

- **SQLite** через `rusqlite` / `sqlx` — основная БД, живёт в Rust-ядре
- **Файловый кэш** — аудио, обложки, отпечатки
- **CRDT-журнал** — отдельный файл, синхронизируемый
- Миграции схемы — версионируемые, с откатом

---
