# Installing Payanam (Sideload Guide)
Last Updated: 2026-10-25

This guide covers how to download, verify, and install Payanam on your Android device.

## Step 1 — Download

Go to the [Releases page](https://github.com/Aravinth-Earth/Payanam/releases) and pick the newest release for your channel — Dev releases are tagged `dev-v<build>`, Beta `beta-v<build>`, Stable `v<build>` — then download:

- `Payanam_Android_<build>_<debug|release>_<yyyyMMdd_HHmmss>.apk` — the app (e.g. `Payanam_Android_1607_debug_20260912_143000.apk`)
- `Payanam_Android_<build>_<debug|release>_<yyyyMMdd_HHmmss>.apk.sha256` — checksum file

## Step 2 — Verify the APK (recommended)

Before installing, verify the APK hasn't been corrupted or tampered with.
Open the `.sha256` file — it contains one line like:

```
A3F8C2D1E4B7F9A0C2E5D8F1B4A7C0E3F6A9B2C5D8E1F4A7B0C3D6E9F2A5B8C1  Payanam_Android_1607_debug_20260912_143000.apk
```

Run the matching command for your OS and compare the output to that hash — they must match exactly.

### Windows

```powershell
certutil -hashfile Payanam_Android_1607_debug_20260912_143000.apk SHA256
```

### macOS

```bash
shasum -a 256 Payanam_Android_1607_debug_20260912_143000.apk
```

### Linux

```bash
sha256sum Payanam_Android_1607_debug_20260912_143000.apk
```

### Android (via Files app or terminal)

If you want to verify directly on the phone before installing:

```bash
# If you have Termux installed
sha256sum /sdcard/Download/Payanam_Android_1607_debug_20260912_143000.apk
```

> If the hashes don't match, do not install. Re-download the APK and try again.

## Step 3 — Allow installing from unknown sources

Android blocks sideloaded apps by default. Enable it once:

1. Open **Settings** on your Android device
2. Go to **Apps** (or **Apps & notifications**)
3. Tap **Special app access** → **Install unknown apps**
4. Find the app you'll use to open the APK (e.g. **Files**, **Chrome**, or **My Files**)
5. Toggle **Allow from this source** to ON

> You only need to do this once per installer app. You can turn it off again after installing.

## Step 4 — Install

1. Transfer the `.apk` file to your phone (via USB, email, Google Drive, WhatsApp, etc.)
2. Open the APK file from your phone's file manager
3. Tap **Install** when prompted
4. Tap **Open** once installation completes

## Moving to a new device

Your data lives on your phone. Payanam tells Android not to back it up and not to transfer it during a device-to-device move, so a new phone — or a phone restored from a backup — starts empty.

To move your data across:

1. On the **old** device, open **Settings → Data Management** and export your data. The export is encrypted, and it contains your database — habits, tasks, journal, time entries and settings.
2. Copy that exported file to the new device (USB, cable, or any file-transfer method you like).
3. On the **new** device, the app opens its setup screen because no database exists yet. Choose **Import** there and enter the passphrase the export was made with.

Session logs are **not** part of the export: they are diagnostics, they stay on the device that wrote them, and they are never needed to restore your data.

## Notes

- **Minimum Android version:** Android 9 (API 28)
- **What to expect by channel:** dev builds are development builds (`debug`-type) — expect rough edges; beta and stable are `release`-type builds and are the steadier way to run the app day to day.
- All data stays on your device; no accounts or internet required
- **Channels ship different build types:** the dev channel ships `debug`-type APKs; beta and stable ship `release`-type APKs. The type is part of the filename (`Payanam_Android_<build>_<debug|release>_<yyyyMMdd_HHmmss>.apk`).
- **To update:** install a newer APK of the same build type over the existing one — data is preserved. Debug-type and release-type installs use different app IDs, so switching between types is a different app — export your data and re-import after installing.
- **Coming from an older beta/stable install:** beta/stable now ship `release`-type APKs (previously debug-type). If you run an older debug-type install, either stay on the dev channel or export + re-import — the app IDs differ, so data does not transfer automatically.
