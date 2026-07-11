# PocketSSH Usage Guide

[中文](USAGE.zh-CN.md) | English

This document explains how to install, configure, connect to, and use PocketSSH.

## 1. Page Configuration

After opening the app, configure the following parameters on the main page:

- `Username`: SSH login username.
- `Password`: SSH login password.
- `Port`: SSH service port, from `1` to `65535`.

After tapping `SAVE AND APPLY`, the app saves the configuration and automatically restarts the SSH service so that the new username, password, and port take effect immediately.

Tap `START SERVICE` to start the SSH service. When the service is running, the button changes to `STOP SERVICE`.

## 2. SSH Connection

Make sure the Android device and the computer are on the same LAN, then check the address shown on the page, for example:

```text
192.168.15.109:2222
```

Connect with a common SSH client:

```bash
ssh -p 2222 android@192.168.15.109
```

Where:

- Replace `2222` with the configured port.
- Replace `android` with the configured username.
- Replace `192.168.15.109` with the device IP shown on the page.

## 3. Remote Exec

You can execute a single command directly through SSH:

```bash
ssh -p 2222 android@192.168.15.109 "id"
```

You can also enter the interactive shell and use built-in commands:

```bash
ssh -p 2222 android@192.168.15.109
help
```

Common command examples:

```text
help
pwd
ls /sdcard
cat /proc/meminfo
apps
running-apps
cameras
volume
sqlite <db-path> <sql>
```

The actual available commands are listed by `help`.

## 4. SFTP

Connect with the system SFTP client or a third-party SFTP client:

```bash
sftp -P 2222 android@192.168.15.109
```

After connecting, common Android storage paths are supported:

```text
/sdcard
/storage/emulated/0
/storage/self/primary
```

Examples:

```sftp
ls /sdcard
get /sdcard/Download/example.txt
put local.txt /sdcard/Download/local.txt
```

## 5. SCP

Copy a file from the device to the computer:

```bash
scp -P 2222 android@192.168.15.109:/sdcard/Download/example.txt ./example.txt
```

Copy a file from the computer to the device:

```bash
scp -P 2222 ./local.txt android@192.168.15.109:/sdcard/Download/local.txt
```

Some OpenSSH clients may require legacy compatibility mode:

```bash
scp -O -P 2222 ./local.txt android@192.168.15.109:/sdcard/Download/local.txt
```

## 6. Boot Auto-Start

The app supports boot broadcast handling and can automatically start the SSH service after device boot. Whether it becomes available immediately depends on the device system policy, foreground service restrictions, battery optimization policy, and whether the first configuration has been completed.

## 7. Permissions and Limits

- PocketSSH is not a system root shell. It runs with the app's own permissions by default.
- File access, app list visibility, process information, and system commands are limited by the Android version and device permissions.
- High-privilege operations such as network configuration, system time changes, installation, uninstallation, and reboot may require root or system-level authorization.
- If permission is missing, commands should return a clear error and should not crash the SSH service.

## 8. Security Suggestions

- Change the default username and password.
- Do not expose the SSH port to the public internet.
- Use it only on trusted LANs or controlled networks.
- Stop the service when it is no longer needed.
- For production or batch deployment, manage passwords and port policies centrally and complete a security review.
