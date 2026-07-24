# Installing Payanam (Sideload Guide)
Last Updated: 2026-07-24

This guide covers how to download, verify, and install Payanam on your Android device.

## Step 1 — Download

Go to the [Latest Dev Release](https://github.com/Aravinth-Earth/Payanam/releases/tag/latest-dev) and download:

- `Payanam_Android_XXX_XXXX_XXXXXXXX_XXXXXX.apk` — the app
- `Payanam_Android_XXX_XXXX_XXXXXXXX_XXXXXX.apk.sha256` — checksum file

## Step 2 — Verify the APK (recommended)

Before installing, verify the APK hasn't been corrupted or tampered with.
Open the `.sha256` file — it contains one line like:

```
A3F8C2D1E4B7F9A0C2E5D8F1B4A7C0E3F6A9B2C5D8E1F4A7B0C3D6E9F2A5B8C1  Payanam_Android_XXXX_XXXXXXXX_XXXXXX.apk
```

Run the matching command for your OS and compare the output to that hash — they must match exactly.

### Windows

```powershell
certutil -hashfile Payanam_Android_XXXX_XXXXXXXX_XXXXXX.apk SHA256
```

### macOS

```bash
shasum -a 256 Payanam_Android_XXXX_XXXXXXXX_XXXXXX.apk
```

### Linux

```bash
sha256sum Payanam_Android_XXXX_XXXXXXXX_XXXXXX.apk
```

### Android (via Files app or terminal)

If you want to verify directly on the phone before installing:

```bash
# If you have Termux installed
sha256sum /sdcard/Download/Payanam_Android_XXXX_XXXXXXXX_XXXXXX.apk
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

## Notes

- **Minimum Android version:** Android 9 (API 28)
- This is a **development build** — expect rough edges
- All data stays on your device; no accounts or internet required
- To update: simply install a newer APK over the existing one (data is preserved)
