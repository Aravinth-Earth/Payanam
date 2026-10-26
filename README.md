# Payanam (பயணம்)

Last Updated: 2026-10-27

> **Your Progress, Your Privacy** — Local-first life dimension manager for Android + Desktop

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL--3.0-purple?style=flat-square&labelColor=1a1a1a)](LICENSE)
[![FOSS](https://img.shields.io/badge/FOSS-Yes-brightgreen?style=flat-square&labelColor=1a1a1a)](LICENSE)
![Android 9+](https://img.shields.io/badge/Android-9%2B-green?style=flat-square&labelColor=1a1a1a)
[![Release](https://img.shields.io/github/v/release/Aravinth-Earth/Payanam?label=Release&style=flat-square&labelColor=1a1a1a)](https://github.com/Aravinth-Earth/Payanam/releases)

Payanam is a privacy-first life dimension manager — tasks, habits, time tracking, journal, and insights across the dimensions of life you define. All data stays on your device. No cloud, no accounts, no tracking, no telemetry — and no network call ever carries your data. The only network traffic the app can generate is an optional update check that contacts GitHub and sends nothing but the app's version code, and, on Android only and only when you accept an update, the download of a GitHub release asset; build metadata is not user data, and nothing about you or your usage is ever sent.

**F-Droid build.** A build packaged for F-Droid makes no update call: the store client owns updates for those installs, so the app's own updater is disabled and it never downloads or installs an APK for itself.

See [VISION.md](docs/VISION.md) for philosophy and roadmap.

---

## Screenshots

**English (EN)**

| | |
|---|---|
| ![Habits list](docs/images/screenshots/en/1_habits_list.jpg) | ![Tasks list](docs/images/screenshots/en/2_tasks_list.jpg) |
| ![Time timeline](docs/images/screenshots/en/3_time_timeline.jpg) | ![Dimension insights](docs/images/screenshots/en/4_dimension_insights.jpg) |
| ![Lenses radar](docs/images/screenshots/en/5_lenses_radar.jpg) | ![Settings](docs/images/screenshots/en/6_settings.jpg) |

**Tamil (தமிழ்)**

| | |
|---|---|
| ![Habits list](docs/images/screenshots/ta/1_habits_list.jpg) | ![Tasks list](docs/images/screenshots/ta/2_tasks_list.jpg) |
| ![Time timeline](docs/images/screenshots/ta/3_time_timeline.jpg) | ![Dimension insights](docs/images/screenshots/ta/4_dimension_insights.jpg) |
| ![Lenses radar](docs/images/screenshots/ta/5_lenses_radar.jpg) | ![Settings](docs/images/screenshots/ta/6_settings.jpg) |

---

## Quick Start

```bash
# Build debug APK (requires Java 17 + Android SDK 35)
./gradlew assembleDebug
```

See [INSTALL.md](INSTALL.md) for sideload and verification steps.
See [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) for development setup and PR workflow.
## What's new

This project does not maintain a changelog file; see the commit history and releases.

---

## Features

| Status | Feature |
|--------|---------|
| ✅ | Tasks — Full CRUD with priority scoring (9-factor weighted formula), templates, recurring, tags, reminders |
| ✅ | Habits — 8 life dimensions, binary + numeric types, score carry-forward |
| ✅ | Time Tracking — Per-dimension sessions with timeline view |
| ✅ | Insights — Charts, dimension scoring dashboard, daily stats |
| ✅ | Journal — Daily notes with dimension tagging |
| ✅ | Tamil (தமிழ்) — Full string parity in `values-ta/` |
| ✅ | Desktop foundation — Compose Desktop with Windows EXE/MSI |
| 🔄 | Export / Import — Auto-backup + manual export/import with passphrase |
| ⏭️ | Cross-device sync (planned) |

---

## Privacy & network

Payanam is offline by design. It has no servers, no accounts, no analytics, and no telemetry: no data about you or your usage ever leaves your device, and the app is fully functional offline.

**Local session logs.** Both the Android and desktop builds keep a session log on your device recording app errors, navigation and diagnostics — that log is what makes a bug report actionable, and it also records that a passphrase reset was attempted. It never leaves your device, it rotates automatically (the desktop keeps the 20 most recent sessions, Android keeps 9), and credentials, file paths and note/journal content are redacted before they are written. On desktop, debug-level entries follow the build: a debug build logs them from start-up and a release build does not, the in-app Debug logging switch changes that at runtime, and launching with the environment variable overrides the build's default for that session (`export PAYANAM_DEBUG_LOGGING=true` on Linux/macOS; `$env:PAYANAM_DEBUG_LOGGING = "true"` in Windows PowerShell).

**Fail-closed security state.** If the desktop app cannot read its stored passphrase configuration, it refuses to start rather than continuing, because it cannot tell a fresh install apart from a damaged one. The recovery it offers clears only the passphrase and its lockout state — your tasks, journal, notes and settings are not touched.

---

## Tech Stack

| Layer | Choice |
|-------|--------|
| **Language** | Kotlin 2.0 |
| **UI** | Jetpack Compose + Material3 |
| **Database** | Room (SQLite, local-only) |
| **DI** | Hilt (Dagger) |
| **Architecture** | Multi-module, MVVM + Repository |
| **Desktop** | Compose Multiplatform |
| **Min SDK** | 28 (Android 9) |
| **Target SDK** | 35 |

---

## Project Structure

```
payanam/
├── app/                    # Android app (UI, ViewModels, DI)
├── desktop/                # Compose Desktop foundation
├── core/
│   ├── shared/             # Cross-platform contracts (Android + Desktop)
│   ├── domain/             # Domain models, repository interfaces
│   ├── database/           # Room entities, DAOs, migrations, repositories
│   ├── scoring/            # Task priority scoring engine
│   └── common/             # Shared utilities, logging, sanitizer
├── build-tools/            # Build automation scripts (PowerShell)
├── config/                 # Detekt, Spotless quality config
├── gradle/                 # Gradle wrapper + version catalog
└── docs/                   # Vision, contributing guide, DB architecture
```

---

## Build

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (minified)
./gradlew assembleRelease

# Unit tests + coverage
./gradlew test coverageCheck

# Static analysis
./gradlew preCommitCheck
```

Windows users can also use `.\build-tools\scripts\build-android.ps1` for the full pipeline (preflight checks, device install, smoke tests).

---

## Credits

Inspiration from [uHabits](https://github.com/iSoron/uhabits), [SATT](https://github.com/Razeeman/Android-SimpleTimeTracker), and [Google Stitch](https://stitch.google.com).  
Third-party libraries: AndroidX, Kotlin & kotlinx (Coroutines, Serialization), Jetpack Compose + Material3, Hilt (Dagger), Room, [SQLCipher](https://www.zetetic.net/sqlcipher/) (database encryption), [Vico Charts](https://github.com/patrykandpatrick/vico), Timber, WorkManager.  
Quality tooling: [detekt](https://detekt.dev), [Spotless](https://github.com/diffplug/spotless) + [ktlint](https://ktlint.github.io), JUnit, [Robolectric](https://robolectric.org), [Mockito](https://site.mockito.org), [Truth](https://truth.dev), Espresso.

---

## License

[AGPL-3.0](LICENSE) — All modifications must be shared under the same terms.
