# Payanam E2E Test Scenarios v2
#
# STRUCTURE:
#   1. Reusable subflows (shared steps, called by many scenarios)
#   2. Independent test scenarios (each = one user journey, separate test)
#   3. Execution order (dependencies)
#
# PRINCIPLE: Each scenario = ONE testable user journey.
#   - Starts from known state (fresh setup, or unlocked)
#   - Tests ONE specific behaviour
#   - Ends at a stable state for the next scenario
#   - Minimal repetition — club common steps into shared subflows
#
# PASSPHRASE: 'E2ETestPass!2026' (used across all scenarios)


# ═══════════════════════════════════════════════════════════════
# PART 1: REUSABLE SUBFLOWS (shared steps)
# ═══════════════════════════════════════════════════════════════

## SUBFLOW: fresh_setup
##   Onboarding → Database → Passphrase → Dimensions → Focus → Lenses
##   Used by: most scenarios that need a fresh app state
##   Steps:
##     1. Dismiss compat dialog if present ("Don't show again")
##     2. Assert "Your Privacy First" → tap "Skip Tour"
##     3. Assert "Welcome To Payanam" → tap "Create New Empty Database"
##     4. Assert "Secure your database" → enter passphrase → confirm → save
##     5. Assert "Set up your life dimensions" → "Verify and proceed"
##     6. Assert "Choose Your Focus" → "I'll choose later"
##     7. Assert "Per Dimension" (Lenses screen)

## SUBFLOW: unlock
##   Stop app → relaunch → enter passphrase → unlock
##   Used by: scenarios that need to verify persistence after app restart
##   Steps:
##     1. Stop app (NOT clearState)
##     2. Relaunch
##     3. Dismiss compat dialog if present
##     4. Assert "Unlock your database"
##     5. Enter passphrase → tap "Unlock"
##     6. Assert main screen (Lenses)

## SUBFLOW: navigate_to_tab
##   Tap bottom nav tab by a11y label
##   Parameter: tab name (Tasks/Habits/Time/Journal/Notes/Lenses/Settings)

## SUBFLOW: add_task
##   Navigate to Tasks → tap FAB → enter title → optional dimension/due → save
##   Parameters: title, dimension (optional), due date (optional)

## SUBFLOW: add_habit
##   Navigate to Habits → tap FAB → enter title → select frequency → save
##   Parameters: title, frequency type

## SUBFLOW: add_note
##   Navigate to Notes → tap Add → enter text → save
##   Parameters: note text

## SUBFLOW: add_journal_entry
##   Navigate to Journal → enter text → save
##   Parameters: journal text

## SUBFLOW: add_time_entry
##   Navigate to Time → Start Tracking → (optional: select task/dimension) → Stop Tracking
##   Parameters: duration (wait seconds), optional task


# ═══════════════════════════════════════════════════════════════
# PART 2: INDEPENDENT TEST SCENARIOS
# ═══════════════════════════════════════════════════════════════

# ─────────────────────────────────────────────────────────────
# SCENARIO GROUP A: FIRST-TIME SETUP
# Each scenario tests ONE specific aspect of initial setup
# ─────────────────────────────────────────────────────────────

### A1: Onboarding — Full Tour (Next through all slides)
- **Start:** Fresh app
- **Steps:**
  1. Dismiss compat dialog
  2. Assert "Your Privacy First" → tap "Next"
  3. Assert "Track with Purpose" → tap "Next"
  4. Assert "Dimensions of Life" → tap "Get Started"
  5. Assert "Welcome To Payanam"
- **Verify:** All 3 slides shown, navigation works
- **End state:** Database Init screen

### A2: Onboarding — Skip Tour
- **Start:** Fresh app
- **Steps:**
  1. Dismiss compat dialog
  2. Assert "Your Privacy First" → tap "Skip Tour"
  3. Assert "Welcome To Payanam"
- **Verify:** Skip works, lands on Database Init
- **End state:** Database Init screen

### A3: Database — Create New Empty
- **Start:** After onboarding (A1 or A2)
- **Steps:**
  1. Assert "Welcome To Payanam"
  2. Tap "Create New Empty Database"
  3. Assert "Secure your database"
- **Verify:** Transitions to passphrase setup
- **End state:** Passphrase Setup screen

### A4: Passphrase — Validation Rules
- **Start:** Passphrase Setup screen
- **Steps:**
  1. Enter short passphrase (< 12 chars) → assert error "Use at least 12 characters"
  2. Enter passphrase without uppercase → assert error "Add at least one uppercase letter"
  3. Enter passphrase without lowercase → assert error "Add at least one lowercase letter"
  4. Enter passphrase without digit → assert error "Add at least one number"
  5. Enter passphrase without symbol → assert error "Add at least one symbol"
  6. Enter mismatched passphrases → assert error "Passphrase and confirmation do not match"
- **Verify:** All validation rules enforced
- **End state:** Passphrase Setup screen (still open)

### A5: Passphrase — Successful Setup
- **Start:** Passphrase Setup screen
- **Steps:**
  1. Enter valid passphrase (E2ETestPass!2026) in both fields
  2. Tap "Set passphrase and continue"
  3. Wait for DB creation
  4. Assert "Set up your life dimensions"
- **Verify:** Passphrase accepted, transitions to dimension setup
- **End state:** Dimension Setup screen

### A6: Passphrase — Show/Hide Toggle
- **Start:** Passphrase Setup screen
- **Steps:**
  1. Enter passphrase text
  2. Tap "Show passphrase" → assert text is visible (not masked)
  3. Tap "Hide passphrase" → assert text is masked
- **Verify:** Toggle works correctly
- **End state:** Passphrase Setup screen

### A7: Passphrase — Reset Local Data
- **Start:** Passphrase Setup screen
- **Steps:**
  1. Tap "Reset local data and continue"
  2. Assert confirmation dialog
  3. Confirm reset
  4. Assert back to Database Init ("Welcome To Payanam")
- **Verify:** Reset works, returns to fresh state
- **End state:** Database Init screen

### A8: Dimension Setup — Verify Defaults Present
- **Start:** Dimension Setup screen (after A5)
- **Steps:**
  1. Assert "Set up your life dimensions"
  2. Assert all 9 default dimensions visible:
     - Physical Health, Mental Health, Family & Relationships
     - Home & Environment, Work & Livelihood, Money & Finance
     - Learning & Growth, Recreation & Leisure, Community & Service
  3. Assert all are enabled by default
- **Verify:** All defaults loaded correctly
- **End state:** Dimension Setup screen

### A9: Dimension Setup — Use Defaults
- **Start:** Dimension Setup screen
- **Steps:**
  1. Tap "Use Defaults"
  2. Assert "Verify and proceed" is active
  3. Tap "Verify and proceed"
  4. Assert "Choose Your Focus"
- **Verify:** Defaults accepted, transitions to focus mode
- **End state:** Focus Mode Selection screen

### A10: Dimension Setup — Customize (Edit Name)
- **Start:** Dimension Setup screen
- **Steps:**
  1. Tap Edit on "Physical Health"
  2. Assert "Edit life dimension" dialog
  3. Clear name, type "Fitness & Exercise"
  4. Save
  5. Assert "Fitness & Exercise" replaces "Physical Health"
- **Verify:** Name change persisted
- **End state:** Dimension Setup screen

### A11: Dimension Setup — Customize (Change Color)
- **Start:** Dimension Setup screen
- **Steps:**
  1. Tap Edit on a dimension
  2. Select a different color from the color picker
  3. Save
  4. Assert dimension shows new color
- **Verify:** Color change persisted
- **End state:** Dimension Setup screen

### A12: Dimension Setup — Customize (Change Icon)
- **Start:** Dimension Setup screen
- **Steps:**
  1. Tap Edit on a dimension
  2. Select a different icon
  3. Save
  4. Assert dimension shows new icon
- **Verify:** Icon change persisted
- **End state:** Dimension Setup screen

### A13: Dimension Setup — Add New Dimension
- **Start:** Dimension Setup screen
- **Steps:**
  1. Tap "Add New"
  2. Assert "Add life dimension" dialog
  3. Enter name "Custom Test Dimension"
  4. Select unique color
  5. Select unique icon
  6. Save
  7. Assert "Custom Test Dimension" appears in list
- **Verify:** New dimension added
- **End state:** Dimension Setup screen

### A14: Dimension Setup — Disable/Enable Dimension
- **Start:** Dimension Setup screen
- **Steps:**
  1. Toggle OFF "Community & Service"
  2. Assert it shows as disabled/hidden
  3. Toggle ON again
  4. Assert it shows as enabled
- **Verify:** Toggle persists
- **End state:** Dimension Setup screen

### A15: Dimension Setup — Validation (Duplicate Name)
- **Start:** Dimension Setup screen
- **Steps:**
  1. Edit a dimension, change name to match another existing dimension
  2. Try to save
  3. Assert error "Each active dimension needs a unique name"
- **Verify:** Duplicate name blocked
- **End state:** Dimension Setup screen

### A16: Dimension Setup — Validation (Duplicate Color)
- **Start:** Dimension Setup screen
- **Steps:**
  1. Edit a dimension, change color to match another
  2. Try to save
  3. Assert error "Each active dimension needs a unique color"
- **Verify:** Duplicate color blocked
- **End state:** Dimension Setup screen

### A17: Dimension Setup — Verify & Proceed
- **Start:** Dimension Setup screen (after any customizations)
- **Steps:**
  1. Tap "Verify and proceed"
  2. Wait for save
  3. Assert "Choose Your Focus"
- **Verify:** Transitions to focus mode
- **End state:** Focus Mode Selection screen

### A18: Focus Mode — Select Preset
- **Start:** Focus Mode Selection screen
- **Steps:**
  1. Assert "Choose Your Focus" visible
  2. Tap "Simple: Time + Habits"
  3. Wait for animation
  4. Assert Lenses screen (Per Dimension)
- **Verify:** Preset selected, correct tabs shown
- **End state:** Lenses (main screen)

### A19: Focus Mode — Skip (Choose Later)
- **Start:** Focus Mode Selection screen
- **Steps:**
  1. Assert "Choose Your Focus" visible
  2. Tap "I'll choose later"
  3. Assert Lenses screen
- **Verify:** Skip works, all tabs visible
- **End state:** Lenses (main screen)

### A20: Focus Mode — Verify Tab Visibility After Preset
- **Start:** After A18 (Simple: Time + Habits preset)
- **Steps:**
  1. Check bottom nav: Time and Habits should be visible
  2. Check other tabs: Tasks, Journal, Notes should be hidden (per preset)
  3. Settings should always be visible
- **Verify:** Correct tabs shown per preset
- **End state:** Lenses (main screen)


# ─────────────────────────────────────────────────────────────
# SCENARIO GROUP B: TASKS MODULE
# Multiple CRUD ops with different data combinations
# ─────────────────────────────────────────────────────────────

### B1: Tasks — Empty State
- **Start:** Fresh setup, Tasks tab
- **Steps:**
  1. Navigate to Tasks
  2. Assert "No Tasks Yet" or "Tap Add Create Task"
- **Verify:** Clean empty state
- **End state:** Tasks tab (empty)

### B2: Tasks — Create One-Time Task (Minimal Data)
- **Start:** Tasks tab (empty)
- **Steps:**
  1. Tap FAB
  2. Enter title "Quick Task"
  3. Save
  4. Assert "Quick Task" in list
- **Verify:** Task created with just title
- **End state:** Tasks tab (1 item)

### B3: Tasks — Create One-Time Task (All Fields)
- **Start:** Tasks tab
- **Steps:**
  1. Tap FAB
  2. Enter title "Full Detail Task"
  3. Select dimension "Physical Health"
  4. Set due date to tomorrow
  5. Enter note "Important task with all fields"
  6. Save
  7. Assert "Full Detail Task" in list
- **Verify:** All fields saved correctly
- **End state:** Tasks tab (2+ items)

### B4: Tasks — Create Multiple Tasks (Different Dimensions)
- **Start:** Tasks tab
- **Steps:**
  1. Create task "Work Task" in dimension "Work & Livelihood"
  2. Create task "Health Task" in dimension "Physical Health"
  3. Create task "Learning Task" in dimension "Learning & Growth"
  4. Create task "Finance Task" in dimension "Money & Finance"
  5. Assert all 4 tasks visible in list
- **Verify:** Tasks across dimensions all visible
- **End state:** Tasks tab (5+ items)

### B5: Tasks — Create Recurring Task (Habit Type)
- **Start:** Tasks tab
- **Steps:**
  1. Tap FAB
  2. Enter title "Daily Standup"
  3. Select "Every N days" frequency
  4. Set to daily
  5. Save
  6. Assert "Daily Standup" in list
- **Verify:** Recurring task created
- **End state:** Tasks tab (6+ items)

### B6: Tasks — View Task Detail
- **Start:** Tasks tab (has items)
- **Steps:**
  1. Tap on "Full Detail Task"
  2. Assert "Task Details" screen
  3. Assert title "Full Detail Task" visible
  4. Assert dimension "Physical Health" visible
  5. Assert due date visible
  6. Assert note visible
- **Verify:** All fields displayed in detail
- **End state:** Task Detail screen

### B7: Tasks — Edit Task (Change Title)
- **Start:** Task Detail for "Full Detail Task"
- **Steps:**
  1. Tap Edit
  2. Change title to "Full Detail Task (Edited)"
  3. Save
  4. Assert updated title in detail
  5. Go back to list
  6. Assert "Full Detail Task (Edited)" in list
- **Verify:** Edit persisted
- **End state:** Tasks tab

### B8: Tasks — Edit Task (Change Dimension)
- **Start:** Task Detail
- **Steps:**
  1. Tap Edit
  2. Change dimension from "Physical Health" to "Mental Health"
  3. Save
  4. Assert new dimension in detail
- **Verify:** Dimension change persisted
- **End state:** Task Detail screen

### B9: Tasks — Mark Task Done
- **Start:** Task Detail for active task
- **Steps:**
  1. Assert task is active (not completed)
  2. Tap "Mark Done"
  3. Assert task status changed to completed
  4. Go back to list
  5. Assert task no longer in active list (or in completed section)
- **Verify:** Completion persisted
- **End state:** Tasks tab

### B10: Tasks — Reschedule Task
- **Start:** Task Detail for overdue task
- **Steps:**
  1. Assert overdue indicator visible
  2. Tap "Reschedule Task"
  3. Select new date
  4. Save
  5. Assert new due date in detail
- **Verify:** Reschedule persisted
- **End state:** Task Detail screen

### B11: Tasks — Skip Task
- **Start:** Task Detail
- **Steps:**
  1. Tap "Skip Task"
  2. Confirm if dialog
  3. Assert task marked as skipped
- **Verify:** Skip persisted
- **End state:** Task Detail screen

### B12: Tasks — Search Tasks
- **Start:** Tasks tab (5+ items)
- **Steps:**
  1. Tap search icon / field
  2. Type "Work"
  3. Assert "Work Task" visible
  4. Assert other tasks filtered out
  5. Clear search
  6. Assert all tasks visible again
- **Verify:** Search filters correctly
- **End state:** Tasks tab (unfiltered)

### B13: Tasks — Sort Tasks
- **Start:** Tasks tab (5+ items)
- **Steps:**
  1. Tap "Sort tasks"
  2. Select "Title A To Z"
  3. Assert tasks sorted alphabetically
  4. Select "Due Date Earliest"
  5. Assert tasks sorted by due date
- **Verify:** Sort options work
- **End state:** Tasks tab (sorted)

### B14: Tasks — Empty State After Delete
- **Start:** Tasks tab (1 item: "Quick Task")
- **Steps:**
  1. Open "Quick Task" detail
  2. Delete task
  3. Go back to list
  4. Assert "No Tasks Yet" or "Tap Add Create Task"
- **Verify:** Empty state returns after all items deleted
- **End state:** Tasks tab (empty)


# ─────────────────────────────────────────────────────────────
# SCENARIO GROUP C: HABITS MODULE
# Multiple habit types, completions, calendar
# ─────────────────────────────────────────────────────────────

### C1: Habits — Empty State
- **Start:** Fresh setup, Habits tab
- **Steps:**
  1. Navigate to Habits
  2. Assert "No Habits Yet"
- **Verify:** Clean empty state
- **End state:** Habits tab (empty)

### C2: Habits — Create Daily Habit
- **Start:** Habits tab (empty)
- **Steps:**
  1. Tap FAB
  2. Enter title "Morning Meditation"
  3. Select dimension "Mental Health"
  4. Select frequency "Every N days" → set daily
  5. Save
  6. Assert "Morning Meditation" in list
- **Verify:** Daily habit created
- **End state:** Habits tab (1 item)

### C3: Habits — Create Weekly Habit
- **Start:** Habits tab
- **Steps:**
  1. Tap FAB
  2. Enter title "Weekly Review"
  3. Select frequency "Every N days" → set 7 days
  4. Save
  5. Assert "Weekly Review" in list
- **Verify:** Weekly habit created
- **End state:** Habits tab (2 items)

### C4: Habits — Create One-Time Habit
- **Start:** Habits tab
- **Steps:**
  1. Tap FAB
  2. Enter title "One Off Task"
  3. Select "One Time"
  4. Save
  5. Assert "One Off Task" in list
- **Verify:** One-time habit created
- **End state:** Habits tab (3 items)

### C5: Habits — Complete Habit
- **Start:** Habits tab (has daily habit)
- **Steps:**
  1. Tap checkmark/complete on "Morning Meditation"
  2. Assert habit marked complete (visual change)
- **Verify:** Completion registered
- **End state:** Habits tab

### C6: Habits — Skip Habit
- **Start:** Habits tab (has habit not yet completed today)
- **Steps:**
  1. Tap skip on a habit
  2. Assert habit marked as skipped
- **Verify:** Skip registered
- **End state:** Habits tab

### C7: Habits — View Habit Calendar
- **Start:** Habits tab (habit with some history)
- **Steps:**
  1. Tap on "Morning Meditation" to open detail/calendar
  2. Assert calendar visible with completion markers
  3. Assert green = completed, yellow = skipped, red = missed, gray = no record
- **Verify:** Calendar shows correct history
- **End state:** Habit detail/calendar screen

### C8: Habits — Sort by Score
- **Start:** Habits tab (3+ habits)
- **Steps:**
  1. Tap "Sort habits"
  2. Select "Today (high to low)"
  3. Assert habits ordered by today's score
- **Verify:** Sort works
- **End state:** Habits tab (sorted)

### C9: Habits — Sort by Streak
- **Start:** Habits tab
- **Steps:**
  1. Tap "Sort habits"
  2. Select "Streak (high to low)"
  3. Assert habits ordered by streak
- **Verify:** Sort works
- **End state:** Habits tab (sorted)

### C10: Habits — Search Habits
- **Start:** Habits tab (3+ items)
- **Steps:**
  1. Tap search
  2. Type "Meditation"
  3. Assert "Morning Meditation" visible
  4. Assert others filtered
- **Verify:** Search filters correctly
- **End state:** Habits tab

### C11: Habits — Edit Habit (Change Dimension)
- **Start:** Habit detail
- **Steps:**
  1. Edit "Morning Meditation"
  2. Change dimension to "Physical Health"
  3. Save
  4. Assert dimension updated
- **Verify:** Edit persisted
- **End state:** Habit detail

### C12: Habits — Delete Habit
- **Start:** Habits tab (has "One Off Task")
- **Steps:**
  1. Open "One Off Task" detail
  2. Delete
  3. Assert "One Off Task" removed from list
- **Verify:** Delete persisted
- **End state:** Habits tab (2 items)


# ─────────────────────────────────────────────────────────────
# SCENARIO GROUP D: TIME MODULE
# Tracking, day plan, multiple entries
# ─────────────────────────────────────────────────────────────

### D1: Time — Empty State
- **Start:** Fresh setup, Time tab
- **Steps:**
  1. Navigate to Time
  2. Assert "No Time Tracked Yet"
- **Verify:** Clean empty state
- **End state:** Time tab (empty)

### D2: Time — Start & Stop Tracking (Quick)
- **Start:** Time tab
- **Steps:**
  1. Tap "Start Tracking"
  2. Wait 3 seconds
  3. Assert "Stop Tracking" visible
  4. Tap "Stop Tracking"
  5. Assert time entry appears in timeline
- **Verify:** Time entry created
- **End state:** Time tab (1 entry)

### D3: Time — Start Tracking With Dimension
- **Start:** Time tab
- **Steps:**
  1. Tap "Start Tracking"
  2. Select dimension "Work & Livelihood"
  3. Wait 2 seconds
  4. Stop tracking
  5. Assert entry shows "Work & Livelihood" dimension
- **Verify:** Dimension association saved
- **End state:** Time tab (2 entries)

### D4: Time — Multiple Time Entries
- **Start:** Time tab
- **Steps:**
  1. Create time entry for "Physical Health" (5s)
  2. Create time entry for "Learning & Growth" (5s)
  3. Create time entry for "Mental Health" (5s)
  4. Assert all 3 entries visible in timeline
- **Verify:** Multiple entries displayed
- **End state:** Time tab (5+ entries)

### D5: Time — Time Scales
- **Start:** Time tab (has entries)
- **Steps:**
  1. Switch to "1h" scale
  2. Assert timeline adjusts
  3. Switch to "30m" scale
  4. Assert timeline adjusts
  5. Switch to "10m" scale
  6. Assert timeline adjusts
- **Verify:** Scale switching works
- **End state:** Time tab

### D6: Time — Edit Day Plan Template
- **Start:** Time tab
- **Steps:**
  1. Tap "Edit Template"
  2. Set planned time for a dimension
  3. Save
  4. Assert template saved
- **Verify:** Day plan persisted
- **End state:** Time tab


# ─────────────────────────────────────────────────────────────
# SCENARIO GROUP E: JOURNAL MODULE
# ─────────────────────────────────────────────────────────────

### E1: Journal — Empty State
- **Start:** Fresh setup, Journal tab
- **Steps:**
  1. Navigate to Journal
  2. Assert journal screen (empty for today)
- **Verify:** Clean journal view
- **End state:** Journal tab (empty)

### E2: Journal — Add Entry
- **Start:** Journal tab
- **Steps:**
  1. Tap add entry area
  2. Enter text "Today was productive"
  3. Save
  4. Assert entry visible for today
- **Verify:** Entry created
- **End state:** Journal tab (1 entry)

### E3: Journal — Edit Entry
- **Start:** Journal tab (has entry)
- **Steps:**
  1. Tap on existing entry
  2. Edit text to "Today was very productive"
  3. Save
  4. Assert updated text visible
- **Verify:** Edit persisted
- **End state:** Journal tab

### E4: Journal — Navigate Dates
- **Start:** Journal tab
- **Steps:**
  1. Tap "Next day"
  2. Assert date changed
  3. Tap back (previous day)
  4. Assert original date and entry visible
- **Verify:** Date navigation works
- **End state:** Journal tab (original date)

### E5: Journal — Add Journal Notes
- **Start:** Journal tab (has entry)
- **Steps:**
  1. Add note to journal entry
  2. Save
  3. Assert note visible with entry
- **Verify:** Notes attached to journal
- **End state:** Journal tab


# ─────────────────────────────────────────────────────────────
# SCENARIO GROUP F: NOTES MODULE
# ─────────────────────────────────────────────────────────────

### F1: Notes — Empty State
- **Start:** Fresh setup, Notes tab
- **Steps:**
  1. Navigate to Notes
  2. Assert "No Notes Yet" or "Tap Add First Note"
- **Verify:** Clean empty state
- **End state:** Notes tab (empty)

### F2: Notes — Add Note
- **Start:** Notes tab (empty)
- **Steps:**
  1. Tap Add Note / FAB
  2. Enter text "Meeting notes from standup"
  3. Save
  4. Assert note visible in list
- **Verify:** Note created
- **End state:** Notes tab (1 note)

### F3: Notes — Add Multiple Notes
- **Start:** Notes tab
- **Steps:**
  1. Add note "Work notes"
  2. Add note "Personal reflection"
  3. Add note "Ideas for project"
  4. Assert all 3 notes visible
- **Verify:** Multiple notes displayed
- **End state:** Notes tab (4 notes)

### F4: Notes — Edit Note
- **Start:** Notes tab (has notes)
- **Steps:**
  1. Tap on "Meeting notes from standup"
  2. Edit text to "Meeting notes from standup - updated"
  3. Save
  4. Assert updated text visible
- **Verify:** Edit persisted
- **End state:** Notes tab

### F5: Notes — Search Notes
- **Start:** Notes tab (4+ notes)
- **Steps:**
  1. Tap search field "Search notes..."
  2. Type "Work"
  3. Assert "Work notes" visible
  4. Assert other notes filtered
  5. Clear search
  6. Assert all notes visible
- **Verify:** Search filters correctly
- **End state:** Notes tab (unfiltered)

### F6: Notes — Delete Note
- **Start:** Notes tab (has "Ideas for project")
- **Steps:**
  1. Open "Ideas for project" detail
  2. Delete
  3. Assert removed from list
- **Verify:** Delete persisted
- **End state:** Notes tab (3 notes)


# ─────────────────────────────────────────────────────────────
# SCENARIO GROUP G: LENSES MODULE
# Data verification + insights population
# ─────────────────────────────────────────────────────────────

### G1: Lenses — Main View (Empty Data)
- **Start:** Fresh setup, Lenses tab
- **Steps:**
  1. Navigate to Lenses
  2. Assert "Per Dimension" visible
  3. Assert dimension abbreviations (PH, MH, FR, etc.) visible
  4. Assert all show 0m / 0% (no data yet)
- **Verify:** Lenses renders with empty data
- **End state:** Lenses tab

### G2: Lenses — Dimension Detail (After Tasks)
- **Start:** After B4 (tasks in multiple dimensions)
- **Steps:**
  1. Navigate to Lenses
  2. Tap on "PH" (Physical Health)
  3. Assert dimension detail screen
  4. Assert task count reflects created tasks
- **Verify:** Task data populates in lenses
- **End state:** Dimension Detail screen

### G3: Lenses — Dimension Detail (After Habits)
- **Start:** After C5 (completed habits)
- **Steps:**
  1. Navigate to Lenses
  2. Tap on "MH" (Mental Health)
  3. Assert habit completion data visible
- **Verify:** Habit data populates in lenses
- **End state:** Dimension Detail screen

### G4: Lenses — Time Insights (After Time Entries)
- **Start:** After D4 (multiple time entries)
- **Steps:**
  1. Navigate to Lenses
  2. Scroll to Time Insights section
  3. Assert charts/cards render with data
  4. Assert tracked time values are non-zero
- **Verify:** Time data populates insights
- **End state:** Lenses tab (scrolled)

### G5: Lenses — Habit Insights
- **Start:** After C5 (completed habits)
- **Steps:**
  1. Navigate to Lenses
  2. Scroll to Habit Insights
  3. Assert habit completion stats visible
- **Verify:** Habit data populates insights
- **End state:** Lenses tab

### G6: Lenses — Task Insights
- **Start:** After B9 (completed tasks)
- **Steps:**
  1. Navigate to Lenses
  2. Scroll to Task Insights
  3. Assert task completion stats visible
- **Verify:** Task data populates insights
- **End state:** Lenses tab

### G7: Lenses — Day Detail
- **Start:** After D4 (time entries today)
- **Steps:**
  1. Navigate to Lenses
  2. Tap on today's date/day entry
  3. Assert day detail screen
  4. Assert time entries for today visible
  5. Assert completed habits for today visible
  6. Assert tasks due today visible
- **Verify:** Day detail aggregates all data
- **End state:** Day Detail screen

### G8: Lenses — Score Breakdown
- **Start:** Lenses tab (has data)
- **Steps:**
  1. Tap on a dimension with data
  2. Assert score value displayed
  3. Assert score reasons visible
  4. Assert breakdown by module (time/habits/tasks)
- **Verify:** Score calculation visible
- **End state:** Dimension Detail screen

### G9: Lenses — Charts Render
- **Start:** Lenses tab (has data)
- **Steps:**
  1. Scroll through all chart sections
  2. Assert "Daily Focus" chart visible
  3. Assert "Time by Dimension" chart visible
  4. Assert "Dimension Trend" chart visible
  5. Assert "Daily Timeline" heatmap visible
- **Verify:** All chart types render
- **End state:** Lenses tab (scrolled)


# ─────────────────────────────────────────────────────────────
# SCENARIO GROUP H: SETTINGS MODULE
# Each toggle/customization verified independently
# ─────────────────────────────────────────────────────────────

### H1: Settings — Overview
- **Start:** After fresh setup
- **Steps:**
  1. Navigate to Settings
  2. Assert all sections visible:
     Appearance, Default Landing, Tab Visibility, Life Dimensions,
     Auto-Track Habit Time, Auto-Backup, Backup Rotation,
     Scoring Configuration, Security & Timeout, Database,
     Passphrase, About, Debug, F-Droid
- **Verify:** All sections present
- **End state:** Settings tab

### H2: Settings — Theme → Dark
- **Start:** Settings tab
- **Steps:**
  1. Scroll to "Appearance"
  2. Tap "Theme Mode"
  3. Select "Dark Theme"
  4. Assert UI changes to dark colors
  5. Navigate to another tab → assert dark theme persists
- **Verify:** Theme change applied globally
- **End state:** Any tab (dark theme)

### H3: Settings — Theme → Light
- **Start:** Settings tab (dark theme from H2)
- **Steps:**
  1. Tap "Theme Mode"
  2. Select "Light Theme"
  3. Assert UI changes to light colors
- **Verify:** Theme change applied
- **End state:** Settings tab (light theme)

### H4: Settings — Theme → System
- **Start:** Settings tab
- **Steps:**
  1. Tap "Theme Mode"
  2. Select "System Theme"
  3. Assert UI matches system setting
- **Verify:** System theme followed
- **End state:** Settings tab

### H5: Settings — Font Change
- **Start:** Settings tab
- **Steps:**
  1. Scroll to "Font Family"
  2. Select "Monospace"
  3. Assert font changes across app
  4. Select "Serif" → assert change
  5. Select "Cursive" → assert change
  6. Select "Sans Serif" → revert
- **Verify:** All font options work
- **End state:** Settings tab (Sans Serif)

### H6: Settings — Time Format
- **Start:** Settings tab
- **Steps:**
  1. Scroll to "Time Format"
  2. Select "24-hour clock"
  3. Navigate to Time tab → assert 24h format
  4. Return to Settings
  5. Select "12-hour clock"
  6. Navigate to Time tab → assert 12h format
- **Verify:** Format change applied
- **End state:** Settings tab

### H7: Settings — App Language → Tamil
- **Start:** Settings tab
- **Steps:**
  1. Scroll to "App Language"
  2. Select "தமிழ்"
  3. Assert UI labels change to Tamil
  4. Navigate tabs → assert Tamil labels
- **Verify:** Language change applied
- **End state:** Any tab (Tamil)

### H8: Settings — App Language → English
- **Start:** Any tab (Tamil from H7)
- **Steps:**
  1. Navigate to Settings (in Tamil)
  2. Find language setting
  3. Select "English"
  4. Assert UI labels change to English
- **Verify:** Language change applied
- **End state:** Settings tab (English)

### H9: Settings — Default Landing Screen
- **Start:** Settings tab
- **Steps:**
  1. Scroll to "Default landing screen"
  2. Select "Time screen"
  3. Kill and relaunch app → assert opens to Time
  4. Return to Settings
  5. Select "Tasks" → assert opens to Tasks on next launch
- **Verify:** Landing preference persisted
- **End state:** Settings tab

### H10: Settings — Tab Visibility (Show/Hide)
- **Start:** Settings tab
- **Steps:**
  1. Scroll to tab visibility section
  2. Hide "Journal" tab
  3. Assert Journal no longer in bottom nav
  4. Show "Journal" tab again
  5. Assert Journal reappears in bottom nav
- **Verify:** Tab visibility toggles work
- **End state:** Settings tab

### H11: Settings — Life Dimensions (Edit from Settings)
- **Start:** Settings tab
- **Steps:**
  1. Scroll to "Life Dimensions"
  2. Tap edit on a dimension
  3. Change name
  4. Save
  5. Assert change reflected in dimension list
  6. Navigate to Lenses → assert change reflected there too
- **Verify:** Dimension changes persist across app
- **End state:** Lenses tab

### H12: Settings — Auto-Backup Enable
- **Start:** Settings tab
- **Steps:**
  1. Scroll to "Auto-Backup"
  2. Toggle "Enable Auto-Backup" ON
  3. Select interval "Backup every 15 min"
  4. Tap "Run Backup Now"
  5. Assert backup success message
- **Verify:** Backup created
- **End state:** Settings tab

### H13: Settings — Auto-Backup Disable
- **Start:** Settings tab (backup enabled from H12)
- **Steps:**
  1. Toggle "Enable Auto-Backup" OFF
  2. Assert backup section shows disabled state
- **Verify:** Backup disabled
- **End state:** Settings tab

### H14: Settings — Database Info
- **Start:** Settings tab
- **Steps:**
  1. Scroll to "Database"
  2. Assert "Database Size" shows non-zero value
  3. Assert schema info visible
  4. Assert file details visible
- **Verify:** Database info displayed
- **End state:** Settings tab

### H15: Settings — Export Database
- **Start:** Settings tab
- **Steps:**
  1. Scroll to "Database"
  2. Tap "Export"
  3. Assert export success snackbar
  4. Verify file created on device
- **Verify:** Export works
- **End state:** Settings tab

### H16: Settings — Export Logs
- **Start:** Settings tab
- **Steps:**
  1. Scroll to "Database" / debug section
  2. Tap "Export Current Session Log"
  3. Assert success snackbar
  4. Tap "Export All Logs"
  5. Assert success snackbar
- **Verify:** Log exports work
- **End state:** Settings tab

### H17: Settings — About Section
- **Start:** Settings tab
- **Steps:**
  1. Scroll to "About"
  2. Assert version number visible
  3. Assert codename "Kotlin Compose Migration"
  4. Assert tagline "Your Progress, Your Privacy"
  5. Assert description visible
- **Verify:** About info correct
- **End state:** Settings tab

### H18: Settings — Debug Logging Toggle
- **Start:** Settings tab
- **Steps:**
  1. Scroll to "Debug"
  2. Toggle "Enable Debug Logging" ON
  3. Assert hint changes to "Debug logs are saved to internal storage"
  4. Toggle OFF
  5. Assert hint changes to "Only info/warn/error logs are saved"
- **Verify:** Toggle works
- **End state:** Settings tab

### H19: Settings — Security Timeout
- **Start:** Settings tab
- **Steps:**
  1. Scroll to "Security & Timeout"
  2. Adjust "Unlock Session Timeout"
  3. Select different value
  4. Assert value saved
- **Verify:** Timeout preference persisted
- **End state:** Settings tab

### H20: Settings — Scoring Config (from Settings)
- **Start:** Settings tab
- **Steps:**
  1. Scroll to "Scoring Configuration"
  2. Tap "Configure Scoring Weights"
  3. Assert "Scoring Configuration" screen
  4. Assert dimension weights visible
  5. Adjust a weight
  6. Save
  7. Assert weight updated
- **Verify:** Scoring config editable
- **End state:** Scoring Config screen

### H21: Settings — Delete All Data
- **Start:** Settings tab (data exists)
- **Steps:**
  1. Scroll to "Delete All Data"
  2. Tap it
  3. Assert confirmation dialog: "Delete All Data?"
  4. Confirm deletion
  5. Assert app restarts to fresh state
  6. Assert "Welcome To Payanam" or onboarding
- **Verify:** All data wiped, fresh state
- **End state:** Database Init / Onboarding


# ─────────────────────────────────────────────────────────────
# SCENARIO GROUP I: PASSPHRASE MANAGEMENT
# ─────────────────────────────────────────────────────────────

### I1: Passphrase — Change (Valid)
- **Start:** Settings tab
- **Steps:**
  1. Tap "Update Passphrase"
  2. Assert passphrase change screen
  3. Enter current passphrase
  4. Enter new passphrase (different from current)
  5. Confirm new passphrase
  6. Save
  7. Assert success message
- **Verify:** Passphrase changed
- **End state:** Settings tab

### I2: Passphrase — Change (Wrong Current)
- **Start:** Settings tab
- **Steps:**
  1. Tap "Update Passphrase"
  2. Enter WRONG current passphrase
  3. Enter new passphrase
  4. Try to save
  5. Assert error: wrong passphrase
- **Verify:** Wrong passphrase rejected
- **End state:** Passphrase Change screen

### I3: Passphrase — Verify New Passphrase Works
- **Start:** After I1 (passphrase changed)
- **Steps:**
  1. Kill app
  2. Relaunch
  3. Assert "Unlock your database"
  4. Enter OLD passphrase → try unlock → assert fails
  5. Enter NEW passphrase → unlock → assert succeeds
- **Verify:** New passphrase works, old one rejected
- **End state:** Main screen (unlocked)

### I4: Passphrase — Unlock Error (Wrong Passphrase)
- **Start:** App locked
- **Steps:**
  1. Enter wrong passphrase
  2. Tap "Unlock"
  3. Assert "Wrong passphrase. Please try again."
- **Verify:** Error message shown
- **End state:** Unlock screen

### I5: Passphrase — Forgot Passphrase Reset
- **Start:** Unlock screen
- **Steps:**
  1. Tap "Forgot passphrase? Reset local data"
  2. Assert confirmation dialog
  3. Tap "Reset and continue"
  4. Assert fresh setup (Database Init)
- **Verify:** Reset works
- **End state:** Database Init screen


# ─────────────────────────────────────────────────────────────
# SCENARIO GROUP J: EXPORT → IMPORT → VERIFY
# Critical: data round-trip integrity
# ─────────────────────────────────────────────────────────────

### J1: Export → Import Round-Trip
- **Start:** App with data (tasks, habits, time, notes, journal from B/C/D/E/F)
- **Steps:**
  1. Navigate to Settings → Database → Export
  2. Assert export success
  3. Delete all data (Settings → Delete All Data)
  4. Assert fresh state
  5. Complete fresh setup (passphrase, dimensions, focus)
  6. Navigate to Settings → Database → Import
  7. Select the exported file
  8. Confirm import
  9. Assert import success
  10. Navigate to Tasks → assert original tasks present
  11. Navigate to Habits → assert original habits present
  12. Navigate to Notes → assert original notes present
  13. Navigate to Lenses → assert data populates correctly
- **Verify:** All data survives export/import round-trip
- **End state:** App with restored data

### J2: Import Invalid File
- **Start:** Settings → Database → Import
- **Steps:**
  1. Select a non-database file
  2. Assert error message
- **Verify:** Invalid import rejected
- **End state:** Settings tab

### J3: Import Encrypted DB (Wrong Passphrase)
- **Start:** Settings → Database → Import
- **Steps:**
  1. Select an encrypted database file
  2. Enter wrong passphrase when prompted
  3. Assert error: "Wrong passphrase for the imported database"
- **Verify:** Wrong passphrase rejected
- **End state:** Import passphrase prompt


# ─────────────────────────────────────────────────────────────
# SCENARIO GROUP K: SCORING CONFIGURATION
# ─────────────────────────────────────────────────────────────

### K1: Scoring — View Config
- **Start:** Settings → Configure Scoring Weights
- **Steps:**
  1. Assert "Scoring Configuration" screen
  2. Assert all dimensions listed with weights
  3. Assert default weights present
- **Verify:** Config loaded
- **End state:** Scoring Config screen

### K2: Scoring — Adjust Weight
- **Start:** Scoring Config screen
- **Steps:**
  1. Change "Physical Health" weight to 150
  2. Save
  3. Assert weight updated to 150
  4. Navigate to Lenses → assert score recalculated
- **Verify:** Weight change affects scores
- **End state:** Lenses tab

### K3: Scoring — Reset to Defaults
- **Start:** Scoring Config screen (modified weights)
- **Steps:**
  1. Tap reset/restore defaults
  2. Assert weights return to original values
- **Verify:** Reset works
- **End state:** Scoring Config screen


# ─────────────────────────────────────────────────────────────
# SCENARIO GROUP L: CROSS-CUTTING
# Data persistence, navigation, error handling
# ─────────────────────────────────────────────────────────────

### L1: Persistence — Data Survives App Kill
- **Start:** App with data from B/C/D/E/F
- **Steps:**
  1. Kill app completely
  2. Relaunch
  3. Unlock with passphrase
  4. Navigate to each tab → assert all data persists
- **Verify:** All data survives app restart
- **End state:** Main screen

### L2: Navigation — All 7 Tabs
- **Start:** Main screen
- **Steps:**
  1. Tap each of 7 bottom nav tabs
  2. Assert each tab's content loads
  3. Assert no crashes or blank screens
- **Verify:** All tabs navigate correctly
- **End state:** Last tab visited

### L3: Navigation — Back Button
- **Start:** Any sub-screen (Task Detail, Settings sub-screen)
- **Steps:**
  1. Navigate to a sub-screen
  2. Press back
  3. Assert return to previous screen
- **Verify:** Back navigation works
- **End state:** Previous screen

### L4: Error Handling — Incomplete Form
- **Start:** Add Task screen
- **Steps:**
  1. Tap FAB → open Add Task
  2. Leave title empty
  3. Try to save
  4. Assert error or save prevented
- **Verify:** Incomplete form handled
- **End state:** Add Task screen

### L5: Error Handling — Wrong Passphrase (3 attempts)
- **Start:** Unlock screen
- **Steps:**
  1. Enter wrong passphrase 3 times
  2. Assert lockout message: "Too many attempts. Try again in X seconds."
- **Verify:** Rate limiting works
- **End state:** Unlock screen (locked out)

### L6: Data Integrity — Insights After CRUD
- **Start:** After completing B1-B14, C1-C12, D1-D6
- **Steps:**
  1. Navigate to Lenses
  2. For each dimension with data:
     - Assert score is non-zero
     - Assert time entries counted
     - Assert habits counted
     - Assert tasks counted
  3. Scroll through all insights sections
  4. Assert no empty/broken charts
- **Verify:** All data flows correctly to insights
- **End state:** Lenses tab

### L7: Compatibility — Android 16 Dialog
- **Start:** Fresh app on Android 16
- **Steps:**
  1. Launch app
  2. If "Don't show again" dialog appears → handle it
  3. Assert app continues normally
- **Verify:** Compat dialog doesn't block usage
- **End state:** Onboarding / Main screen


# ═══════════════════════════════════════════════════════════════
# PART 3: EXECUTION ORDER (Dependencies)
# ═══════════════════════════════════════════════════════════════
#
# GROUP A (Setup) → must complete first
#   A1/A2 → A3 → A4/A5/A6/A7 → A8/A9/A10-A17 → A18/A19 → A20
#
# GROUP B (Tasks) → after A
#   B1 → B2 → B3 → B4 → B5 → B6 → B7-B14
#
# GROUP C (Habits) → after A (parallel with B)
#   C1 → C2 → C3 → C4 → C5-C12
#
# GROUP D (Time) → after A (parallel with B/C)
#   D1 → D2 → D3 → D4 → D5-D6
#
# GROUP E (Journal) → after A (parallel with B/C/D)
#   E1 → E2 → E3 → E4-E5
#
# GROUP F (Notes) → after A (parallel with B/C/D/E)
#   F1 → F2 → F3 → F4-F6
#
# GROUP G (Lenses) → after B/C/D (needs data)
#   G1 → G2-G9
#
# GROUP H (Settings) → after A (parallel with B/C/D/E/F)
#   H1 → H2-H21
#
# GROUP I (Passphrase) → after A
#   I1-I5
#
# GROUP J (Export/Import) → after B/C/D/E/F (needs data)
#   J1-J3
#
# GROUP K (Scoring) → after A
#   K1-K3
#
# GROUP L (Cross-cutting) → after all data groups
#   L1-L7
#
# RECOMMENDED BATCH ORDER:
#   1. A1-A20 (Setup) — ~3 min
#   2. B1-B14 + C1-C12 + D1-D6 + E1-E5 + F1-F6 (All CRUD) — ~10 min
#   3. G1-G9 (Lenses verification) — ~3 min
#   4. H1-H21 (Settings) — ~8 min
#   5. I1-I5 (Passphrase) — ~3 min
#   6. J1-J3 (Export/Import) — ~5 min
#   7. K1-K3 (Scoring) — ~2 min
#   8. L1-L7 (Cross-cutting) — ~3 min
#   TOTAL: ~37 min estimated
