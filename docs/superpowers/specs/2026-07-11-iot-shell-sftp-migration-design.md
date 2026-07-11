# IoT Shell and SFTP Migration Design

## Goal

PocketSSH shall provide every interactive SSH command and all SFTP path behavior currently implemented by `D:/Workspace/Android/AiMultiHandHygiene/iot`, while remaining a standalone application without MQTT, OTA, Bugly, Aliyun tunnel, or other IoT business dependencies.

## Command Surface

Migrate the complete `AndroidInteractiveShellFactory` command surface: shell navigation and help; file listing, reading, search, removal, creation, copy, move, ZIP and download; IP, Wi-Fi, LAN and ping; memory, CPU, process and htop views; application listing, start, stop, install and uninstall; logs and logcat; screenshot; hardware, GPU, NPU, USB and camera information; volume and brightness; system-time query/change and reboot; and all SQLite database, table, column and version commands.

Interactive terminal behavior, history, completion, Ctrl+C handling, formatted output and command help migrate with the command implementations. SCP and remote exec remain supported.

## Dependency Boundary

Migrate `AndroidInteractiveShellFactory`, `RemoteCommandRunner`, command-related parsers and options, and `SftpPathAliasAccessor` into the PocketSSH namespace. Do not migrate `Remote`, `SecureTunnelSshBridge`, MQTT, Alink, OTA, FileServer, Bugly, or tunnel protocol types.

Android-specific data is supplied through a focused resolver component owned by PocketSSH. It uses PackageManager, ActivityManager/process inspection, CameraManager, AudioManager and platform shell commands to provide application metadata, launch results, running applications, cameras and volume information.

## Privilege Policy

The SSH session and ordinary commands run with application privileges. Commands that modify protected system state, including network configuration, system time, reboot, package installation/removal and similar operations, may explicitly invoke `su` as implemented by the migrated command logic. Failure to obtain root returns command output explaining the denial and never terminates the SSH listener. The complete session is never elevated automatically.

## SFTP

Migrate path resolution and reported-attribute normalization for `/sdcard`, `/storage/emulated/0`, and `/storage/self/primary`. At startup, select the first shared-storage candidate that the process can enumerate and create a private shadow tree for virtual `/storage` parents. SFTP remains subject to Android filesystem, SELinux and root permissions. No legacy-storage flag is added.

## Integration

`SshServerManager` constructs the migrated interactive shell with PocketSSH resolvers and registers the migrated SFTP accessor. The existing persistent host key, password authentication, foreground service, boot receiver and configuration UI remain unchanged.

## Testing

Port all command-related parser, runner, interactive-shell, SFTP alias and applicable SSH tests from `iot`, changing only namespace and unavoidable platform seams. Exclude tests for remote tunnel protocols. Run focused tests after each migrated dependency group, then the complete PocketSSH unit suite and debug APK build. On the connected device, verify server startup, SSH banner, password login, representative ordinary and privileged commands, and SFTP listing/path aliases where device permissions allow.

## Constraints

Maintain minSdk 24, targetSdk 35 and JVM 11. Preserve existing user configuration and UI behavior. The workspace has no Git metadata, so migration commits cannot be created.
