# Contributing to Plinth

Thanks for looking. Plinth is **pre-alpha**: the first wave, v0.1 "Sound", is
nearly done and nothing is released yet. Small, focused contributions are the
easiest to accept right now.

## What is welcome

| Kind | Notes |
|---|---|
| **Translations** | Add `app/src/main/res/values-<lang>/strings.xml`. Keep every placeholder (`%1$s`, `%d`) and every plural form your language needs |
| **Bug fixes** | With a test that fails before the fix, if the code is testable on the JVM |
| **Small UI fixes, typos** | Screenshots before/after help |
| **Themes** | Colour schemes on top of Material 3 |
| **Provider fixes** | Once online providers exist (later waves) |

**Open an issue first** for anything larger: a new feature, a change to a
public interface (`AudioEngine`, `LibraryRepository`, `PlaybackController`),
a new dependency, or a refactoring across packages. The design is written down
(see below) and a change that fights it will not be merged, however good the
code.

Not accepted: DRM circumvention of any kind, analytics or telemetry,
proprietary SDKs, anything that works only with Google Play services. Plinth
ships on GitHub Releases and F-Droid, never on Google Play.

## Building

- **JDK 21 or newer.** The JBR bundled with Android Studio works; point
  `JAVA_HOME` at it when using a terminal. CI uses Temurin 21.
- **Android SDK**, path in `local.properties` as `sdk.dir=...` (Android Studio
  writes it). The build downloads missing platforms itself. Android Studio must
  support AGP 9.
- **Rust, Android NDK and cargo-ndk** for the Rust core in `core/` — versions
  and setup in [docs/BUILD.md](docs/BUILD.md).

```bash
./gradlew assembleGithubDebug
```

Two flavors are built from the same sources: `github` (checks for updates
itself) and `fdroid` (F-Droid updates it). Anything flavor-specific lives in
`app/src/github` or `app/src/fdroid`, never in `main`.

## Before you open a pull request

Run what CI runs:

```bash
./gradlew ktlintCheck detekt
./gradlew testGithubDebugUnitTest testFdroidDebugUnitTest
./gradlew assembleGithubDebug assembleFdroidDebug assembleGithubDebugAndroidTest
```

and, if you touched `core/`, in `core/`:

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
```

`./gradlew ktlintFormat` fixes formatting in place; the ktlint report is in
`app/build/reports/ktlint`.

If you touched `audio/media3`, `library/db`, `library/scan`, `ffi` or `core/`,
also run the instrumented tests on an emulator (the reference is API 36):

```bash
./gradlew connectedGithubDebugAndroidTest
```

Note that this uninstalls the debug app from the device when it finishes.

## How the code is written

- **Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) first**, then the
  decision records in [docs/adr/](docs/adr/). Both are in Russian for now;
  the package table and the diagrams are readable through a translator.
- **Tests first.** Behaviour starts as a failing test. Engines and the library
  are specified by contract tests (`AudioEngineContractTest`,
  `LibraryRepositoryContractTest`) that both the real implementation and the
  fake in `app/src/sharedTest` must pass. Screens and the queue are tested on
  the JVM against these fakes, without an emulator.
- **Boundaries are enforced by tests.** `audio/engine`, `queue` and the
  artwork core import nothing from Android; `ui` reaches playback only through
  `PlaybackController` and the library only through `LibraryRepository`,
  `LibraryScan` and `FolderSettings`. Guard tests (`EngineBoundaryTest`,
  `LibraryBoundaryTest`, `ArtworkBoundaryTest`, the queue guard in
  `ShuffleOrderTest`) fail on a violation.
- **Style:** ktlint with the repository `.editorconfig`, detekt with
  `config/detekt.yml`. Don't silence a detekt rule to get green; restructure.
- **Privacy in logs.** Never pass track titles, file paths, URIs or search
  queries to `AppLog`. `LogRedactor` strips what it can recognise, but titles
  cannot be recognised by pattern.

### Languages

- **English:** identifiers, commit messages, pull requests, issues, and
  user-facing strings in `values/strings.xml` (the default locale).
- **Russian:** code comments and KDoc, and the internal documents —
  `docs/ARCHITECTURE.md`, `docs/adr/`, `docs/decisions.md`, the plan. This is
  how the project is written today; a comment in English in a file you touch
  is fine.
- **Every new string needs a Russian translation** in
  `values-ru/strings.xml` — `RussianTranslationTest` fails the build
  otherwise. If you don't speak Russian, say so in the PR and it will be
  translated during review.

### Commits

One logical change per commit, a short English summary in the imperative or
descriptive form used in the history, e.g. *Skip tracks that can't be played
and tell about it once*.

## Reporting problems

In the app: **Settings → Diagnostics → Report a problem** opens a GitHub issue
pre-filled with the app version, device and Android version. **Save log**
exports a log with file names, paths and search queries removed; attach it if
you're comfortable with that.

## Licence

Plinth is licensed under the [GNU AGPLv3](LICENSE). There is **no CLA**: you
keep the copyright on your contribution and license it under AGPLv3 by
submitting it. The reasons are in
[docs/adr/0003-license-agplv3.md](docs/adr/0003-license-agplv3.md).
