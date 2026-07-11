# PocketSSH

[中文](README.zh-CN.md) | English

PocketSSH is a lightweight SSH server that runs locally on Android devices. It is designed for LAN device maintenance, debugging, file transfer, and automation control. The app provides a foreground SSH service, a deep-blue dark-tech configuration page, boot auto-start support, and standard SSH/SFTP/SCP compatibility for common desktop clients.

PocketSSH is fully open source under the permissive MIT License. Personal use, commercial use, modification, redistribution, and integration into other products are allowed subject to the license notice requirements.

## Highlights

- Local Android SSH server based on Apache MINA SSHD.
- Works with common SSH clients such as OpenSSH, PuTTY, WinSCP, Termius, and other SFTP/SCP tools.
- Configurable username, password, and port from the app page.
- Automatically restarts the SSH service after saving configuration, so new settings take effect immediately.
- Foreground service with boot auto-start support.
- Supports SSH interactive shell, remote exec, SCP, and SFTP.
- SFTP path aliases for common Android storage paths:
  - `/sdcard`
  - `/storage/emulated/0`
  - `/storage/self/primary`
- Persistent host key storage, so SSH clients do not see a new server identity on every restart.
- Device command capabilities migrated from the IoT module, including files, system information, apps, running processes, cameras, volume, SQLite, and related diagnostics.
- UI and notification text follow the Android system language:
  - English is the default resource language.
  - Simplified Chinese is provided through `values-zh-rCN`.

## Screens and Notifications

The app has a simple configuration page for:

- Viewing service status and LAN endpoint.
- Editing username, password, and port.
- Starting or stopping the SSH service.
- Saving configuration and applying it immediately.

Notification text is also localized through Android resources and follows the system language.

## Quick Start

1. Install and open PocketSSH.
2. Configure `Username`, `Password`, and `Port`.
3. Tap `SAVE AND APPLY` to save settings and restart the service automatically.
4. Tap `START SERVICE` if the service is not already running.
5. Connect from a computer on the same LAN:

```bash
ssh -p 2222 android@<device-ip>
```

Default development parameters are usually:

```text
Username: android
Password: android
Port: 2222
```

Use the actual values shown and saved in the app.

## SFTP and SCP

SFTP:

```bash
sftp -P 2222 android@<device-ip>
```

SCP download:

```bash
scp -P 2222 android@<device-ip>:/sdcard/Download/example.txt ./example.txt
```

SCP upload:

```bash
scp -P 2222 ./local.txt android@<device-ip>:/sdcard/Download/local.txt
```

Some OpenSSH clients may require legacy SCP mode:

```bash
scp -O -P 2222 ./local.txt android@<device-ip>:/sdcard/Download/local.txt
```

## Build

On macOS or Linux:

```bash
./gradlew :app:assembleDebug
```

On Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated with a versioned file name:

```text
app/build/outputs/apk/debug/PocketSSH-v1.0.0001.apk
```

The naming format is:

```text
PocketSSH-v{versionName}.{versionCode padded to 4 digits}.apk
```

## GitHub Releases

Push a version tag matching the generated APK version to publish a GitHub Release automatically:

```bash
git tag v1.0.0001
git push origin v1.0.0001
```

GitHub Actions runs the unit tests, builds an installable APK, creates the release, and attaches `PocketSSH-v1.0.0001.apk`. Update `versionName` and `versionCode` in `app/build.gradle.kts` before creating each new tag.

## Documentation

Project documentation follows a simple internationalized file layout:

- English default:
  - [README.md](README.md)
  - [USAGE.md](USAGE.md)
  - [LICENSE.md](LICENSE.md)
  - [DISCLAIMER.md](DISCLAIMER.md)
- Simplified Chinese:
  - [README.zh-CN.md](README.zh-CN.md)
  - [USAGE.zh-CN.md](USAGE.zh-CN.md)
  - [LICENSE.zh-CN.md](LICENSE.zh-CN.md)
  - [DISCLAIMER.zh-CN.md](DISCLAIMER.zh-CN.md)

## Important Notes

- PocketSSH is not a system root shell. Commands run with the app's own permissions by default.
- Runtime behavior is limited by the Android app sandbox, file access rules, SELinux, system permissions, and device root state.
- Commands requiring system-level privileges may need root or system signature permissions. Without those permissions, commands may return a clear failure message.
- SFTP access is still limited by Android's actual file permissions.
- Do not expose a weak-password SSH service directly to the public internet.
- For commercial or batch deployment, perform security assessment, permission auditing, and password policy planning first.

## License

PocketSSH is open-source software released under the [MIT License](LICENSE.md). It may be used, modified, distributed, sublicensed, and used commercially without separate authorization, provided that the copyright and license notices are retained.

See [LICENSE.md](LICENSE.md). Please also read [DISCLAIMER.md](DISCLAIMER.md) before use.
