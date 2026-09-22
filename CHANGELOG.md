# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Project scaffolding: Gradle build with a version catalog, `minSdk 26`,
  `compileSdk 37`, Compose and Hilt.
- Two product flavors, `github` and `fdroid`, differing in whether the app
  checks for updates by itself and where API keys come from.
- Application skeleton: single activity, Material 3 theme following the system
  light/dark setting and dynamic colour, bottom navigation with the
  Library · Search · Settings tabs.
- Audio engine abstraction: the `AudioEngine` interface with typed playback
  state, events and errors, kept free of any Android type so a desktop client
  and a future Rust engine can reuse it.
- A contract test suite every engine implementation must pass, an in-memory
  fake engine for tests, and a guard test that fails on the first Android
  import crossing the abstraction boundary.
- `docs/ARCHITECTURE.md`: package map, layer boundaries and the playback data
  flow.
- ktlint and detekt with a shared `.editorconfig`, CI building both flavors.

[Unreleased]: https://github.com/Puflik/Plinth/commits/main
