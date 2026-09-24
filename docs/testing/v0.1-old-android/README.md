# Улики: v0.1 на Android 8.0 и 11

Прогон 2026-09-24/25 на эмуляторах `Plinth_API_26` (Android 8.0) и
`Plinth_API_30` (Android 11), отладочная сборка ветки `hotfix/0.1.1`. Что
найдено и как воспроизвести — `docs/decisions.md`, раздел «v0.1 на Android 8
и 11» (находки Н1–Н8); сводка по пунктам — колонка «Эмулятор» в
`docs/testing/v0.1-checklist.md`.

Префикс имени — версия: `api26-…` — Android 8.0, `api30-…` — Android 11.

| Файл | Что в нём |
|---|---|
| `*-connected.txt` | итог `connectedGithubDebugAndroidTest` по классам; строки `AudioTrack` в logcat каждого теста форматов; аудиодекодеры образа |
| `api26-flac-silent.txt` | FLAC в приложении: сессия играет, позиция идёт, игроков в `dumpsys audio` нет (Н1) |
| `api30-play.txt` | FLAC и MP3 на 11 — оба со звуком |
| `*-background_check*.txt` | `tools/background_check.py --minutes 3`: на 8.0 — ложный FAIL и прогон с разбором обоих форматов (Н8); на 11 — PASS |
| `*-call.txt` | `adb emu gsm call/accept/cancel`: состояние звонка и сессии |
| `api30-restart.txt`, `api30-reboot.txt` | перезапуск: `force-stop` (без диалога) и перезагрузка (ложный диалог, Н5) |
| `*-service_lifetime.txt`, `api26-headset_after_service.txt` | жизнь службы после паузы и медиакнопка «play» (Н6) |
| `api26-06-roots.png`, `api26-07-menu.png` | выбор папки на 8.0 без накопителя (Н4) |
| `api26-13-flac-playing.png` | FLAC «играет» — пара к `api26-flac-silent.txt` |
| `api26-15-restored.png`, `api30-08-after-reboot.png` | ложный «Why music stops» (Н5) |
| `api30-02-after-back.png`, `api30-03-dialog-again.png` | «Назад» в диалоге разрешения и повторный диалог после перезапуска (Н7) |
| `*-shade*.png`, `*-albums.png`, `api30-07-restored.png`, `api26-16-call.png` | то, что прошло: уведомление, обложки, восстановление, звонок |
