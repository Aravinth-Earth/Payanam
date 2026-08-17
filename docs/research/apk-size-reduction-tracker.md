# APK Size Reduction Research — Experiment Tracker

**Branch:** `research/apk-size-reduction`
**Started:** 2026-08-17
**Baseline APK:** 76.93 MB (debug, arm64-v8a, no R8)
**Current APK:** 24.5 MB (debug, arm64-v8a, R8 + tightened rules)
**Total Reduction:** -52.43 MB (68.2%)

---

## Cumulative Results

| Exp | Change | Before | After | Delta | Cumulative |
|-----|--------|--------|-------|-------|------------|
| 001 | Baseline | — | 76.93 MB | — | 76.93 MB |
| 002 | R8 in debug | 76.93 | 67.77 | -9.16 MB | 67.77 MB |
| 003 | + Resource shrinking | 67.77 | 67.68 | -0.09 MB | 67.68 MB |
| 004 | + R8 full mode | 67.68 | 67.68 | 0 MB | 67.68 MB |
| 005 | Tighten kotlin/coroutines rules | 67.68 | 60.73 | -6.95 MB | 60.73 MB |
| 006 | Strip material-icons-extended | 60.73 | 31.37 | -29.36 MB | 31.37 MB |
| 007 | Strip foundation/animation/material3 | 31.37 | 26.67 | -4.70 MB | 26.67 MB |
| 008 | Strip Compose UI keep | 26.67 | 25.15 | -1.52 MB | 25.15 MB |
| 009 | Zero Compose keeps | 25.15 | 24.50 | -0.65 MB | 24.50 MB |

---

## Key Learnings

### What Worked (highest impact first)
1. **Remove `-keep class androidx.compose.material.** { *; }`** — saved 29.36 MB. The material-icons-extended library generates 46K icon classes; R8 strips unused ones when not force-kept.
2. **R8 in debug builds** — saved 9.16 MB + builds are faster (R8 strips before DEXing).
3. **Narrow kotlin.** / kotlinx.coroutines.** rules** — saved 6.95 MB.
4. **Strip foundation/animation/material3** — saved 4.70 MB by removing broad keep rules.

### What Didn't Work
- **R8 full mode with broad rules** — zero impact. ProGuard rules were the bottleneck.
- **Resource shrinking** — negligible (app has minimal resources).
- **material-icons-extended removal is the single biggest finding** — the library generates 900+ icon classes, and `-keep class material.** { *; }` kept all of them.

### Build Timing
| Config | Time |
|--------|------|
| Baseline (no R8) | 14m54s |
| With R8 | 10m55s |
| Tightened rules | 9m50s |
| Zero Compose keeps | 2m29s |

---

## Current APK Breakdown (24.5 MB)

| Component | Size | % |
|-----------|------|---|
| DEX (classes.dex) | 14.3 MB | 58% |
| DEX (classes2.dex) | 5.9 MB | 24% |
| SQLCipher native | 3.5 MB | 14% |
| Resources | 1.6 MB | 6% |

## Remaining Seeds (what R8 keeps)

| Package | Seeds | Notes |
|---------|-------|-------|
| com.patrykandpatrick (Vico) | 3,910 | Charts — could narrow |
| androidx.biometric | 2,286 | Used for biometric auth |
| io.payanam | 2,205 | Our app code — correct |
| net.sqlcipher | 1,061 | Native JNI bridge |
| kotlin.jvm | 946 | Kotlin internals |
| androidx.appcompat | 504 | Transitive dep |
| dagger.hilt | 502 | DI framework |
| androidx.compose | 468 | Minimal — good |
| androidx.work | 360 | WorkManager |

## Next Experiments (planned)

- **EXP-010**: Narrow Vico keep rules
- **EXP-011**: Check if biometric keep can be narrowed
- **EXP-012**: Try removing kotlinx.serialization keep
- **EXP-013**: Try removing Hilt broad keep rules
