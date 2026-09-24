# Сборка Plinth

С v0.2 в APK есть Rust-ядро (`core/`): Gradle сам вызывает `cargo` и
собирает `libplinth_ffi.so` под четыре ABI вместе с Kotlin-биндингами к ней.
Поэтому кроме привычного Android-тулчейна нужны Rust, NDK и `cargo-ndk`.
Что пересекает границу Kotlin ↔ Rust и почему — [ADR 0010](adr/0010-ffi-boundary.md).

## Что поставить

| Что | Версия | Где зафиксирована |
|---|---|---|
| JDK | 21 (JBR из Android Studio подходит) | `gradle.properties` |
| Android SDK | compileSdk 37 | `app/build.gradle.kts` |
| Android NDK | 28.2.13676358 | `ndk` в `gradle/libs.versions.toml` |
| Rust | 1.98.1 + rustfmt, clippy, четыре Android-таргета | `core/rust-toolchain.toml` |
| cargo-ndk | 4.1.2 | здесь и в `.github/workflows/*.yml` |

1. **Rust** — через [rustup](https://rustup.rs). Нужную версию с таргетами
   rustup ставит сам при первом `cargo` в `core/`; явно —
   `cd core && rustup toolchain install`. На Windows нужен ещё линкер MSVC
   (Visual Studio Build Tools, «Desktop development with C++»): генератор
   биндингов собирается под саму машину сборки.
2. **cargo-ndk** — `cargo install cargo-ndk --version 4.1.2 --locked`.
3. **NDK** — Android Studio → Settings → Languages & Frameworks → Android
   SDK → SDK Tools → «Show Package Details» → NDK (Side by side)
   28.2.13676358. Из командной строки — `sdkmanager "ndk;28.2.13676358"`
   (новый `sdkmanager`, обёртка над Android CLI, пишет пакеты через «/»:
   `ndk/28.2.13676358`).

После установки Rust перезапустите Android Studio и демон Gradle
(`./gradlew --stop`): иначе они не видят `cargo` в `PATH`.

## Как собирается

Плагин `plinth.rust` (`build-logic/`) добавляет в `:app` две задачи:

| Задача | Что делает | Куда кладёт |
|---|---|---|
| `cargoNdkBuild` | `cargo ndk … build --release -p plinth-ffi` под ABI из `ndk.abiFilters` и minSdk приложения | `app/build/rust/jniLibs/<abi>/libplinth_ffi.so` → в APK |
| `uniffiKotlin` | собирает `plinth-ffi` под машину сборки и генерирует по ней Kotlin-биндинги | `app/build/generated/uniffi/kotlin` → пакет `…ffi.generated` |

Обе задачи инкрементальны: без правок в `core/` Gradle их пропускает.
Компиляции Kotlin и JVM-тестам нужен только Rust; NDK нужен, когда
собирается APK. Сгенерированный код в репозиторий не попадает, при загрузке
он сверяет контрольные суммы API с `.so`.

Rust собирается профилем `release` и для отладочного APK: `opt-level = "z"`,
LTO и `strip` (размер, A2.4) и **`panic = "unwind"`**. С `abort` перестаёт
работать перехват паник на границе.

## Размер APK (A2.4)

Замер 2026-09-24, релиз `github`, R8:

| | v0.1.0 | с ядром (v0.2, шаг 1) |
|---|---|---|
| APK | 2,9 МБ | 4,9 МБ |
| `libplinth_ffi.so`, четыре ABI | — | 1,36 МБ (250–380 КБ на ABI) |
| `libjnidispatch.so` (JNA), четыре ABI | — | 0,52 МБ |

`.so` лежат в APK несжатыми: так их грузят прямо из APK, без распаковки при
установке. Сжатие (`packaging.jniLibs.useLegacyPackaging = true`) сократило
бы `lib/` с 1,94 до 0,90 МБ. До бюджета 30 МБ далеко, поэтому не включено.
64-битные `.so` выровнены под страницы 16 КБ (Android 15+), NDK r28 делает
это сам.

## Команды

```bash
./gradlew assembleGithubDebug            # APK с ядром под все ABI
./gradlew testGithubDebugUnitTest        # JVM-тесты (ядро не грузится)
./gradlew connectedGithubDebugAndroidTest  # на эмуляторе — в том числе PlinthCoreTest
```

В `core/` — то же, что проверяет CI:

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
```

## Частые ошибки

| Симптом | Причина и что делать |
|---|---|
| `Cannot run program "cargo"` | Gradle не видит Rust: перезапустите Android Studio и `./gradlew --stop` после установки rustup |
| `NDK not configured` / `NDK at … did not have a source.properties` | Нет NDK нужной версии — см. «Что поставить», п. 3 |
| `the lock file … needs to be updated but --locked was passed` | Поменялись зависимости в `Cargo.toml`: `cargo update -p <крейт>` в `core/` и закоммитить `core/Cargo.lock` |
| `error: linker 'link.exe' not found` (Windows) | Нет MSVC Build Tools — см. п. 1 |
| В приложении `UniFFI API checksum mismatch` | `.so` и биндинги из разных сборок: `./gradlew clean` и собрать заново |
| `UnsatisfiedLinkError … libplinth_ffi.so` | ABI устройства нет в `ndk.abiFilters`, или `.so` не попала в APK — проверить `unzip -l app.apk lib/` |
| Отладочная сборка работает, в релизе в логе `Core: core did not start` | R8 переименовал классы JNA или биндингов — правила в `app/proguard-rules.pro` |
| `unwrap_used` / `expect_used` / `panic` от clippy | В коде ядра запрещены — вернуть `CoreError` (A3.3); в тестах можно |
