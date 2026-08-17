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

### EXP-002: Enable R8 Minification in Debug
- **Hypothesis:** R8 will strip unused code from debug APK
- **Category:** R8 & ProGuard
- **Change:** Build with `-SizeOptimized` flag (passes `-PdebugMinify=true`)
- **Build command:** `build-android.ps1 -Profile full -SizeOptimized`
- **Result:** 76.93 MB → 67.77 MB (**-9.16 MB, 11.9% reduction**)
- **Build time:** 10m55s (faster than baseline 14m54s — R8 strips before DEX)
- **Verdict:** ✅ keep
- **Learnings:** R8 works but ProGuard rules are too broad (R8 warns about Object methods). Resource shrinking not enabled for debug. Next: try R8 full mode + resource shrinking.
- **Time:** 11 min

---

*New experiments appended below as they are run.*

---

## Research Notes (Category 4: Kotlin/Compiler/Build System)

### AGP 9.1+ Changes (already active for Payanam)
- **R8 auto-repackageclasses**: AGP 9.1+ adds `-repackageclasses` by default → classes repackaged to unnamed package → smaller DEX. Already active.
- **Optimized resource shrinking**: AGP 9.0+ has optimized pipeline by default. No need for `android.r8.optimizedResourceShrinking=true`.

### AGP 9.3 Changes
- **R8 Configuration Analyzer**: Dedicated Gradle task to analyze and optimize shrinking/obfuscation rules. Could help identify suboptimal proguard rules.

### Compose APK Size Insights
- **material-icons-extended**: Generates ImageVector constants for ALL icons → huge code bloat. Switching to individual icon imports saves显著 space.
- **Compose compiler metrics**: Can identify unstable classes and optimization opportunities via `compose.compiler.metrics`.
- **Compose stability config file**: Can mark types as stable to improve compiler optimizations.

### Kotlin Compiler Flags
- No major new size-specific flags in Kotlin 2.3 that would significantly reduce DEX size.
- `-Xjvm-default=all` affects interface method generation but not significant for size.

### Key Takeaway
The biggest lever remains R8 minification in debug builds. Secondary levers: material-icons-extended subset, native lib ABI filtering, proguard rule optimization.
