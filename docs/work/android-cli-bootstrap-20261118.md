# Android CLI bootstrap — 2026-11-18

Фактическая модель/effort: `gpt-6-luna`, `medium` (назначение координатора).
Рабочая ветка/worktree: `work/yu5f`, `/home/puflik/src/Plinth-yu5f`.

## Объём и ход

Разрешено только получить официальный Android CLI, проверить его команды и
совместимость sdkmanager wrapper. Не устанавливаются emulator, NDK, images,
AVD и приложения; runtime не запускается. Существующие `latest` и bootstrap
не перезаписываются. PID adb 61195 не затрагивается.

Официальный источник и локально проверенные сведения о загрузке приведены
в разделе результата ниже. Исходный bootstrap зависал на скачивании CLI.

Wrapper читает `ANDROID_CLI_BIN`, передаёт `--sdk="$SDK_ROOT" sdk` и выводит
версию из указанного бинарника. Корень SDK для будущей проверки:
`/home/puflik/Android/Sdk`.

Получение CLI и проверки описаны ниже; этот этап не устанавливает
дополнительные SDK-пакеты и не подтверждает готовность эмулятора/runtime.

## Результат

Источник: `https://dl.google.com/android/cli/latest/linux_x86_64/android-cli`.
Прямая загрузка IPv4 без proxy завершена за 31 секунду; curl использовал
`--connect-timeout 10 --max-time 180 --fail --location`. Размер — 79,497,240
байт. SHA-256:
`9803784ddf24d4699929eece519ffa8b066afa9a2b93bd58749b3c6d2caaeeec`.
`file` подтвердил ELF 64-bit LSB pie executable, x86-64, dynamically linked.
Проверка и hash выполнены до первого запуска. Файл размещён отдельно:
`/home/puflik/Android/Sdk/cmdline-tools/android-cli-20261118/android-cli`.
`latest/bin/sdkmanager` и bootstrap не менялись.

Проверенные команды и результаты:

- `android-cli --no-metrics --version` → `1.1.16919734`.
- `android-cli --no-metrics --sdk=/home/puflik/Android/Sdk sdk --help` → справка успешно показала `install`, `list`, `remove`, `update`.
- `android-cli --no-metrics --sdk=/home/puflik/Android/Sdk sdk list --help` → справка подтверждает `sdk list` и описание installed/available.
- `android-cli --no-metrics --sdk=/home/puflik/Android/Sdk sdk list` → exit 0; перечислены установленные Build-Tools 36/37, Platform-Tools 37.0.1 и Platform 37.0.
- `ANDROID_CLI_BIN=<путь выше> latest/bin/sdkmanager --version` → exit 0, `1.1.16919734 (Android CLI)`.
- `ANDROID_CLI_BIN=<путь выше> timeout 60 latest/bin/sdkmanager --list_installed` → exit 0; тот же список локально установленных пакетов.

Wrapper сообщил предупреждение о deprecated sdkmanager; fallback через
`ANDROID_CLI_BIN` работает. CLI/help/list не устанавливали SDK-пакеты. Вызов
версии впервые вывел `Unpacking embedded installation...`; в целевой новой
директории остался только заданный исполняемый файл. Эмулятор, NDK, images,
AVD, APK и runtime не проверялись и не готовы по результатам этой задачи.
PID adb 61195 не затрагивался.

Следующий шаг Terra: повторить остановленный UI baseline setup через новый
CLI, если координатор снимет предыдущий стоп; установки выполнять отдельным
явным решением, затем выбирать и проверять конкретный emulator serial.
