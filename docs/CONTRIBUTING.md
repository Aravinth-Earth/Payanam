# Contributing to Payanam
Last Updated: 2026-07-21

Thanks for your interest in Payanam! This guide explains how to contribute bug reports, features, documentation, and improvements.

**Before you start:** Please read [VISION.md](VISION.md) to understand what Payanam is and isn't, and which contributions align with our direction.

## Code of Conduct

- **Be respectful** — We're building a community where privacy, autonomy, and thoughtful design matter
- **Assume good intent** — Most misunderstandings are just that
- **Respect the vision** — Contributions should strengthen Payanam's core purpose, not derail it
- **Value privacy** — Never propose features that compromise user data sovereignty

## How to Contribute

### 1. Reporting Bugs

Found a crash, missing feature, or unexpected behavior?

**Before reporting:**
- Check [Issues](https://github.com/Aravinth-Earth/Payanam/issues) to see if it's already reported
- Try to reproduce it on the latest code
- Note your Android version, device type, and exact steps to reproduce

**Report by opening an Issue:**
- Title: Clear, specific description (e.g., "Time dimension UI shows blank on Android 13")
- Body:
  - **Steps to reproduce** (ordered list)
  - **Expected behavior**
  - **Actual behavior**
  - **Device info** (Android version, device type, build number if available)
  - **Logs** (if applicable; see [Log Analysis Workflow](#log-analysis-workflow) below)
  - **Screenshots** (if UI-related)

### 2. Suggesting Features or Improvements

Have an idea for a new life dimension, improvement to time tracking, or UX enhancement?

**Before proposing:**
- Check [Issues/Discussions](https://github.com/Aravinth-Earth/Payanam/discussions) for similar ideas
- Consider: Does this fit Payanam's vision? (See [VISION.md](VISION.md))
- Ask yourself: *Is this user-driven or feature-bloat?*

**Propose by opening a Discussion:**
- **Category:** Feature Request
- **Title:** Clear summary (e.g., "Add 'people connection' tracking for relationships")
- **Body:**
  - **What problem does this solve?** (user need, not implementation)
  - **How does this fit Payanam's vision?** (explain alignment)
  - **Rough idea** (no code needed, just concept)
  - **Any concerns or tradeoffs?**

Maintainers will provide feedback before you invest in a pull request.

### 3. Submitting Code Changes

Once a bug or feature is approved (via Issue/Discussion), here's how to contribute code.

#### Prerequisites

- **Development environment:**
  - Java 17+
  - Android SDK 35
  - Kotlin 2.0+
  - A connected Android device or emulator (for testing)

- **Build & test locally:**
  ```powershell
  # Run full build with unit tests
  .\build-tools\scripts\build-android.ps1
  
  # Or use quick commands (if available)
  . .\build-commands.ps1
  ba  # Build & install
  ```

- **Repository rules** (mandatory, enforced by build script):
  - Use `UnifiedLogger` in every Kotlin source file
  - Add SPDX license headers to new source files
  - No hardcoded UI strings — use `values/strings.xml` (and keep `values-ta/strings.xml` in sync)
  - Respect module file-size and import limits (checked by preflight)
  - POC/research artifacts go under `poc/`

#### Making Your Changes

1. **Create a branch:**
   ```bash
   git checkout -b feature/short-description
   # or for bugs:
   git checkout -b fix/short-description
   ```

2. **Code with the project style:**
   - Follow Kotlin idioms (val over var, immutable data classes, extension functions)
   - Use MVVM + Repository pattern (see existing modules for examples)
   - Keep functions small and testable (~30-50 lines ideal)
   - Add unit tests for business logic (database, scoring, view models)

3. **Update related docs:**
   - If adding a feature, update [README.md](README.md) (Features table, Build Commands if needed)
   - If changing database schema, update database docs
   - If changing public APIs, update inline code docs

4. **Run the full build:**
   ```powershell
   .\build-tools\scripts\build-android.ps1
   ```
   - Enforces unit tests, coverage checks, static analysis
   - Checks Kotlin file lengths, import counts, etc.
   - **Must pass before submitting PR**

5. **Manual testing:**
   - Test on your target Android version
   - Verify app data isn't lost (database migrations)
   - Check UI layout on various screen sizes if UI-related
   - For widgets/integration features, test end-to-end (UI → ViewModel → Repository → Database)

#### Submitting a Pull Request

1. **Push to your fork:**
   ```bash
   git push origin feature/short-description
   ```

2. **Open a PR on GitHub:**
   - Use the PR template (auto-populated if `.github/pull_request_template.md` exists)
   - **Title:** Clear, concise (e.g., "Add life dimension cards to home screen")
   - **Description:**
     - Link the related Issue/Discussion
     - Explain *what* changed and *why*
     - If visual changes: include a screenshot or screen recording
     - Call out any breaking changes or new dependencies
     - Reference any build script changes or new test files

3. **Respond to reviews:**
   - Address all feedback (even if you respectfully disagree, explain your reasoning)
   - Push follow-up commits; don't force-push (helps reviewers track changes)
   - Once approved, maintainer will merge

### 4. Documentation & Localization

- **Docs** (README, CONTRIBUTING, guides): Open a PR directly
- **String resources** (UI text): 
  - EN: `app/src/main/res/values/strings.xml`
  - TA: `app/src/main/res/values-ta/strings.xml` (keep in sync with EN)
  - Always localize user-facing copy, never hardcode in Kotlin
- **Markdown docs:** Keep `Last Updated: YYYY-MM-DD` at the top

### 5. Becoming a Maintainer

If you've contributed consistently and understand the vision, we'd love to add you as a maintainer. Get in touch in a GitHub Discussion.

---

## Development Workflow

### Project Structure

```
payanam/
  app/                    # Android app module
  core/                   # Shared libraries
    common/               # Logging, utilities
    database/             # Room DAOs, entities, migrations
    domain/               # Business logic (scoring, time calc)
    scoring/              # Task-scoring algorithm
  docs/                   # Documentation
  poc/                    # Proof-of-concept & experimental work
  build-tools/            # Build scripts, configuration
```

See [ProjectStructure.md](ProjectStructure.md) for detailed module descriptions.

### Build System

- **Gradle 8.x** with Kotlin DSL
- **Version management:** `gradle/libs.versions.toml`
- **Build script:** `build-tools/scripts/build-android.ps1` (PowerShell on Windows)
  - Runs unit tests, coverage, static analysis
  - Enforces Kotlin file sizes and code limits
  - Generates APK and installs if device is available

### Testing

**Unit tests:**
```bash
./gradlew test
```

**Integration/instrumentation tests:**
```bash
./gradlew connectedDebugAndroidTest  # Requires connected device
```

**Smoke tests** (after successful install):
- Automated UI validation runs from `output/smoke/` if a device is detected
- Captures logs and app state for debugging

### Common Workflows

**After a successful build, update CHANGELOG.md:**
- Add a new entry at the top with the current date
- Describe the change briefly (user-facing perspective)
- Do NOT alter old entries

Example:
```markdown
## Build #1478 (Android) / #615 (Desktop) — 2026-07-22
### Added
- Life dimension cards on home screen
- Time tracking UI (work in progress)

### Fixed
- App crash on Android 13+ when loading large habit histories
```

**Before submitting a PR:**
1. Run `.\build-tools\scripts\build-android.ps1` — must pass
2. Test manually on a real device
3. Update CHANGELOG.md if behavior changes
4. Update the corresponding GitHub Issue(s) so completed work is closed or moved to the right workflow state.

---

## Log Analysis Workflow

If you need to debug app behavior:

1. **Export logs from the app:**
   - Settings > Data Management > Export Logs
   - This saves logs to `/sdcard/Documents/Payanam/exported-logs/`

2. **Pull logs locally:**
   ```powershell
   adb pull /sdcard/Documents/Payanam/exported-logs/<filename> output/exported-logs/build_<NUM>/<filename>
   ```

3. **Analyze locally** (not via pipe/grep)
   - Use your editor or IDE for full-file context search
   - Look for `[ERROR]`, `[WARN]` tags and surrounding logs

4. **Share relevant excerpts** in your Issue/PR (redact personal data if needed)

---

## Questions?

- **About contributing?** Open a [Discussion](https://github.com/Aravinth-Earth/Payanam/discussions)
- **About the vision?** Read [VISION.md](VISION.md) or ask in Discussions
- **Found a bug?** Open an [Issue](https://github.com/Aravinth-Earth/Payanam/issues)

Thanks for helping Payanam grow! 🌱
