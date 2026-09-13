# Payanam E2E Test Suite — Session Goal
# Last Updated: 2026-09-09

## END GOAL
Cover EVERY tappable, interactive, configurable, toggleable, navigable element in the app
via Maestro UI test automation framework. No mocking. No internal state checks.
Real user interactions only. Fastest possible execution with zero step repetition.

## CONSTRAINTS
1. Single Maestro execution cycle — fresh setup ONCE, all tests share session
2. ZERO coordinate taps — only text/selector-based taps
3. When element lacks a selector → STOP → add a11y label → build-android.ps1 → resume
4. Every action happens ONCE — no repeated creates, no redundant asserts
5. Sequential navigation — left-to-right through bottom nav, no backtracking

## COMPLETE INTERACTIVE ELEMENT MAP

### Phase 0: First-Time Setup (runs ONCE)
| # | Screen | Interactive Elements | Count |
|---|--------|---------------------|-------|
| 0.1 | Onboarding | Next, Skip Tour, Get Started | 3 |
| 0.2 | Database Init | Create New Empty DB, Import DB, I'll choose later | 3 |
| 0.3 | Passphrase Setup | Passphrase input, Confirm input, Show/Hide toggle, Set passphrase button, Reset local data, Back | 6 |
| 0.4 | Dimension Setup | Edit per dimension (×9), Disable/Enable per dimension (×9), Add New, Use Defaults, Verify and proceed | 21 |
| 0.5 | Focus Mode | Simple: Tasks, Simple: Time+Habits, Simple: Journal, I'll choose later | 4 |
| **Subtotal** | | | **37** |

### Phase 1: Tasks Module
| # | Screen | Interactive Elements | Count |
|---|--------|---------------------|-------|
| 1.1 | Tasks List | Tab filters (All/Active/Inactive/Overdue/Today/Future), FAB (Add Task), Search, Sort, Task card tap, Habits strip | 7 |
| 1.2 | Add Task | Title input, Task Type (One Time/Recurring), Due Date picker, Due Time picker, Reminder (Off/Custom/Auto), Description input, Dimension selector, Duration input, Estimated Duration, Save Task, Cancel | 11 |
| 1.3 | Task Detail | Back, Edit, Delete, Complete, Reschedule, Skip, Score link | 7 |
| 1.4 | Edit Task | Same as Add Task (pre-filled) | 11 |
| 1.5 | Recurring Options | Frequency (One Time/Every N days/Specific days/Weekly/Monthly), Day picker | 5 |
| **Subtotal** | | | **41** |

### Phase 2: Habits Module
| # | Screen | Interactive Elements | Count |
|---|--------|---------------------|-------|
| 2.1 | Habits List | Habit cards, Complete checkbox, Skip button, Calendar view, Sort, Search, FAB | 7 |
| 2.2 | Habit Detail | Calendar, Streak info, Completion history, Score | 4 |
| **Subtotal** | | | **11** |

### Phase 3: Time Module
| # | Screen | Interactive Elements | Count |
|---|--------|---------------------|-------|
| 3.1 | Time Screen | Start Tracking, Stop Tracking, Scale selector (1m-2h), Day plan, Time entries list, Entry tap | 6 |
| 3.2 | Start Tracking Dialog | Task selector, Dimension selector, Start button, Cancel | 4 |
| 3.3 | Stop Tracking Dialog | Notes input, Stop button, Cancel | 3 |
| 3.4 | Time Entry Edit | Duration, Notes, Dimension, Delete, Save | 5 |
| 3.5 | Day Plan Template | Dimension time allocations, Save template, Delete template | 3 |
| **Subtotal** | | | **21** |

### Phase 4: Journal Module
| # | Screen | Interactive Elements | Count |
|---|--------|---------------------|-------|
| 4.1 | Journal Screen | Date navigation (prev/next), Add entry, Entry tap, Journal notes | 4 |
| 4.2 | Entry Editor | Text input, Save, Cancel | 3 |
| **Subtotal** | | | **7** |

### Phase 5: Notes Module
| # | Screen | Interactive Elements | Count |
|---|--------|---------------------|-------|
| 5.1 | Notes List | FAB (Add Note), Search, Note card tap, Edit, Delete | 5 |
| 5.2 | Note Editor | Text input, Save, Cancel | 3 |
| **Subtotal** | | | **8** |

### Phase 6: Lenses Module
| # | Screen | Interactive Elements | Count |
|---|--------|---------------------|-------|
| 6.1 | Lenses Main | Dimension tabs (PH/MH/FR/HE/WL/MF/LG/RL/CS), Per Dimension view, Time insights card, Habit insights card, Task insights card, Focus insights card | 11 |
| 6.2 | Dimension Detail | Score breakdown, Time section, Habits section, Tasks section, Journal section, Chart interactions | 6 |
| 6.3 | Time Insights | Window selector (30D/90D/180D/365D/All), Chart types, Dimension trend, Heatmap, Weekly pattern, Daily rhythm | 8 |
| 6.4 | Day Detail | Day summary, Time entries, Completed habits, Tasks due | 4 |
| **Subtotal** | | | **29** |

### Phase 7: Settings Module
| # | Screen | Interactive Elements | Count |
|---|--------|---------------------|-------|
| 7.1 | Appearance | Theme Mode (System/Light/Dark), Font Family (Sans/Serif/Mono/Cursive), Time Format (12h/24h), App Language (System/EN/TA) | 4 |
| 7.2 | Default Landing | Landing screen selector | 1 |
| 7.3 | Tab Visibility | Show/Hide per tab, Focus mode preset | 8 |
| 7.4 | Life Dimensions | Edit dimension (name/color/icon × N), Add dimension, Delete dimension | 5 |
| 7.5 | Auto-Track Habit Time | Global toggle, Per-dimension toggles | 3 |
| 7.6 | Auto-Backup | Enable toggle, Interval selector, Run Backup Now, Rotation enable, Rotation count | 5 |
| 7.7 | Scoring Config | Dimension weight sliders, Reset defaults | 3 |
| 7.8 | Security | Unlock timeout selector, Biometric toggle | 2 |
| 7.9 | Database | Size display, Schema info, Export, Import, Stale file cleanup, Log export (session + all) | 6 |
| 7.10 | Passphrase Change | Current passphrase, New passphrase, Confirm, Save | 4 |
| 7.11 | About | Version, Codename, Tagline, GitHub link | 4 |
| 7.12 | Debug | Enable debug logging toggle | 1 |
| 7.13 | Delete All Data | Delete button, Confirmation dialog | 2 |
| **Subtotal** | | | **48** |

### Phase 8: Cross-Cutting
| # | Scenario | Interactive Elements | Count |
|---|----------|---------------------|-------|
| 8.1 | Unlock Flow | Passphrase input, Unlock, Forgot passphrase, Reset | 4 |
| 8.2 | Export → Import → Verify | Export, Delete all, Fresh setup, Import, Verify data | 5 |
| 8.3 | Insights Verification | Scroll all charts, Verify data populates | 3 |
| **Subtotal** | | | **12** |

## GRAND TOTAL: ~214 interactive elements across 20+ screens

## EXECUTION ORDER (zero backtracking)
```
0. FRESH SETUP (once)
1. TASKS → create tasks across dimensions → complete → search
2. HABITS → create habits → complete → calendar → sort
3. TIME → track → stop → multiple entries → scales
4. JOURNAL → add entry → edit → navigate dates
5. NOTES → add → edit → search → delete
6. LENSES → verify all data → dimension detail → insights
7. SETTINGS → every toggle/option verified
8. PASSPHRASE → change → verify
9. SCORING → adjust → verify
10. EXPORT/IMPORT → round-trip verification
11. UNLOCK → kill → relaunch → verify persistence
```

## BLOCKERS
- **Phone lock screen:** Device has PIN lock that Maestro cannot bypass. Must be disabled before running full suite. Settings → Security → Screen lock → None.
- **Note dialog Save:** May require Title field before Save is enabled. Currently handled by inputting title before save.

## A11Y GAPS
### Fixed
1. ✅ Bottom nav tabs: added visible text labels (label = { Text(tabLabel) })
2. ✅ FAB buttons: added visible text ("Add Task", "Add Note")
3. ✅ Task Detail action buttons: already have text ("Complete", "Skip", "Miss", "Reschedule")

### Remaining
4. Habit day-by-day checkmark panel (CheckmarkPanelCanvas): Canvas-based, but these are status indicators, not action buttons — lower priority
5. Various icon-only toolbar buttons: need verification

## BUILD PIPELINE
When a11y gap found:
1. Add visible text / a11y label to Kotlin source
2. `pwsh build-tools/scripts/build-android.ps1`
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
4. Resume test from that point
