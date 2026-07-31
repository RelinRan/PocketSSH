# rockchip-ssh-sftp

[中文](README.zh-CN.md) | English

rockchip-ssh-sftp is a lightweight SSH server that runs locally on Android devices. It is designed for LAN device maintenance, debugging, file transfer, and automation control. The app provides a foreground SSH service, a deep-blue dark-tech configuration page, boot auto-start support, and standard SSH/SFTP/SCP compatibility for common desktop clients.

The project is currently developed and tested primarily for SSH remote access on Rockchip-based Android development boards. Compatibility on phones, tablets, other chipsets, and vendor-customized Android systems may vary.

rockchip-ssh-sftp is fully open source under the permissive MIT License. Personal use, commercial use, modification, redistribution, and integration into other products are allowed subject to the license notice requirements.

## Highlights

- Local Android SSH server based on Apache MINA SSHD.
- Works with common SSH clients such as OpenSSH, PuTTY, WinSCP, Termius, and other SFTP/SCP tools.
- Configurable username, password, and port from the app page.
- Automatically restarts the SSH service after saving configuration, so new settings take effect immediately.
- Foreground service with boot auto-start support.
- Supports SSH interactive shell, remote exec, SCP, and SFTP.
- SSH shell and SFTP sessions start in shared storage (`/storage/emulated/0`) by default, so `ls` and the initial file list show sdcard contents immediately.
- Android 11 and newer are guided to grant All files access; the SSH service restarts after the permission is granted.
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

1. Install and open rockchip-ssh-sftp.
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

On connection, SFTP `/` represents the device shared-storage root. These paths are equivalent:

```text
/
/sdcard
/storage/emulated/0
```

Parent navigation from `/` remains at the shared-storage root and does not enter the protected Android system root.

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
app/build/outputs/apk/debug/rockchip-ssh-sftp-v1.0.0002.apk
```

The naming format is:

```text
rockchip-ssh-sftp-v{versionName}.{versionCode padded to 4 digits}.apk
```

## GitHub Releases

Push a version tag matching the generated APK version to publish a GitHub Release automatically:

```bash
git tag v1.0.0002
git push origin v1.0.0002
```

GitHub Actions runs the unit tests, builds an installable APK, creates the release, and attaches `rockchip-ssh-sftp-v1.0.0002.apk`. Update `versionName` and `versionCode` in `app/build.gradle.kts` before creating each new tag.

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

- rockchip-ssh-sftp is not a system root shell. Commands run with the app's own permissions by default.
- On a non-rooted device, some commands and protected paths cannot be accessed because the app does not have the required system privileges. This is an Android security restriction, not an SSH authentication issue.
- Runtime behavior is limited by the Android app sandbox, file access rules, SELinux, system permissions, and device root state.
- Commands requiring system-level privileges may need root or system signature permissions. Without those permissions, commands may return a clear failure message.
- SFTP access is still limited by Android's actual file permissions.
- On Android 11 and newer, grant rockchip-ssh-sftp the system All files access permission to browse shared storage. `Android/data`, `Android/obb`, and system `/data` may remain restricted by Android, SELinux, vendor policy, or root state.
- Do not expose a weak-password SSH service directly to the public internet.
- For commercial or batch deployment, perform security assessment, permission auditing, and password policy planning first.
- The primary test environment is Rockchip-based Android development boards used for SSH remote maintenance. Test the required commands on the target hardware and Android build before deployment.

## License

rockchip-ssh-sftp is open-source software released under the [MIT License](LICENSE.md). It may be used, modified, distributed, sublicensed, and used commercially without separate authorization, provided that the copyright and license notices are retained.

See [LICENSE.md](LICENSE.md). Please also read [DISCLAIMER.md](DISCLAIMER.md) before use.
