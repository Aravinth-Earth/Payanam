# APK Size Reduction Research — Experiment Tracker

**Branch:** `research/apk-size-reduction`
**Started:** 2026-08-17 20:30 IST
**Baseline APK:** 76.93 MB (debug, arm64-v8a, no R8)
**Current APK:** 23.97 MB (debug, arm64-v8a, R8 + tightened rules)
**Total Reduction:** -52.96 MB (68.8%)

---

## Cumulative Results

| Exp | Change | Before | After | Delta | Cumulative |
|-----|--------|--------|-------|-------|------------|
| 001 | Baseline | — | 76.93 MB | — | 76.93 MB |
| 002 | R8 in debug | 76.93 | 67.77 | -9.16 MB | 67.77 MB |
| 003 | + Resource shrinking | 67.77 | 67.68 | -0.09 MB | 67.68 MB |
| 004 | + R8 full mode | 67.68 | 67.68 | 0 MB | 67.68 MB |
| 005 | Tighten kotlin/coroutines | 67.68 | 60.73 | -6.95 MB | 60.73 MB |
| 006 | Strip material-icons-ext | 60.73 | 31.37 | -29.36 MB | 31.37 MB |
| 007 | Strip foundation/anim/mat3 | 31.37 | 26.67 | -4.70 MB | 26.67 MB |
| 008 | Strip Compose UI keep | 26.67 | 25.15 | -1.52 MB | 25.15 MB |
| 009 | Zero Compose keeps | 25.15 | 24.50 | -0.65 MB | 24.50 MB |
| 010 | Vico zero keep | 24.50 | 24.22 | -0.28 MB | 24.22 MB |
| 011 | Biometric narrowed | 24.22 | 24.05 | -0.17 MB | 24.05 MB |
| 012 | Kotlin JVM narrowed | 24.05 | 23.97 | -0.08 MB | 23.97 MB |

---

## Key Findings

### Single Biggest Finding
**`-keep class androidx.compose.material.** { *; }` was responsible for 29.36 MB of bloat.** The material-icons-extended library generates 900+ icon classes (46K seeds), and the blanket keep rule prevented R8 from stripping unused ones.

### Pattern: Every `-keep class X.** { *; }` rule is a size tax
- `kotlin.**` → 17K seeds → narrowed to ~1K
- `androidx.compose.**` → 138K seeds → 0 explicit keeps
- `com.patrykandpatrick.vico.**` → 3.9K → 0 keeps
- `androidx.biometric.**` → 2.3K → 6 specific classes
- `kotlinx.coroutines.**` → 7.5K → 7 specific classes

### R8 is Better Than You Think
R8's code analysis correctly tracks which classes are actually referenced. Blanket keep rules are almost always over-broad. The optimal strategy: **zero explicit keeps for most libraries**, let R8 track references.

### Build Timing Improves with Smaller APK
| Config | Build Time |
|--------|-----------|
| No R8 (baseline) | 14m54s |
| R8 broad rules | 10m55s |
| R8 tightened rules | 9m50s |
| R8 zero Compose | 2m29s |

---

## Final APK Breakdown (23.97 MB)

| Component | Size | % |
|-----------|------|---|
| DEX (classes.dex) | ~14 MB | 58% |
| DEX (classes2.dex) | ~6 MB | 25% |
| SQLCipher native | 3.5 MB | 15% |
| Resources | 1.6 MB | 7% |

## Remaining Seeds (what R8 keeps)

| Package | Seeds | Notes |
|---------|-------|-------|
| io.payanam | 2,205 | Our app — correct |
| net.sqlcipher | 1,061 | Native JNI bridge |
| dagger.hilt | 502 | DI framework |
| androidx.compose | 468 | Minimal — good |
| androidx.work | 360 | WorkManager |
| androidx.appcompat | 504 | Transitive dep |
| dagger.internal | 265 | Hilt internals |
| androidx.core | 201 | Core AndroidX |

---

## What Didn't Work
- **R8 full mode with broad rules** — zero impact
- **Resource shrinking** — app has minimal resources
- **Narrowing Compose to specific packages** — less impact than zero-keeps

## Recommendations for Production
1. Keep the tightened ProGuard rules from EXP-012
2. Test on device — build succeeds but runtime verification needed
3. Consider: removing kotlinx.serialization if desktop-only, narrowing Hilt rules
4. The 23.97 MB is a **68.8% reduction** from baseline
