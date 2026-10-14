# Plinth

*Русская версия: [README.ru.md](README.ru.md)*

An autonomous Android music player in which **a track is a logical entity, not a
file and not a link**. One track may have several sources — a local file, an
online stream, a purchase, an archive recording — and the player picks the best
one available right now. There is no "offline mode" and "online mode": the split
only ever existed because other players equate a track with its source.

There is no central backend. The device itself is the server, desktop is an equal
peer, and synchronisation is an optional layer on top of an operation log.

## Written by AI: an experiment

Plinth is written entirely by AI. Its author sets the direction, makes the
product decisions, approves the architecture records and tests the app on a real
phone; the code, the tests, the documentation and the code reviews are produced
by AI models (Claude, by Anthropic). The project is an experiment in how well AI
can build a large, non-trivial piece of software — a Rust core with a CRDT
journal, an Android app, a test suite and a release pipeline — and keep it
working release after release.

The work is split between models: one writes the task file with the decision
and the tests that fail without it, another writes the code, and a third reads
the plan and reviews the result before it is merged. The process is written down
in [docs/work/README.md](docs/work/README.md) (in Russian); every change goes
through CI.

Treat Plinth as early software. Bug reports are welcome.

## Status

**Version 0.2.3** — early, but usable: a player for the music on your phone,
with the Internet Archive as the first online source. Download the APK from
[GitHub Releases](https://github.com/Puflik/Plinth/releases); F-Droid comes
after v1.0.

What it does now:

- plays your music in the background, with notification, lock-screen and
  headset controls, and resumes where you left off;
- reads tags straight from your files on the phone and on SD cards (a scanner
  in the Rust core, not Android's media index): tracks, albums, artists and
  folders in natural order, artist sort names, search;
- searches the Internet Archive and plays its albums and concerts — FLAC on
  Wi-Fi, MP3 on a metered network; online sources can be turned off;
- likes, listening history and playlists (Liked, Recent and your own), with
  M3U, M3U8 and PLS import and M3U8 export;
- keeps likes, playlists and history in a journal that survives a reinstall: a
  copy in a folder you choose and in Android backup, and songs you moved,
  renamed or retagged are found again;
- does not lock you out when its data is damaged or cannot be updated: the
  library database is rebuilt from the journal and a scan;
- a local log with personal data removed, crash reports you choose to save,
  help when the phone's firmware stops background playback;
- English and Russian.

What comes next:

| Wave | What it brings |
|---|---|
| v0.3 "Identity" | Audio fingerprints (Chromaprint, AcoustID, MusicBrainz): one song in different files and sources is recognised as one track |
| v0.5 "Hybrid" | Bandcamp as a second source, one search across sources, cache and downloads — one track, many sources |
| v0.7 "Enrichment" | High-resolution covers, synced lyrics, artist information |
| v0.9 "Taste" | Scrobbling, smart shuffle and radio, SoundCloud |
| v1.0 "YouTube" | YouTube as a source |
| v1.5 "Desktop and sync" | A desktop client and sync between devices without a server |

The full roadmap is in [docs/plan/20-roadmap.md](docs/plan/20-roadmap.md) (in
Russian).

| | |
|---|---|
| Package ID | `io.github.puflik.plinth` |
| Min Android | 8.0 (API 26) |
| Compiled against | API 37 |
| Distribution | GitHub Releases now, F-Droid after v1.0. **Not** Google Play |
| Licence | [AGPLv3](LICENSE) |

## Privacy

No accounts, no ads, no analytics or telemetry. What leaves the device:

- while online sources are on, searches and streams go to archive.org;
- the journal of likes, playlists and history goes to Android backup, and only
  when the phone encrypts it (a screen lock is set); the music, the library
  database and the settings stay on the device;
- a log or a crash report, only if you share it yourself.

## Building

Requirements:

- **JDK 21 or newer.** The one Android Studio ships in `<studio>/jbr` (JDK 25)
  works; from a terminal point `JAVA_HOME` at it. CI uses Temurin 21. Gradle
  will not run on JDK 8.
- **Android SDK.** Put its path into `local.properties` as `sdk.dir=...`
  (Android Studio writes this file for you). Missing platforms are downloaded
  by the build.
- **Rust, Android NDK and cargo-ndk**: the build compiles the Rust core in
  `core/` for four ABIs. Versions and setup — [docs/BUILD.md](docs/BUILD.md).

Two product flavors are built from the same source:

| Flavor | Update checker |
|---|---|
| `github` | enabled — there is no automatic updating outside an app store |
| `fdroid` | disabled — F-Droid updates apps itself |

```bash
./gradlew assembleGithubDebug        # APK, github flavor
./gradlew testGithubDebugUnitTest    # unit tests
./gradlew ktlintCheck detekt         # style and static analysis
cd core && cargo test --workspace    # the Rust core
```

`ktlintFormat` fixes formatting in place.

## Contributing and documentation

- [CONTRIBUTING.md](CONTRIBUTING.md) — how to build, what is welcome, how the
  code is written.
- [CHANGELOG.md](CHANGELOG.md) — what changed in each release.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — packages, boundaries and data
  flow; [docs/adr/](docs/adr/) — the key decisions and why;
  [docs/work/README.md](docs/work/README.md) — how the AI models split the work
  (all in Russian).

## Licence

GNU Affero General Public License v3.0 — see [LICENSE](LICENSE).

The goal is blunt: nobody should be able to fork this into a closed ecosystem
and sell it.
