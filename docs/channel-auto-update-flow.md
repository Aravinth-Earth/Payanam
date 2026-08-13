# Channel Auto-Update — Flow & State Machine

> Last Updated: 2026-08-13

## 1. Main update flow

```mermaid
flowchart TD
    Start([Settings > About]) --> Channel[Channel selector<br/>dev / beta / stable]
    Channel --> Button{Action button<br/>state-driven}

    Button -->|"Check for update"| Check[GitHub API call<br/>1 request, all 3 channels]
    Check --> Parse[Parse releases<br/>latest-dev / latest-beta / latest-stable]
    Parse --> Compare{Selected channel build<br/>vs installed?}

    Compare -->|"same / older"| UptoDate[✓ Up to date]
    Compare -->|"newer"| OnDisk{APK already<br/>in app downloads dir?}

    OnDisk -->|"yes"| InstallNow1[Button: Install now<br/>no re-download]
    OnDisk -->|"no"| AutoDL{Auto-download<br/>toggle ON?}

    AutoDL -->|"no"| DLBtn[Button: Download update]
    AutoDL -->|"yes"| Enqueue[DownloadManager enqueue]

    DLBtn --> Enqueue
    Enqueue --> WiFi{Wi-Fi-only<br/>toggle ON?}
    WiFi -->|"yes, on mobile data"| Wait[Waiting for Wi-Fi…<br/>system auto-resumes]
    Wait --> Progress

    WiFi -->|"no / on Wi-Fi"| Progress[Downloading…<br/>dev · #1573<br/>file name · % · Cancel]

    Progress -->|Cancel| Cancelled[Idle<br/>state cleaned]
    Progress -->|"failed"| Retry[Button: Retry]
    Retry --> Enqueue
    Progress -->|"complete"| Done[Downloaded state<br/>stored: build + file + time]

    Done --> Prompt{Prompt-to-install<br/>toggle ON?}
    Prompt -->|"yes"| Popup[Popup: Update now?<br/>Update / Later]
    Prompt -->|"no"| InstallBtn[Button: Install now]

    Popup -->|Later| Keep[File kept,<br/>Install now available]
    Popup -->|Update| System[System installer<br/>unknown-apps prompt first time]
    InstallBtn --> System
    Keep --> InstallBtn
    System --> Updated[✓ App updated]

    style Start fill:#e8f5e9
    style Updated fill:#c8e6c9
    style Wait fill:#fff3e0
    style Retry fill:#ffebee
    style Cancelled fill:#eceff1
```

## 2. Restart / staleness handling (15-min rule)

```mermaid
flowchart TD
    Kill([App/device killed<br/>after download complete<br/>before install]) --> Restart[App restarts]

    Restart --> ReadState[Read persisted state<br/>build + file + timestamp]

    ReadState --> FileCheck{APK still<br/>on disk?}
    FileCheck -->|"no (cleaned)"| Clear[Clear stale markers<br/>→ Idle → Check for update]
    FileCheck -->|"yes"| FreshCheck{Download completed<br/>< 15 min ago?}

    FreshCheck -->|"yes"| InstallNow[Button: Install now<br/>file name shown<br/>NO re-download]
    FreshCheck -->|"no (stale)"| Revert[Button: Check for update<br/>fresh check takes priority]

    Revert --> Check[Check → new build found]
    Check --> Scan{That build's APK<br/>in downloads dir?}
    Scan -->|"yes"| InstallNow2[Install now<br/>no re-download]
    Scan -->|"no"| Download[Download update<br/>normal path]

    InstallNow --> Install[Install flow]
    InstallNow2 --> Install

    style Kill fill:#ffebee
    style InstallNow fill:#c8e6c9
    style InstallNow2 fill:#c8e6c9
    style Revert fill:#fff3e0
```

## 3. Channel mapping

| Branch | Channel | Cadence | Tag | Prerelease |
|--------|---------|---------|-----|-----------|
| `feature/*` | dev | 10+/day | `latest-dev` | yes |
| `dev` | beta | 2/week | `latest-beta` | yes |
| `main` | stable | 2/month | `latest-stable` | no |

Rolling releases: one release per channel, always the newest. Old releases are deleted on publish.

## 4. Toggles (all persisted in app_settings)

- **Auto-download updates** — after check, download automatically
- **Prompt to install after download** — popup when download completes
- **Download only on Wi-Fi** — DownloadManager `setAllowedOverMetered(false)`; system pauses/resumes
- **Check for updates after unlock** — app-start check, fires post-DB-unlock

## 5. Key behaviors

- Download destination: app-private dir (`Android/data/<pkg>/files/downloads/`) — no storage permission on any API level
- Completed downloads persisted (build, file, timestamp) → restart offers Install instead of re-downloading
- Fresh window: 15 min; stale → re-check first, folder re-scan
- Cancel: removes partial file + persisted state; toggle-off also cancels in-flight
- Single state-driven button: Check → Download → Install now / Retry — one action at a time
- All taps, toggles, transitions, and startup-check decisions traced; no user content in logs
