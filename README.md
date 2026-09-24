# Plinth

*Русская версия: [README.ru.md](README.ru.md)*

An autonomous Android music player in which **a track is a logical entity, not a
file and not a link**. One track may have several sources — a local file, an
online stream, a purchase, an archive recording — and the player picks the best
one available right now. There is no "offline mode" and "online mode": the split
only ever existed because other players equate a track with its source.

There is no central backend. The device itself is the server, desktop is an equal
peer, and synchronisation is an optional layer on top of an operation log.

## Status

**Pre-alpha. Nothing to install yet.**

Current wave is **v0.1 "Sound"** — a player for local files. Done so far:

- the audio core: a player abstraction with a Media3 implementation behind it,
  background playback with a media session, notification and headset controls;
- a library built from the system media index: tracks, albums, artists and
  folders, search, the choice of folders to scan;
- a queue with shuffle, repeat and manual additions that survives a restart;
- the player screen, a mini-player, queue editing and gestures;
- a first-run wizard and an adaptive start screen;
- English and Russian;
- reliability: a local log with personal data removed, crash reports you
  choose to save, help when the phone's firmware kills background playback,
  and skipping files that can't be played.

Left before the first release: acceptance testing. Online sources, one track
from several sources and sync between devices come in later waves.

| | |
|---|---|
| Package ID | `io.github.puflik.plinth` |
| Min Android | 8.0 (API 26) |
| Compiled against | API 37 |
| Distribution | GitHub Releases and F-Droid. **Not** Google Play |
| Licence | [AGPLv3](LICENSE) |

## Building

Requirements:

- **JDK 21 or newer.** The one Android Studio ships in `<studio>/jbr` (JDK 25)
  works; from a terminal point `JAVA_HOME` at it. CI uses Temurin 21. Gradle
  will not run on JDK 8.
- **Android SDK.** Put its path into `local.properties` as `sdk.dir=...`
  (Android Studio writes this file for you). Missing platforms are downloaded
  by the build.
- **Rust, Android NDK and cargo-ndk** (since v0.2): the build compiles the Rust
  core in `core/` for four ABIs. Versions and setup — [docs/BUILD.md](docs/BUILD.md).

Two product flavors are built from the same source:

| Flavor | Update checker |
|---|---|
| `github` | enabled — there is no automatic updating outside an app store |
| `fdroid` | disabled — F-Droid updates apps itself |

```bash
./gradlew assembleGithubDebug        # APK, github flavor
./gradlew testGithubDebugUnitTest    # unit tests
./gradlew ktlintCheck detekt         # style and static analysis
```

`ktlintFormat` fixes formatting in place.

## Contributing and documentation

- [CONTRIBUTING.md](CONTRIBUTING.md) — how to build, what is welcome, how the
  code is written.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — packages, boundaries and data
  flow; [docs/adr/](docs/adr/) — the key decisions and why (both in Russian).

## Licence

GNU Affero General Public License v3.0 — see [LICENSE](LICENSE).

The goal is blunt: nobody should be able to fork this into a closed ecosystem
and sell it.
