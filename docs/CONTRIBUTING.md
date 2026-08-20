# Contributing to Payanam

Payanam is a local-first, FOSS (AGPL-3.0) project. Contributions are welcome — this
guide keeps the codebase consistent and the quality gates green for everyone.

## Development setup

- **Java 17** (toolchain enforced in Gradle)
- **Android SDK 35** (for the `app` module)
- Clone, then build once to warm Gradle:

```bash
./gradlew assembleDebug
```

## Branching

- Branch off `dev` (the active development line), not `main`.
- Use descriptive branch names: `feature/...`, `fix/...`, `chore/...`.
- One logical change per PR. Small, focused PRs review faster.

## Before opening a PR

Run the pre-commit gate locally. It runs Spotless (formatting) + detekt
(static analysis) across all modules:

```bash
./gradlew preCommitCheck
```

Fix what it reports. The CI pipeline runs the same checks on every PR, so a
green local run is the fastest path to merge.

## Documentation standard (KDoc)

Public API surface must be documented:

- Every `public`/`override` **function** needs a KDoc block (`/** ... */`).
- Every public **property** and **class** needs a KDoc block.

This is enforced by detekt (`UndocumentedPublicFunction`,
`UndocumentedPublicProperty`, `UndocumentedPublicClass`). New undocumented
public code fails the build.

Existing code that predates this rule is whitelisted in
`config/detekt/baseline.xml` so the build stays green. **Do not hand-edit the
baseline** — if you legitimately remove a violation, regenerate it:

```bash
./gradlew detektBaseline
```

## Code style

- Kotlin formatting is enforced by Spotless (ktlint). Run `./gradlew spotlessApply`
  to auto-format.
- Keep numeric literals in scoring/config logic as named constants where the
  value's meaning isn't obvious (detekt `MagicNumber` rule).
- Every source file must carry the AGPL SPDX header (Spotless enforces this).

## Tests

- Add or update unit tests for behavior changes.
- Run the suite: `./gradlew test coverageCheck`

## License

By contributing, you agree your contributions are licensed under AGPL-3.0-or-later,
matching the project.
