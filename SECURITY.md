# Security Policy

Last Updated: 2026-10-27

Payanam keeps a person's habits, journal, time entries and tasks in an encrypted
local database on their own device. There is no server, no account and no telemetry,
so the security surface is the data at rest and the code that unlocks it.

## What to report

- **The encrypted database and key handling** — the SQLCipher database, how the key
  is derived and held, and anything that could let the plaintext or the key reach
  disk, a log, an export or a crash report.
- **Passphrase verification and lockout** — how the passphrase is checked without
  being stored, and whether the lockout after repeated failures can be bypassed.
- **Backup, export and import** — whether an export can leak more than the user
  asked for, or an imported file can overwrite or execute something it should not.
- **The update check and artefact verification** — the optional GitHub update
  check, and whether a release asset is verified before it is installed. The F-Droid
  build makes no update call at all.
- **The desktop build's database handling** — the Compose Desktop build for Linux and
  Windows follows the same encrypted-database rules as Android, foreign keys included.
  A divergence between the two is a defect.

## What is not a vulnerability

- **A rooted device, or one with an unlocked bootloader.** If the OS can be
  instrumented, it can read what the app reads: a property of the device, not of us.
- **An attacker who already has the unlocked device and the passphrase.** Anything
  reachable once the database is unlocked is data the user has access to by design.
- **Third-party ROMs, backup tools, keyboard apps or screen recorders** capturing
  data through permissions the user granted them.
- **A forgotten passphrase.** It is never stored and there is no cloud recovery or
  recovery key: if it is lost, the data cannot be recovered, by design.

## How to report

**Preferred: GitHub private vulnerability reporting.** On the repository's **Security**
tab, choose **Report a vulnerability**; the report stays private until disclosed.

Otherwise use the same contacts the app shows under **Settings → About**:

- Email: `payanam.feedback.unsettled941@8alias.com`
- Signal: `zatu.69`

Please do not open a public issue for a security problem: public issues are visible to
everyone, cannot be made private afterwards, and put users at risk while a fix is
being written.

## What happens next

The maintainer will acknowledge the report and work with you on it: confirming the
problem, agreeing what a fix looks like, and settling a disclosure timeline together
before anything is made public.

The commitment is good faith rather than a service level — reports are read and
answered as promptly as possible, and no response time is promised here.

## No bug bounty

There is no bug bounty, no payment and no monetary reward for reports. Payanam is an
AGPL-3.0 project maintained in personal time; a careful report is still worth sending.
