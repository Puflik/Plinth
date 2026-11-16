# yu5f — сверка полного CI перед VM-B1 correction

Фактическая проверка координатором: **2026-11-17, +05:00**. Повторных сборок,
агентов и изменений production/tests нет. Проверены сохранённые результаты
GitHub Actions; это дополнение к принятому независимому ревью тестов.

## Точные исходники и результаты

- Main `2a067fec95c6b4bb9d1d065243965ed679dfb2af`: run
  [43749054749](https://github.com/Puflik/Plinth/actions/runs/43749054749) SUCCESS.
- Work `7534c77673c74b9f186eadd2a9ef5c474603a5e9`: run
  [43749054774](https://github.com/Puflik/Plinth/actions/runs/43749054774) FAILURE.
  Rust job `132918706248` SUCCESS; Android job `132918705902` упал в
  `Unit tests, both flavors`.
- Artifact `reports`, id `14175483515`, при проверке `expired: false`,
  546088 bytes. Скачанный ZIP SHA-256:
  `3c5a89c0f1efd85f1e8992953863df28086827267445d69989d2b0587a0b0f14`.
  Локально `/tmp/plinth-yu5f-ci-reports-20261117.zip`, сводка
  `/tmp/plinth-yu5f-ci-summary-20261117.json`.

XML прочитаны непосредственно из ZIP без запуска их содержимого:
**170 Github suites, 1114 tests: 1110 passed, 3 failures, 0 errors, 1 skipped**.
Все три failures — принятые guards из `Yu5fAdditionalSearchTest`:

| Сценарий раннего retry после grace | Фактическое падение |
| --- | --- |
| Removed YouTube | Три readiness calls вместо двух |
| Master-off | Три readiness calls вместо двух |
| Retained Internet Archive | Устаревшая ALL вместо ARCHIVE_ONLY |

Новых падений в остальной Github suite нет. `Yu4gGatedSearchInTest`:
**4 tests, 0 failures/errors/skips**. Значит отдельная миграция CI-C1
проверена в полном Github CI, прежнее падение устранено. XML Fdroid в ZIP
отсутствуют; both-flavor PASS или исполнение Fdroid этим не доказаны.

## Вывод и продолжение

Прежняя неопределённость конкретных CI failures закрыта для точного `7534c77`.
VM-B1 остаётся открытым, baseline14 `b52b256` не меняется. Следующий этап —
уже принятая ограниченная коррекция SearchViewModel/отчёта, 85 scoped tests
GREEN, сохранение защиты14, независимое review и полный CI обеих flavors.
Эта сверка не принимает production, UI и живое воспроизведение YouTube.

Обычный `gh run download` дошёл до Azure Blob, но соединение сорвалось.
Прямой IPv4 без proxy успешно скачал тот же artifact; Authorization передан
только GitHub и удалён при смене hostname на redirect. Секреты не выводились.
Лимиты после временной недоступности API: 15% primary / 97% weekly; новый
агент не запускался. Weekly reset — 17 ноября 09:39 +05.

Согласно постоянному разрешению автора на вчерашний пропуск, коммит этого
реального отчёта получает обе даты Git **2026-11-16 12:00 +05**. Фактическая
дата проверки остаётся 17 ноября; опубликованная история не переписывается.
