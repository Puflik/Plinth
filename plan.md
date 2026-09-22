# Plinth — Техническое задание и план разработки

> **Статус:** v1.4 — интервью завершено, закрыто 24 вопроса, открытыми остаются 3
> **Дата:** 2026-09-21
> **Имя:** Plinth · package `io.github.puflik.plinth` · ядро `plinthcore`
> **Лицензия:** AGPLv3
> **Стадия:** теория. Кода нет и на этом этапе не пишется.
> **Как читать:** разделы 1–2 — зачем и что. Разделы 3–17 — как. Разделы 18–21 — что и в каком порядке делать. Разделы 22–27 — риски, ограничения, незакрытые вопросы.

---

## Оглавление

Полный текст разбит по главам в [docs/plan/](docs/plan/README.md).

| № | Раздел | Файл |
|---:|---|---|
| 1 | Обзор проекта | [01-overview.md](docs/plan/01-overview.md) |
| 2 | Ключевые концепции | [02-concepts.md](docs/plan/02-concepts.md) |
| 3 | Архитектура | [03-architecture.md](docs/plan/03-architecture.md) |
| 4 | Модель данных | [04-data-model.md](docs/plan/04-data-model.md) |
| 5 | Алгоритмы идентичности и версий | [05-identity.md](docs/plan/05-identity.md) |
| 6 | Провайдеры контента | [06-providers.md](docs/plan/06-providers.md) |
| 7 | Источники метаданных | [07-metadata-sources.md](docs/plan/07-metadata-sources.md) |
| 8 | Оффлайн: кэш и загрузки | [08-offline-cache.md](docs/plan/08-offline-cache.md) |
| 9 | Рекомендации и открытия | [09-recommendations.md](docs/plan/09-recommendations.md) |
| 10 | Синхронизация устройств | [10-sync.md](docs/plan/10-sync.md) |
| 11 | Требования к воспроизведению | [11-playback.md](docs/plan/11-playback.md) |
| 12 | Интерфейс и UX | [12-ui-ux.md](docs/plan/12-ui-ux.md) |
| 13 | Библиотека, теги, плейлисты, импорт | [13-library.md](docs/plan/13-library.md) |
| 14 | Производительность и ресурсы | [14-performance.md](docs/plan/14-performance.md) |
| 15 | Тестирование, CI и качество | [15-testing.md](docs/plan/15-testing.md) |
| 16 | Сообщество и поддержка | [16-community.md](docs/plan/16-community.md) |
| 17 | Надёжность, безопасность и эксплуатация | [17-reliability.md](docs/plan/17-reliability.md) |
| 18 | Таблица решений и умолчаний | [18-decisions-table.md](docs/plan/18-decisions-table.md) |
| 19 | Полный реестр фич | [19-feature-registry.md](docs/plan/19-feature-registry.md) |
| 20 | Дорожная карта | [20-roadmap.md](docs/plan/20-roadmap.md) |
| 21 | Распространение | [21-distribution.md](docs/plan/21-distribution.md) |
| 22 | Правовые границы | [22-legal.md](docs/plan/22-legal.md) |
| 23 | Ресурсы и оценка | [23-estimates.md](docs/plan/23-estimates.md) |
| 24 | Риски проекта | [24-risks.md](docs/plan/24-risks.md) |
| 25 | Критерии готовности | [25-readiness.md](docs/plan/25-readiness.md) |
| 26 | Открытые вопросы | [26-open-questions.md](docs/plan/26-open-questions.md) |
| 27 | Глоссарий | [27-glossary.md](docs/plan/27-glossary.md) |

---

> Этот файл — точка входа. Текст глав лежит в `docs/plan/`;
> правки вносятся туда, оглавление здесь обновляется вручную.
