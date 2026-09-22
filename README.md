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

Current wave is **v0.1 "Sound"** — the audio core: a player abstraction, a Media3
implementation behind it, background playback with a media session. The library,
the queue and the online sources come in later waves.

| | |
|---|---|
| Package ID | `io.github.puflik.plinth` |
| Min Android | 8.0 (API 26) |
| Compiled against | API 37 |
| Distribution | GitHub Releases and F-Droid. **Not** Google Play |
| Licence | [AGPLv3](LICENSE) |

## Building

Requirements:

- **JDK 21.** Android Studio ships one in `<studio>/jbr`; from a terminal point
  `JAVA_HOME` at it. Gradle will not run on JDK 8.
- **Android SDK.** Put its path into `local.properties` as `sdk.dir=...`
  (Android Studio writes this file for you). Missing platforms are downloaded
  by the build.

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

## Licence

GNU Affero General Public License v3.0 — see [LICENSE](LICENSE).

The goal is blunt: nobody should be able to fork this into a closed ecosystem
and sell it.
