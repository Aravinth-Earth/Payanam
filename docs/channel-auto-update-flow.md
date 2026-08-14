# Channel Auto-Update — Flow & State Machine

> Last Updated: 2026-08-13
> Diagram-first: review this diagram before any code change to the update flow.
> Convention: node = logic state; node text carries the UI button/message it shows; edge labels marked `button →` show where the button text changes.

## 1. Combined flow — single live-check on open

```mermaid
flowchart TD
    Open([Open Settings > About<br/>or app start]) --> Check[GitHub API call<br/>1 request, all 3 channels<br/>parses APK + sha256 assets<br/>button: Checking…]

    Check -->|"API error · button → Check again"| CheckFailed[Check failed<br/>offline · rate-limited · timeout<br/>msg: check failed]
    CheckFailed -->|"tap Check again"| Check
    Check --> Parse[Parse releases<br/>latest-dev / latest-beta / latest-stable<br/>+ sha256 asset URLs]
    Parse -->|"found=0 or no APK asset · button → Check again"| NoBuild[No build found<br/>for this channel]
    NoBuild -->|"tap Check again"| Check
    Parse --> Compare{Selected channel build<br/>vs installed?}
    Compare -->|"same or older"| UptoDate[✓ Up to date<br/>button: Check for update<br/>msg: up to date]
    Compare -->|"newer · button → Download update"| OnDisk{APK for that build<br/>already on disk?}

    OnDisk -->|"yes"| VerifyOnDisk[Verify on-disk APK sha256<br/>vs published asset]
    VerifyOnDisk -->|"valid"| InstallNow1[Button: Install now<br/>no re-download<br/>msg: ready to install]
    VerifyOnDisk -->|"mismatch"| CleanBad[Delete corrupt file]
    CleanBad --> AutoDL

    OnDisk -->|"no"| AutoDL{Auto-download<br/>toggle ON?}
    AutoDL -->|"no"| DLBtn[Button: Download update]
    AutoDL -->|"yes"| Enqueue[DownloadManager enqueue]
    DLBtn --> Enqueue
    Enqueue -->|"failed · button → Download update"| EnqueueFail[Download couldn't start<br/>msg: download could not start]
    EnqueueFail --> DLBtn

    Enqueue --> WiFi{Wi-Fi-only<br/>toggle ON?}
    WiFi -->|"yes on mobile data"| Wait[Waiting for Wi-Fi…<br/>system-managed auto-resume<br/>button: Cancel]
    Wait --> Progress
    WiFi -->|"no or on Wi-Fi"| Progress[Downloading…<br/>dev · #1578<br/>file name · % · Cancel<br/>attempt N/3<br/>button: Cancel]

    Progress -->|"cancel"| Cancelled[Idle<br/>state cleaned<br/>button: Check for update]
    Progress -->|"disk full · button → Download update"| DiskFull[Storage full<br/>free space and retry<br/>msg: storage full]
    Progress -->|"failed network"| RetryLoop{attempt 1..3<br/>button: Retry N/3}
    RetryLoop -->|"retry"| Enqueue
    RetryLoop -->|"exhausted 3/3"| FailOutcome[Download failed<br/>try again later<br/>button: Retry]
    Progress -->|"complete"| Done[Download complete<br/>state stored]

    Done --> Verify[Fetch published sha256<br/>for that build]
    Verify -->|"sha asset missing · button → Open release page"| VerifyUnavail[Verification unavailable<br/>msg: verification unavailable]
    Verify --> Hash[Compute APK hash]
    Hash --> Match{SHA match?}
    Match -->|"no · button → Open release page"| VerifyFail[Verification failed<br/>open release page to install manually<br/>no retry — re-download cannot fix]
    Match -->|"yes"| Verified[✓ Verified APK<br/>log: path · size · valid<br/>button: Install now]
    Verified --> Cleanup[Cleanup: keep last 2 APKs<br/>log inventory: path · size · verdict]

    Cleanup --> Prompt{Prompt-to-install<br/>toggle ON?}
    Prompt -->|"yes"| Popup[Popup: Update now?<br/>Update / Later<br/>button: Update now]
    Prompt -->|"no"| InstallBtn[Button: Install now]
    Popup -->|"later"| Keep[File kept,<br/>Install now available<br/>button: Install now]
    Popup -->|"update"| Handoff[Resolve + log installer name<br/>hand off to Android]
    InstallBtn --> Handoff
    Keep --> InstallBtn
    Handoff --> System[System installer<br/>unknown-apps prompt first time]
    System --> Updated[✓ App updated]
    System -->|"returned, version unchanged · button → Retry install"| Interrupted[Install didn't complete<br/>manual retry only — no auto retry<br/>msg: install did not complete]
    Interrupted -->|"tap retry"| Handoff
    InstallNow1 --> Handoff

    style Open fill:#e8f5e9
    style Updated fill:#c8e6c9
    style Wait fill:#fff3e0
    style RetryLoop fill:#fff3e0
    style Cancelled fill:#eceff1
    style Verified fill:#c8e6c9
    style VerifyFail fill:#ffebee
    style Interrupted fill:#ffebee
```

## 2. Channel mapping

| Branch | Channel | Cadence | Tag | Prerelease |
|--------|---------|---------|-----|-----------|
| `feature/*` | dev | 10+/day | `latest-dev` | yes |
| `dev` | beta | 2/week | `latest-beta` | yes |
| `main` | stable | 2/month | `latest-stable` | no |

Rolling releases: one release per channel, always the newest. Old releases are deleted on publish.

## 3. Toggles (all persisted in app_settings)

- **Auto-download updates** — after check, download automatically
- **Prompt to install after download** — popup when download completes
- **Download only on Wi-Fi** — DownloadManager `setAllowedOverMetered(false)`; system pauses/resumes
- **Check for updates after unlock** — app-start check, fires post-DB-unlock

## 4. APK verification & inventory

**Download pipeline (identical for manual and auto paths — only the trigger differs):**

1. **Download completes** → state stored (build + file + time)
2. **Fetch published sha256** for that build from the release assets (`<apk>.sha256`)
3. **Compute APK hash** → compare → **verdict**
4. **Verdict = valid** → log `path · size · valid` → **cleanup: keep last 2 APKs only** → continue to install prompt
5. **Verdict = mismatch** → **NO retry** (re-download cannot fix a bad published asset) → delete corrupt file → terminal state: *"Verification failed — open release page to install manually"*
6. **SHA asset missing** → terminal state: *"Verification unavailable — open release page"*

**Per-APK inventory log format** (one entry per APK found in the app-private dir — path included, since APKs may live in different subpaths):

```
path | sizeMB | verdict
```

- `verdict = valid` — hash matches the published sha256 asset
- `verdict = mismatch` — file present but hash differs from published
- `verdict = unpublished` — build no longer in rolling releases (orphan)

**Inventory is logged at every boundary:** before enqueue, after download complete, after cleanup, and at open-check pre-enqueue probe.

**Cleanup rule:** tied to a **verified new download** (manual or auto — same pipeline), **never to app open/start**. After verification passes, keep only the last 2 APKs (new + previous), delete the rest.

## 5. State derivation (no timestamps)

The UI state is derived **entirely from the live check** — there is no freshness window, no 15-min rule, no persisted staleness:

| Live check result | On-disk state | UI |
|-------------------|---------------|-----|
| Newer build exists | APK for that build on disk + sha valid | **Install now** (no re-download) |
| Newer build exists | APK on disk but sha mismatch | delete file → **Download update** |
| Newer build exists | no APK on disk | **Download update** (or auto-download) |
| Same / older | anything | **Up to date** — nothing triggered (leftover APKs stay until next verified download's cleanup) |

Reopening the app or About section = fresh check = fresh derivation. Nothing to remember across restarts.

## 6. Button label map (state → UI text)

| Flow state | Button label | Message | Button enabled? |
|------------|-------------|---------|-----------------|
| Idle / Up to date | `Check for update` | "✓ Up to date" | ✅ |
| Checking | `Checking…` | — | ❌ (spinner) |
| Check failed | `Check again` | "Check failed — offline/rate-limited" | ✅ |
| No build for channel | `Check again` | "No build found for this channel" | ✅ |
| Update available | `Download update` | "v#1578 available" | ✅ |
| Enqueue failed | `Download update` | "Download couldn't start" | ✅ |
| Waiting for Wi-Fi | `Cancel` | "Waiting for Wi-Fi…" | ✅ |
| Downloading | `Cancel` | "Downloading… 45% (attempt 2/3)" | ✅ |
| Download failed (auto retries) | `Retry N/3` | "Download failed" | ✅ |
| Download failed (exhausted) | `Retry` | "Download failed — try again later" | ✅ |
| Disk full | `Download update` | "Storage full — free space and retry" | ✅ |
| Downloaded / Verified | `Install now` | "Ready to install" | ✅ |
| SHA mismatch | `Open release page` | "Verification failed" | ✅ |
| SHA asset missing | `Open release page` | "Verification unavailable" | ✅ |
| Install interrupted | `Retry install` | "Install didn't complete" | ✅ |

Edge annotations `button → X` in the diagram mark exactly where the label changes between these states.

## 7. Retry & error policy

| Failure | Retry? | Behavior |
|---------|--------|----------|
| Check API error (offline/rate-limit/timeout) | Manual only | "Check failed" message; tap Check again |
| Channel empty / no APK asset | Manual only | "No build found for this channel" |
| Enqueue failed | Manual only | "Download couldn't start" |
| Download failed (network) | **Auto, max 3 attempts** | Progress shows `attempt N/3`; after 3/3 → "Download failed — try again later" |
| Disk full | **No retry** | "Storage full — free space and retry" (Android-mapped reason) |
| SHA mismatch | **No retry** | Delete file → "Verification failed — open release page" |
| SHA asset missing | No retry | "Verification unavailable — open release page" |
| Install interrupted (returned, version unchanged) | **Manual only** | "Install didn't complete — tap to retry" (no auto install retry) |

**Handoff principle:** we trace what we control, we delegate what Android owns (wifi-wait, network resume, disk-full detection), and when handing off to the system installer we resolve and **log the handler name** (PackageInstaller component) — then the app only detects return + version to surface the interrupted-install state.

## 8. Key behaviors

- Download destination: app-private dir (`Android/data/<pkg>/files/downloads/`) — no storage permission on any API level
- Completed downloads persisted (build, file, timestamp) for trace; install offer comes from the live check, not the timestamp
- Cancel: removes partial file + persisted state; toggle-off also cancels in-flight
- Single state-driven button: Check → Download → Install now / Retry — one action at a time
- Every APK is sha256-verified against the published release asset before it can be offered for install
- All taps, toggles, transitions, verifications, cleanups, and startup-check decisions traced; no user content in logs

## 1b. State-diagram variant (stateDiagram-v2) — for comparison

```mermaid
stateDiagram-v2
    [*] --> Idle

    Idle: button: Check for update
    Idle --> Checking: tap Check for update

    Checking: button: Checking… (disabled)
    Checking --> CheckFailed: API error · offline · rate-limited · timeout
    Checking --> NoBuild: found=0 or no APK asset
    Checking --> UpToDate: same or older
    Checking --> UpdateAvailable: newer build

    CheckFailed: button → Check again · msg: check failed
    CheckFailed --> Checking: tap Check again

    NoBuild: button → Check again · msg: no build for this channel
    NoBuild --> Checking: tap Check again

    UpToDate: button: Check for update · msg: up to date
    UpToDate --> Checking: tap Check for update

    UpdateAvailable: button → Download update
    UpdateAvailable --> OnDiskVerify: APK for build on disk
    UpdateAvailable --> AutoDownload: no APK on disk

    OnDiskVerify: verify sha256 vs published asset
    OnDiskVerify --> InstallReady: valid
    OnDiskVerify --> DownloadDecision: mismatch

    InstallReady: button: Install now · no re-download
    InstallReady --> Installing: tap Install now

    DownloadDecision: delete corrupt file
    DownloadDecision --> AutoDownload: proceed

    AutoDownload: auto-download toggle ON?
    AutoDownload --> Enqueue: yes
    AutoDownload --> ManualDownload: no

    ManualDownload: button: Download update
    ManualDownload --> Enqueue: tap Download update

    Enqueue: DownloadManager enqueue
    Enqueue --> EnqueueFailed: enqueue failed
    Enqueue --> WaitingWifi: wifi-only + metered
    Enqueue --> Downloading: enqueued

    EnqueueFailed: button: Download update · msg: download couldn't start
    EnqueueFailed --> Enqueue: tap retry

    WaitingWifi: button: Cancel · waiting for Wi-Fi · system-managed
    WaitingWifi --> Downloading: on Wi-Fi

    Downloading: button: Cancel · dev #1578 · % · attempt N/3
    Downloading --> Cancelled: cancel
    Downloading --> DiskFull: disk full
    Downloading --> FailedRetry: network fail
    Downloading --> Downloaded: complete

    Cancelled: state cleaned · button: Check for update
    Cancelled --> Idle: reset

    DiskFull: button: Download update · msg: storage full
    DiskFull --> ManualDownload: tap retry

    FailedRetry: button: Retry N/3
    FailedRetry --> Enqueue: retry · attempt below 3
    FailedRetry --> FailedExhausted: exhausted 3/3

    FailedExhausted: button: Retry · msg: download failed · try again later
    FailedExhausted --> Enqueue: tap retry

    Downloaded: download complete · state stored
    Downloaded --> ShaFetch: verify

    ShaFetch: fetch published sha256
    ShaFetch --> ShaUnavailable: asset missing
    ShaFetch --> ShaCompare: sha fetched

    ShaUnavailable: button: Open release page · msg: verification unavailable

    ShaCompare: compute APK hash + compare
    ShaCompare --> Verified: match
    ShaCompare --> ShaFail: mismatch

    ShaFail: button: Open release page · msg: verification failed · no retry

    Verified: verified APK · log path · size · valid
    Verified --> Cleanup: verified

    Cleanup: keep last 2 APKs · log inventory
    Cleanup --> InstallDecision: done

    InstallDecision: prompt-to-install toggle ON?
    InstallDecision --> Popup: yes
    InstallDecision --> InstallButton: no

    Popup: popup: Update now? · Update / Later
    Popup --> Keep: Later
    Popup --> Installing: Update now

    Keep: file kept · button: Install now
    Keep --> Installing: tap Install now

    InstallButton: button: Install now
    InstallButton --> Installing: tap Install now

    Installing: resolve + log installer name → hand off to Android
    Installing --> Updated: installed
    Installing --> Interrupted: returned · version unchanged

    Updated: app updated
    Interrupted: button: Retry install · msg: install didn't complete · manual only
    Interrupted --> Installing: tap retry
```
