# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Rust core groundwork: the app now ships a native library built from `core/`
  for four ABIs and starts it in the background at launch. No user-visible
  features depend on it yet; its log lines go to the app log, and a crash
  inside the core reaches the app as an error instead of closing it.

## [0.1.0] - 2026-09-24

The first wave, "Sound": a player for the music files on your phone. This is
an early pre-release — online sources, playlists and sync come in later waves.

### Added

#### Playback

- Plays local music files in the background, with a media notification,
  lock-screen controls and headset buttons.
- Pauses for calls and other players, lowers the volume under navigation
  prompts, pauses when headphones are unplugged instead of switching to the
  speaker.
- Comes back where you left off after a restart: the same queue, paused at the
  same second.
- Skips files that can't be played — moved, deleted, damaged or in an
  unsupported format — and says so briefly, once per file.

#### Library

- Built from Android's media index: tracks, albums, artists and folders in
  natural order ("Track 2" before "Track 10", "The Beatles" under B).
- Search by title, artist and album.
- A choice of folders to scan (Music and Download by default). A file that
  disappears is hidden, not forgotten, and returns when the file does.
- Any audio file can be opened through the system file picker without adding
  it to the library.

#### Queue and player

- A queue that keeps what you added by hand when you start something else,
  with shuffle and repeat.
- Player screen with artwork, a mini-player, queue editing, links to the album
  and the artist, and gestures.

#### First run

- A short setup wizard (permission and folders) that can be skipped, help for
  an empty library, and a start screen that continues where you left off.
- English and Russian; on Android 13+ the language can be chosen per app.

#### Reliability

- A local log with file names, paths and search queries removed; it can be
  saved from Settings.
- After a crash the app offers to save a report; it goes to GitHub only if you
  decide to report it.
- If the phone's firmware stops background playback, the app explains it once
  and opens the right battery settings on Xiaomi, Samsung, Huawei and OPPO.

#### For developers

- The playback engine sits behind an `AudioEngine` interface with a contract
  test suite; the library behind `LibraryRepository`, with its own contract.
- CI builds and tests both flavors (`github`, `fdroid`); architecture decision
  records in `docs/adr/`, a contributing guide in `CONTRIBUTING.md`.

[Unreleased]: https://github.com/Puflik/Plinth/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/Puflik/Plinth/releases/tag/v0.1.0
