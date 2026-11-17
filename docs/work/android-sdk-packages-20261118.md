# Android SDK packages — 2026-11-18

Фактическая модель/effort: `gpt-6-luna`, `medium` (назначение координатора).
Ветка/worktree: `work/yu5f`, `/home/puflik/src/Plinth-yu5f`.

## Ход

- Разрешённый объём: установить только отсутствующие `emulator`,
  `system-images;android-35;google_apis;x86_64` и `ndk;28.2.13676358`
  в `/home/puflik/Android/Sdk`.
- Отчёт bootstrap: `docs/work/android-cli-bootstrap-20261118.md`;
  CLI `1.1.16919734`, путь и SHA-256 сверены с ним.
- `sdk install --help` подтверждает форму
  `sdk install <package>[@<version>]`; поддерживается несколько packages.
- Первоначальный вызов CLI внутри sandbox не прошёл из-за read-only
  `/home/puflik/.android`; повтор вне sandbox разрешён и справка прошла.
- `sdk list` вне sandbox прошёл; три целевых пакета отсутствовали.
- Установка запущена одним вызовом; emulator archive пройден, сейчас
  загружается `sys-img/google_apis/x86_64-35_r09.zip`.
- Временный файл загрузки образа достиг 1,400,263,696 байт
  (рост подтверждён повторными проверками); свободно 55 GiB.
- Загрузка образа завершилась, затем начался официальный архив
  `android-ndk-r28c-linux.zip`; его временный файл рос при проверке.
- Полный install log: `/tmp/android-sdk-packages-20261118-install.log`.

## Установка и проверки

Установка завершилась успешно одним вызовом официального CLI; exit code 0.
Созданы только указанные SDK packages в `/home/puflik/Android/Sdk`:

- `emulator` — package metadata `37.2.13`; бинарник сообщает
  `Android emulator version 37.2.13.0 (build_id 16740729)`.
- `system-images;android-35;google_apis;x86_64` — metadata revision `9.0.0`.
- `ndk;28.2.13676358` — metadata и `source.properties` подтверждают
  revision `28.2.13676358`, release `r28c`.

Проверки после установки: `sdk list` показывает все три пакета;
`emulator -accel-check` сообщает `KVM (version 12) is installed and usable`.
Проверки выполнены read-only вне sandbox. Установка log:
`/tmp/android-sdk-packages-20261118-install.log`.

Не создавались AVD и не запускались emulator, adb, app, tests или builds.
Телефон и посторонний adb PID 61195 не затрагивались. Kernel, BIOS, groups,
permissions и device nodes не менялись. Остальные SDK packages/defaults,
project dependencies и user-data formats не менялись. Ранее существовавшие
`latest`/bootstrap не заменялись.
