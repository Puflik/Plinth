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
- The core keeps its own database and a journal of likes, ratings, playlists
  and listening history in the app's private storage. The journal is the
  source of truth: a deleted or damaged database is rebuilt from it at
  launch. Screens do not use the core yet.

## [0.1.1] - 2026-09-25

Fixes from the review of the first wave and a run on Android 8 and 11.

### Fixed

- Editing the queue while paused (play next, add to queue, shuffle, repeat,
  removing a track) no longer resets the saved position to 0:00.
- A full phone storage no longer crashes the app during playback: the queue,
  the log and the playback marker just skip the write.
- The player fits the screen in landscape and on small phones with large
  fonts: the cover shrinks, the controls stay visible.
- Revoking access to music no longer marks the whole library as missing.
- Play works again after "Stop" from a Bluetooth remote, car or watch, and
  after a playback error — it retries the same track.
- Tapping the notification or the media card opens the app.
- Headset "play" resumes the music even after a long pause, when Android has
  already closed the playback service.
- File paths with spaces no longer leak into the saved log.
- Links and the language setting no longer crash the app on phones without a
  browser or without the per-app language screen.
- The "Why music stops" dialog no longer shows up after a reboot or after the
  battery ran out.
- Closing the crash report dialog by accident no longer deletes the report.
- Error messages no longer pile up while the app is in the background.
- After dismissing the permission dialog with Back on Android 11+, access can
  be asked for again.
- FLAC on Android 8.0 and ALAC on Android 8–11, which the system cannot
  decode, are reported as unsupported and skipped instead of playing silence.
- On Android 8 the folder picker shows the phone storage right away.

### Changed

- The app is excluded from Android backup and device-to-device transfer:
  nothing leaves the device.

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

[Unreleased]: https://github.com/Puflik/Plinth/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/Puflik/Plinth/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/Puflik/Plinth/releases/tag/v0.1.0
