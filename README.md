# QuestTuner

**English** | [简体中文](README.zh-CN.md)

On-device parameter tuning tool for **Meta Quest 3**. The APK runs on the headset itself and talks to the local ADB daemon (`127.0.0.1:5555`) through a **pure-Kotlin ADB client** to run `setprop` commands. **No root, no PC required** for core tuning. An optional one-time PC command unlocks automation features (see "Optional Grant & Automation").

## Features

| Feature | System property | Notes |
|---|---|---|
| Refresh rate 72 / 90 / 120 Hz | `debug.oculus.refreshRate` | Global |
| Render texture resolution | `debug.oculus.textureWidth / textureHeight` | 0.5x–2.0x scale |
| CPU / GPU performance level 0–4 | `debug.oculus.cpuLevel / gpuLevel` | Power save ↔ Performance |
| Passthrough precision | `debug.oculus.highPrecisionPassthrough` | 0 / 1 |
| Per-app profiles | — | JSON storage, one-tap apply / edit / delete |
| Reset to default | — | Clears all tuning properties |
| First-run wizard | — | Mechanism intro, grant-command copy, switch pre-selection |
| Auto-apply per app | Accessibility service (package name only) | Apply on foreground switch / rollback on exit; fully local |
| Optional automation | WRITE_SECURE_SETTINGS | Auto-enable wireless debugging / accessibility (off by default) |
| In-app self-update | GitHub Releases | 4-fold APK verification (parse / package / signature / no-downgrade), manual trigger |
| Private DNS toggle | Settings.Global | Optional, hostname validation + rollback on failure |
| OVR Metrics toggle | System broadcast | Requires OVR Metrics Tool installed |
| Restore on boot | BOOT_COMPLETED | Optional switch, best-effort |

## How It Works

The app embeds a from-scratch ADB client implementation (no external libraries):

```
adb/    CNXN/AUTH handshake, RSA-2048 key management, android_pubkey encoding
        (524-byte wire format, little-endian), shell v2 streams, packet CRC
tuner/  setprop command mapping, profile repository (atomic JSON writes),
        installed-app scanning (VR intent-filter detection)
update/ Self-update: GitHub Releases check/download, 4-fold APK
        verification, PackageInstaller session
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

## Optional Grant & Automation

Core tuning never needs a PC (the first-connection authorization dialog is confirmed on the headset itself). An optional one-time PC command unlocks extra automation:

```bash
adb shell pm grant com.example.questtuner android.permission.WRITE_SECURE_SETTINGS
```

This enables: auto-enabling wireless debugging on connect, auto-enabling the accessibility service, and Private DNS control.

**No-PC alternative**: System Settings → Accessibility → enable the QuestTuner service manually — auto-apply per app works the same way.

All automation is off by default with independent toggles.

## Privacy

**Privacy.** The only network behavior is the GitHub Releases API request made when you tap "Check for update" (api.github.com). Profiles, tuning parameters, and the accessibility service (which reads only the foreground app's package name, never screen content) are fully local. No analytics, no tracking, no data upload.

## Important Notes

- The `refreshRate` property appears to be **restricted on recent firmware (v74+)**. If it has no effect, please report your firmware version.
- After changing the refresh rate, use **Refresh status** to verify the actual value of `debug.oculus.refreshRate`.
- `textureWidth / textureHeight` reference values: Quest 3 default is 1680×1760 per eye, upper bound 3072×3208.
- **Nothing is persistent**: `setprop` values are lost on reboot, and **Reset to default** (or a reboot) restores stock behavior at any time.
- In-app updates require allowing "install unknown apps" in system settings (done on-headset, no PC).
- The app does not modify system partitions.

## Project Structure

```
QuestTuner/
├── app/src/main/java/com/example/questtuner/
│   ├── adb/          # Pure-Kotlin ADB client protocol (CNXN/AUTH/shell)
│   ├── tuner/        # Tuning command mapping, profile storage, app scanning
│   ├── update/       # Self-update (Releases check, APK verify, install)
│   ├── ui/           # Jetpack Compose UI + ViewModel
│   └── MainActivity.kt
└── docs/             # QA reports: bug report / fix plan / verification
```

## Disclaimer

This project is for tuning your own personal device. All changes are session-only and fully reversible. Not affiliated with Meta. Use at your own risk.

---

QuestTuner v0.2.2 · Kotlin 2.0 · Jetpack Compose (BOM 2024.06) · minSdk 32 (Quest 3 / Android 12L)
