# Installing Payanam (Sideload Guide)
Last Updated: 2026-10-27

This guide covers how to download, verify, and install Payanam on your Android device, and how to install the Linux desktop build from a `.deb`.

## Step 1 — Download

Go to the [Releases page](https://github.com/Aravinth-Earth/Payanam/releases) and pick the newest release — releases are tagged `v<version>` (for example `v1.0.0`), and every published release is a `release`-type build — then download:

- `Payanam_<platform>_<version>_<sha>_<debug|release>_<yyyyMMdd_HHmmss>.apk` — the app (e.g. `Payanam_android_1.0.0_a1b2c3d_release_20261027_101500.apk`)
- `Payanam_<platform>_<version>_<sha>_<debug|release>_<yyyyMMdd_HHmmss>.apk.sha256` — checksum file

## Step 2 — Verify the APK (recommended)

Before installing, verify the APK hasn't been corrupted or tampered with.
Open the `.sha256` file — it contains one line like:

```
A3F8C2D1E4B7F9A0C2E5D8F1B4A7C0E3F6A9B2C5D8E1F4A7B0C3D6E9F2A5B8C1  Payanam_android_1.0.0_a1b2c3d_release_20261027_101500.apk
```

Run the matching command for your OS and compare the output to that hash — they must match exactly.

### Windows

```powershell
certutil -hashfile Payanam_android_1.0.0_a1b2c3d_release_20261027_101500.apk SHA256
```

### macOS

```bash
shasum -a 256 Payanam_android_1.0.0_a1b2c3d_release_20261027_101500.apk
```

### Linux

```bash
sha256sum Payanam_android_1.0.0_a1b2c3d_release_20261027_101500.apk
```

### Android (via Files app or terminal)

If you want to verify directly on the phone before installing:

```bash
# If you have Termux installed
sha256sum /sdcard/Download/Payanam_android_1.0.0_a1b2c3d_release_20261027_101500.apk
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

1. On the **old** device, open **Settings → Data Management** and tap **Export**. This writes a single encrypted file containing your database — habits, tasks, journal, time entries, notes and settings.
2. Copy that file to the new device (USB, cable, or any file-transfer method you like).
3. On the **new** device, the app opens with a short tour. After it, choose **Import Database File (.db)** and enter the passphrase the export was made with.

Already have a database on the new device? **Settings → Data Management → Import** replaces it with your exported one.

## Installing the Linux desktop app

Payanam also ships a `.deb` package for Debian-based Linux — Ubuntu, Debian, Linux Mint, Pop!_OS, and anything else that uses `apt`.

### Download

From the same [Releases page](https://github.com/Aravinth-Earth/Payanam/releases), take:

- `Payanam_linux_<version>_<sha>_release_<yyyyMMdd_HHmmss>.deb` — the app (e.g. `Payanam_linux_1.0.0_a1b2c3d_release_20261027_101500.deb`)
- `Payanam_linux_<version>_<sha>_release_<yyyyMMdd_HHmmss>.deb.sha256` — checksum file

### Verify the .deb (recommended)

Open the `.sha256` file — it contains one line naming the hash and the file it belongs to:

```
A3F8C2D1E4B7F9A0C2E5D8F1B4A7C0E3F6A9B2C5D8E1F4A7B0C3D6E9F2A5B8C1  Payanam_linux_1.0.0_a1b2c3d_release_20261027_101500.deb
```

With both files in the same folder, check them against each other:

```bash
sha256sum -c Payanam_linux_1.0.0_a1b2c3d_release_20261027_101500.deb.sha256
# Payanam_linux_1.0.0_a1b2c3d_release_20261027_101500.deb: OK
```

`sha256sum <file>.deb` prints the same hash if you would rather compare by eye.

> If the hash doesn't match, do not install. Re-download the file and try again.

### Install

```bash
sudo apt install ./Payanam_linux_1.0.0_a1b2c3d_release_20261027_101500.deb
```

The leading `./` matters: it tells `apt` the argument is a file on disk rather than a package to look up by name. On a distribution without `apt`:

```bash
sudo dpkg -i Payanam_linux_1.0.0_a1b2c3d_release_20261027_101500.deb
```

### Update

Download the newer `.deb` and run the same `apt install ./<file>.deb` (or `dpkg -i`) over it. The package replaces the old one in place and your data is untouched.

### Uninstall

```bash
sudo apt remove payanam
```

Your data is not part of the package: the database and your notes stay in `~/Documents/Payanam` after the app is removed — delete that folder yourself if you want a clean start.

## Notes

- **Minimum Android version:** Android 9 (API 28)
- **What to expect:** one release stream — the newest release is the current version, and its filename states the build type. Published releases are `release`-type builds only: a `debug`-type build is a development build and is never published.
- All data stays on your device; no accounts or internet required
- **To update:** install a newer APK over the existing one — data is preserved.
- **Switching build type is a fresh app:** `debug`-type and `release`-type installs have different app IDs, so a released APK cannot update an older `debug`-type install in place — export your data from the old install and re-import it after installing.
