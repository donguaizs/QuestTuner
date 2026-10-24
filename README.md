# QuestTuner

**English** | [简体中文](README.zh-CN.md)

On-device parameter tuning tool for **Meta Quest 3** — a QGO / Quest Assistant style app. The APK runs on the headset itself and talks to the local ADB daemon (`127.0.0.1:5555`) through a **pure-Kotlin ADB client** to run `setprop` commands. **No root, no PC required** after installation.

## Features

| Feature | System property | Notes |
|---|---|---|
| Refresh rate 72 / 90 / 120 Hz | `debug.oculus.refreshRate` | Global |
| Render texture resolution | `debug.oculus.textureWidth / textureHeight` | 0.5x–2.0x scale |
| CPU / GPU performance level 0–4 | `debug.oculus.cpuLevel / gpuLevel` | Power save ↔ Performance |
| Passthrough precision | `debug.oculus.highPrecisionPassthrough` | 0 / 1 |
| Per-app profiles | — | JSON storage, one-tap apply / delete |
| Reset to default | — | Clears all tuning properties |

## How It Works

The app embeds a from-scratch ADB client implementation (no external libraries):

```
adb/    CNXN/AUTH handshake, RSA-2048 key management, android_pubkey encoding
        (524-byte wire format, little-endian), shell v2 streams, packet CRC
tuner/  setprop command mapping, profile repository (atomic JSON writes),
        installed-app scanning (VR intent-filter detection)
ui/     Jetpack Compose UI + ViewModel (MVVM)
```

Everything happens on the headset: the app connects to `adbd` on `127.0.0.1:5555` as a regular ADB client, authorizes with its own generated RSA key (the familiar "Allow USB debugging?" dialog appears once), then executes `setprop` as the `shell` user, which has write access to `debug.*` properties.

## Build

Requirements: **JDK 17**, **Android SDK** (compileSdk 34), **Gradle 8.7+**.

```bash
gradle :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

## Install on Quest 3

1. Headset: **Settings → System → Developer options → enable USB / wireless debugging** (developer mode must be activated first)
2. From a PC via USB:
   ```bash
   adb devices
   adb install -r app-debug.apk
   ```
3. In the headset: **App Library → filter "Unknown Sources" → QuestTuner**
4. On first tap of **Connect**, the headset shows the *"Allow USB debugging?"* dialog → check **Always allow** → **Allow**
5. After that, everything runs on-device — no PC needed

## Important Notes

- The `refreshRate` property appears to be **restricted on recent firmware (v74+)**. If it has no effect, please report your firmware version.
- After changing the refresh rate, use **Refresh status** to verify the actual value of `debug.oculus.refreshRate`.
- `textureWidth / textureHeight` reference values: Quest 3 default is 1680×1760 per eye, upper bound 3072×3208.
- **Nothing is persistent**: `setprop` values are lost on reboot, and **Reset to default** (or a reboot) restores stock behavior at any time.
- The app does not modify system partitions.

## Project Structure

```
QuestTuner/
├── app/src/main/java/com/example/questtuner/
│   ├── adb/          # Pure-Kotlin ADB client protocol (CNXN/AUTH/shell)
│   ├── tuner/        # Tuning command mapping, profile storage, app scanning
│   ├── ui/           # Jetpack Compose UI + ViewModel
│   └── MainActivity.kt
└── docs/             # QA reports: bug report / fix plan / verification
```

## Quality

The codebase went through a full agent-team QA cycle (see [docs/](docs/)): **18 defects found (1×P0, 9×P1, 8×P2), 17 fixed** — including a P0 in the ADB public-key encoding that broke authorization on unpaired devices — all fixes verified by diff review plus a clean build (17/17 PASS). Details:

- [Bug report (round 1)](docs/bugs/BUG-REPORT-round1.md)
- [Fix plan (round 1)](docs/plans/PLAN-round1.md)
- [Verification (round 1)](docs/verification/VERIFY-round1.md)

## Disclaimer

This project is for tuning your own personal device. All changes are session-only and fully reversible. Not affiliated with Meta. Use at your own risk.

---

QuestTuner v0.1.0 · Kotlin 2.0 · Jetpack Compose (BOM 2024.06) · minSdk 32 (Quest 3 / Android 12L)
