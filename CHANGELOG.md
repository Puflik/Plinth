# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.0] - 2026-09-28

The second wave, "Core and first source": the library and your likes,
playlists and history move into a core written in Rust, with a journal that
survives a reinstall, and Internet Archive becomes the first online source.

### Added

#### Online sources

- Search looks on Internet Archive too: tracks from your library come first,
  Internet Archive albums and concerts load below them. An album opens on its
  own screen with Play, Like, the queue and Add to playlist.
- Internet Archive tracks play in the background like local files and can be
  liked, queued and added to playlists; a small cloud marks them in lists. On
  Wi-Fi the best format is played (FLAC when there is one), on a metered
  network MP3.
- If the network drops mid-track, what is buffered plays out, then playback
  pauses with a message instead of skipping through the queue; Play
  continues from the same second.
- Online sources can be turned off in Settings. While they are on, search
  queries go to archive.org.

#### Likes, history and playlists

- A heart on the player and Like in the track menu.
- Listening history: a play counts after half the track or four minutes, as
  on Last.fm; tracks shorter than 30 seconds are not counted.
- A Playlists tab with Liked, Recent and your own playlists; a playlist screen
  where tracks are reordered by dragging and removed by swiping; Add to
  playlist in the track menu. Tracks can be sorted by Most played.
- Import of M3U, M3U8 and PLS playlists, export to M3U8.
- A copy of your likes, playlists and history in a folder you choose (a step
  of the setup wizard, or Settings). After a reinstall, choose the same folder
  and they come back — merged with anything you did before answering.

#### Library

- Artist sort names from tags (TSOP and TSO2 in MP3, ARTISTSORT and
  ALBUMARTISTSORT elsewhere) order artists, and tracks and albums by artist:
  "David Bowie" under B.
- A damaged or lost library database is rebuilt from the journal at launch;
  the app says so once, and the tracks come back with the scan.

#### For developers

- A Rust core in `core/` (SQLite catalog, a CRDT journal on `yrs`, the
  scanner and tag reader, providers), built by Gradle for four ABIs and
  reached through UniFFI; building needs Rust, the NDK and `cargo-ndk`
  (`docs/BUILD.md`).
- A `Provider` interface with a shared contract test suite; Internet Archive
  is its first implementation.
- Test corpora for the database schema and the journal of every release:
  newer versions must read what older ones wrote.

### Changed

- The library is read by the app's own scanner in the core instead of
  Android's media index: tags come from the files themselves.
- The journal of likes, playlists and history — and only it — goes to Android
  backup and device-to-device transfer again. The cloud copy is made only when
  the phone encrypts it (a screen lock is set). The music, the library
  database and the settings stay on the device.
- New permissions: internet (online sources) and network state (to tell a
  metered network from Wi-Fi).
- After updating from 0.1, Android asks for access to music once more: the
  new core reads files by their paths, and granting that resets the earlier
  answer. Your queue and settings stay as they were.
- The permission screen now says the music never leaves the device.
- The APK grows from 2.9 MB to 16.3 MB: the core is built for four ABIs
  (`docs/testing/apk-size.md`).

### Fixed

- After a playback error, Play continues from the same second instead of
  starting the track over.

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

[Unreleased]: https://github.com/Puflik/Plinth/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/Puflik/Plinth/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/Puflik/Plinth/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/Puflik/Plinth/releases/tag/v0.1.0
