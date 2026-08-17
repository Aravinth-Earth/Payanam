# APK Size Reduction Research — Experiment Tracker

**Branch:** `research/apk-size-reduction`
**Started:** 2026-08-17
**Baseline APK:** 77 MB (debug, arm64-v8a, no R8)
**Release APK:** 45 MB (R8 on, all 4 ABIs)
**Goal:** Find realistic minimum for Payanam with its current dependency set

---

## Baseline Metrics

| Metric | Value |
|--------|-------|
| Debug APK size | 77 MB |
| Release APK size | 45 MB |
| DEX files (debug) | 27 files, ~77 MB |
| DEX files (release) | 2 files, ~31 MB |
| Native libs (debug, arm64) | 3.5 MB (SQLCipher) |
| Native libs (release, 4 ABIs) | 12.8 MB (SQLCipher) |
| resources.arsc | 1.4 MB |
| Build time (quick) | ~6 min |
| Build time (full) | ~15-20 min est |

---

## Research Categories

1. **R8 & ProGuard** — Enable/optimize minification in debug
2. **Native Library Audit** — SQLCipher alternatives, stripping, ABI filtering
3. **Dependency Tree** — Transitive deps, unused libs, compileOnly candidates
4. **Kotlin/Compiler Research** — AGP 9.x, Kotlin 2.3, new flags
5. **Compose & UI Layer** — Icons subset, compiler metrics, chart lib alternatives
6. **Build Configuration** — Dex layout, resource shrinking, Gradle flags
7. **Code Structure** — Proguard rule scope, module architecture impact

---

## Experiments

### EXP-001: Baseline Measurement
- **Hypothesis:** Establish exact current state for comparison
- **Category:** Baseline
- **Change:** None — measure existing debug APK
- **Build command:** `./gradlew assembleDebug --no-daemon`
- **Result:** 77 MB, 27 dex files, 3.5 MB native
- **Verdict:** ✅ baseline established
- **Learnings:** 90% of APK is un-minified DEX. R8 is the single biggest lever.
- **Time:** 6 min

---

*New experiments appended below as they are run.*
