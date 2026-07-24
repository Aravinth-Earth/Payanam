# Payanam (பயணம்)

> **Your Progress, Your Privacy** — Local-first life dimension manager for Android + Desktop

**Current Build:** #1478 (Android) / #615 (Desktop)

Payanam is a privacy-first life dimension manager — tasks, habits, time tracking, journal, and insights across the dimensions of life you define. All data stays on your device. No cloud, no accounts, no tracking.

See [VISION.md](docs/VISION.md) for philosophy and roadmap.

---

## Quick Start

```bash
# Build debug APK (requires Java 17 + Android SDK 35)
./gradlew assembleDebug
```

See [INSTALL.md](INSTALL.md) for sideload and verification steps.
See [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) for development setup and PR workflow.
See [CHANGELOG.md](CHANGELOG.md) for what's new.

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
Third-party libraries: AndroidX, Compose, Hilt, Room, Coroutines, [Vico Charts](https://github.com/patrykandpatrick/vico), Timber.

---

## License

[AGPL-3.0](LICENSE) — All modifications must be shared under the same terms.
