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
- ktlint and detekt with a shared `.editorconfig`, CI building both flavors.

[Unreleased]: https://github.com/Puflik/Plinth/commits/main
