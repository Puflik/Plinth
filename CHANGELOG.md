# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Online search also covers YouTube: songs and albums from YouTube Music
  appear in their own section after Internet Archive and play as Opus (about
  160 kbps) or AAC 128 kbps. The player asks for a fresh link when the old one
  runs out or the server refuses it.
- Settings: each online source, Internet Archive and YouTube, has its own
  switch. A source that is switched off gets no requests; its saved tracks
  stay in the library and play again when it is switched back on.
- Settings: an About section with the app version, the YouTube extractor
  version and a link to the source code, and a Licenses screen with the
  third-party components and their licences (also in the NOTICE file).

### Fixed

- A journal file damaged in the middle no longer drops everything after the
  damage quietly: Plinth keeps what comes before it and sets the damaged file
  aside.
- A group of changes that cannot be done in full, such as adding to a
  playlist that is gone, leaves no half-done trace.
- Importing a huge playlist no longer bloats the library journal: 60,000
  lines take about a megabyte instead of 28.
- Online search no longer hangs for up to a minute when the network is
  connected but silent: a search gives up after 15 seconds, and a new query
  cancels the old one at once instead of waiting for it.
- The tracks of an Internet Archive concert play in disc and track order;
  files without a number follow in natural order, 2 before 10.
- A like or "Add to playlist" from the player no longer vanishes when a scan
  or a restore has just moved the playing song to a new id: the heart follows
  the song, and the change lands on the song in your library.
- On an almost full phone your changes keep being saved in small pieces
  instead of every other one failing, and a change counts as saved only once
  it is on disk. The library opens even with no space left to tidy up.
- Changes are no longer lost when the start of the journal's file of recent
  changes is damaged: Plinth reads them back and keeps a copy of the file. A
  single change that cannot be read is skipped instead of stopping the
  library from opening.
- After a power cut in the middle of a huge playlist import, the library
  opens quickly and simply drops the unfinished import. Copies of damaged
  journal files no longer pile up: only the newest few are kept.
- Moving music to the SD card the usual way, copying first and deleting the
  originals later, no longer leaves likes, playlists and listening history on
  a hidden song: the song keeps them and plays from its new place. The same
  goes for a file a scan caught half-copied. A song from another album is
  never taken for the moved one.
- When several songs with the same title move at once, each lands on the file
  that matches it best, and the one you liked or played comes first.
- Tags edited while a restore ran during a rescan are no longer stuck: the
  scan saves them to the restored song.
- When a like or a playlist change cannot be saved, most likely because the
  phone is full, Plinth says so every time, not just once per launch, and at
  most once in ten seconds.
- A damaged playback position no longer throws away the whole saved queue:
  the song simply starts from the beginning.
- A very long playlist name or search no longer stops the queue from being
  saved, and a queue that failed to save no longer gets the position of the
  next one.
- Songs in a queue saved by Plinth 0.1 now show their likes and history.
- Two installations that restored each other's backups now both see the
  likes, playlists and history of both and settle on the same song, whichever
  of them scanned first.
- When two copies of a song match equally well, your likes go to the copy
  that can play rather than to a missing duplicate, and a song is never
  matched with one from another album.
- A song that was merged into another no longer comes back later to take a
  duplicate's place, so its plays are no longer counted twice.
- Restoring a backup hands each song's data to its best match at once, so a
  single no longer takes the likes of the album track.
- After Plinth has rebuilt your likes and playlists from the library, restoring
  from the backup folder no longer brings back the playlists, ratings and likes
  you removed since, and the old backup is no longer offered as news. It still
  helps the library recognize songs on a card that is not inserted.
- Rebuilding likes and playlists from the library now takes a second even with
  tens of thousands of plays, instead of minutes.
- The notice that your likes and playlists were restored, or that they need
  restoring from your backup, now appears even when Plinth opens its data on a
  second try, and turning the screen no longer makes it disappear for good.
  Until the data comes back, Settings → Data backup reminds you to choose the
  folder with your copy.
- A copy in the backup folder that Plinth can't read (written by a newer
  version, damaged or too large) is now reported instead of looking like an
  empty folder.
- Copies of damaged journal files no longer count against Android's 25 MB
  backup quota, a fresh copy is no longer deleted when the clock goes back, and
  a copy cut short by a full disk leaves nothing behind.
- Writing the copy to the backup folder no longer deletes the previous copy
  before the new one is fully written.
- Copies of the library database set aside after damage or a failed upgrade no
  longer pile up on the phone: Plinth keeps the two newest damaged copies and the
  newest failed upgrade, and removes the rest.
- Upgrading the library database no longer fails for lack of space because of its
  own safety copy: when the disk is full, Plinth drops that copy and finishes the
  upgrade.
- Online search no longer says "No network" when the server itself refused or
  was busy: a server that asks to wait says so, and Plinth waits as long as it
  asked before trying it again. Every online section with a problem now has a
  Retry button, and coming back from an album no longer runs the search again.
- Searching or playing online right after Plinth starts no longer fails before
  the online sources are switched on.

## [0.2.3] - 2026-10-15

A small usability release: liking a song and adding it to a playlist no
longer hide behind a long press.

### Added

- Every song in a list has a ⋮ button with the same menu as a long press:
  play next, add to queue, replace queue, add to playlist, like.
- The player's ⋮ menu can like the song that is playing and add it to a
  playlist; the song you opened the choice for is the one that is added,
  even if the next one has started meanwhile.

## [0.2.2] - 2026-10-12

A reliability release after the review of the second wave. Your likes,
ratings, playlists and history now survive moving or renaming songs, editing
their tags on a computer, restoring an older backup and a memory card that is
still being checked after a restart; Plinth opens faster on a big library and
no longer stays shut when its data cannot be updated or read.

### Fixed

- A library update that fails no longer keeps Plinth from opening. If the
  database cannot be brought to the new version, it is set aside whole next
  to a fresh one: likes, ratings, playlists and history come back from the
  journal, the library from a scan. A database found damaged while it is
  copied before an update is set aside the same way, and a copy that cannot
  be written (no space left) no longer stops the update. Only a lack of
  space or a locked file during the update itself still stops the launch,
  with your data untouched, until the next try.
- An update takes one copy of the database instead of one per schema step,
  so the three copies kept are those of the last three updates.
- Every twentieth launch no longer waits for a full check of the library
  database before the first screen: on a big library the check took most of
  the start-up time. It now runs in the background once Plinth is open, and
  damage it finds is repaired on the next launch — likes and playlists come
  back from the journal, tracks with the scan.
- A damaged journal file no longer locks you out of Plinth. If the file that
  keeps your likes, playlists and history cannot be read, it is set aside and
  started again from what the library database shows, so you keep what you
  saw. If the database is gone as well, Plinth says so and points you to your
  backup folder: the backup made before the damage is never overwritten.
- When Plinth cannot open its data at all — the phone is out of space or the
  data was written by a newer version — the app no longer stays on a blank
  screen: the library explains what happened and offers Retry and Save log.
- A song you move to another folder, rename (or whose folder you rename), or
  that comes back on a card with a new volume name keeps its likes, rating,
  playlists and history. The scan recognises the file in its new place by its
  title, artist, album and length and keeps it as the same track, so it is
  not added again and does not show up among recently added songs.
- Tags edited outside Plinth (on a computer, in a tag editor) no longer cost
  a song its likes, rating, playlists and history after a reinstall. Plinth
  recognises your songs after a reinstall by their title, artist, album and
  length; it used to remember them as they were the last time you liked or
  played them, and now every scan brings that up to date.
- Restoring from the backup folder no longer brings back an older rating,
  playlist name, track order in a playlist or setting over a newer one. When
  the same thing was changed on two installations, the later change wins; it
  used to be a coin toss.
- Right after the phone starts or a memory card is inserted, the first scan no
  longer hides your whole SD card library while the system is still checking
  the card: its songs stay in your lists, playlists and Liked, and play once
  the card is ready. A song the scan could not read for a moment no longer
  drops out of your lists either. A card you really took out is still shown
  as gone, and its songs come back when you put it back.

## [0.2.1] - 2026-10-04

A fix release after the review of the second wave: nothing you saved is lost
when a reinstall is restored, the Albums tab opens quickly on a big library,
and a backup folder that stopped working is no longer silent.

### Added

- On TECNO, Infinix and itel phones, when the phone is online but search
  or an album says there is no network, Plinth explains that the firmware
  may be blocking it and opens Phone Master's network settings.
- "Why music stops" knows TECNO, Infinix and itel phones: it asks to turn on
  autostart for Plinth and opens Phone Master for it. Without autostart the
  firmware freezes Plinth after a long pause, and the first press of a
  headset button is lost.

### Fixed

- Restoring after a reinstall keeps the data of both installations. If you
  liked, rated or played a track before the new scan finished, its old
  rating, plays and playlist places used to be left behind with an id that
  no track had; now they are merged onto the track (your newer rating wins).
- The Albums tab no longer takes minutes on a big library: it used to read
  the whole library once per album (81 s for 10 000 tracks).
- Searching with no network no longer switches Internet Archive off for five
  minutes. A rejected request does not count against the source, and with no
  connection it is paused for 15 seconds only.
- An error while a track is restored (no network at launch) no longer
  resets the saved position to the start.
- AIFF files of Internet Archive are no longer picked as the best variant:
  the player cannot read them, and the track used to be skipped with an MP3
  right next to it.
- "Previous" on a lone track added by hand restarts it instead of emptying
  the queue while the sound keeps playing.
- Exporting a playlist to M3U says how many online tracks the file left out.
- A backup folder that cannot be written is no longer silent. Settings show
  the time of the last copy, or in red that the copy is not being written;
  a folder picked in the system dialog is tried before it is remembered.
- Typing fast in the search field no longer loses letters or moves the
  cursor away from the end of the text.
- Without access to music the library keeps all its tabs: Liked, Recent and
  playlists with Internet Archive tracks are there and play, and the file
  tabs ask for access in a card above their lists. If access was turned off
  later, the library stays in view; its files play again once access is
  back. An empty library shows where to look for music inside the file tabs,
  so playlists stay in reach.
- On TECNO, Infinix and itel phones, swiping Plinth away from recent apps
  while music plays no longer brings up "Why music stops" on the next
  start: you stopped it, not the firmware.

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

[Unreleased]: https://github.com/Puflik/Plinth/compare/v0.2.3...HEAD
[0.2.3]: https://github.com/Puflik/Plinth/compare/v0.2.2...v0.2.3
[0.2.2]: https://github.com/Puflik/Plinth/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/Puflik/Plinth/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/Puflik/Plinth/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/Puflik/Plinth/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/Puflik/Plinth/releases/tag/v0.1.0
