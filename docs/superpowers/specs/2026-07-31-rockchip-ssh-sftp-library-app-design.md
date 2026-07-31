# rockchip-ssh-sftp Library and App Split Design

## Goal

Rework the current PocketSSH application into `rockchip-ssh-sftp`, split into an Android library module plus an Android application module. The new project does not preserve backward compatibility with the old `io.pocketssh.server` application identity, package names, persisted configuration, host keys, or installed-app upgrade path.

The SSH and SFTP behavior shall use `D:/Workspace/Android/AiMultiHandHygiene/iot` as the authoritative source for local SSH/SFTP logic. The project remains focused on Rockchip Android board SSH, SFTP, SCP, and local device-management commands, without importing MQTT, OTA, Bugly, Aliyun secure tunnel, Alink protocol, or other IoT business features.

## Module Structure

Create two Gradle modules:

- `:library`: an Android library containing the reusable SSH/SFTP service engine.
- `:app`: an Android application containing UI, foreground service integration, boot receiver, permission guidance, and packaging.

The root Gradle project name becomes `rockchip-ssh-sftp`.

The existing `:app` module will be split rather than wrapped wholesale. SSH/SFTP implementation code moves into `:library`; Android application shell code remains in `:app`.

## Package and Identity

Use the new Android identity `io.rockchip.sshsftp`.

`applicationId` for `:app` becomes `io.rockchip.sshsftp`. The app namespace also becomes `io.rockchip.sshsftp`.

The library namespace becomes `io.rockchip.sshsftp`. Public and internal library code should live under:

- `io.rockchip.sshsftp.ssh` for SSH, SFTP, SCP, shell, command runners, parsers, and Android command resolvers.
- `io.rockchip.sshsftp.config` for reusable SSH configuration data if configuration remains shared by the library API.

App-only code should live under:

- `io.rockchip.sshsftp.app` for Activity, service, boot receiver, UI state, and app-specific persistence.

No compatibility shim for `io.pocketssh.server` is required.

## Library Responsibilities

The Android library owns the reusable SSH/SFTP runtime:

- SSH server lifecycle wrapper adapted from the `iot` module's `androidx.iot.remote.SSH` logic.
- Apache MINA SSHD setup, password authentication, persistent host key path support, SCP delegation, and SFTP subsystem registration.
- Interactive Android shell command engine from `iot`'s `AndroidInteractiveShellFactory`.
- Command helpers and parsers: remote command runner, CPU, memory, top process metrics, accelerator/GPU/NPU, logcat options, system time, archive helpers, and related formatting logic.
- SFTP path alias behavior from `iot`'s `SftpPathAliasAccessor`: `/sdcard`, `/storage/emulated/0`, `/storage/self/primary`, virtual `/storage` parents, and symbolic-link attribute normalization.
- Android resolver interfaces or default Android-backed resolver implementations for application metadata, app start, running apps, camera information, and volume information.

The library may depend on Android APIs directly because it is an Android library. This avoids forcing platform-heavy command behavior through a pure JVM abstraction.

## App Responsibilities

The application owns product behavior around the library:

- Main configuration UI.
- Persisting username, password, bind address, port, and enabled state.
- Foreground service and notification lifecycle.
- Boot auto-start receiver.
- Android 11+ all-files-access permission guidance.
- Local network address display.
- App branding, launcher resources, release artifact naming, and documentation.

The app calls the library through a small service-facing API, passing configuration and resolver dependencies as needed.

## SSH Behavior

The SSH implementation should align with the `iot` module's `SSH.kt` behavior unless the app integration requires a narrow adapter. The migrated server should support:

- configurable host, port, username, and password;
- persistent host key storage;
- interactive shell;
- remote exec;
- SCP;
- SFTP.

The complete SSH session is never elevated automatically. Individual commands may invoke `su` where the migrated `iot` command logic already does so. Root or SELinux failures should be reported as command output or controlled errors without terminating the SSH listener.

Low-port fallback and iptables redirect behavior from `iot` may be kept inside the library API, but app UI wiring should remain explicit and tested before exposing it as a product feature.

## SFTP Behavior

SFTP behavior shall be aligned to the `iot` module's implementation rather than the current app's extra root-backed SFTP extensions.

Required behavior:

- Select an accessible shared-storage candidate.
- Resolve `/sdcard`, `/storage/emulated/0`, and `/storage/self/primary` to shared storage.
- Create a private shadow tree for virtual `/storage`, `/storage/emulated`, and `/storage/self` parent paths.
- Normalize reported symbolic-link attributes so SFTP clients see expected directory/file metadata.

Not required:

- Preserving current root-backed SFTP access to protected paths.
- Preserving current explicit SFTP denial behavior for `/data` or Android protected directories beyond what Android and the `iot` logic naturally enforce.

## Branding and Release Output

Visible project branding becomes `rockchip-ssh-sftp`:

- Gradle root project name.
- App display name and notification text.
- APK output filename.
- GitHub Actions artifact and release asset names.
- README, USAGE, DISCLAIMER, LICENSE, and CHANGELOG references where they describe the product name.

Package identity changes are intentional. Existing installed PocketSSH data, permissions, host keys, and notification channels are not migrated.

## Testing

Port the relevant `iot` SSH/SFTP unit tests into the new library module with package-name changes only where possible. Exclude remote tunnel protocol tests and IoT business tests.

Test layers:

- Library parser/runner tests.
- Library interactive shell tests.
- Library SFTP alias tests.
- Library SSH lifecycle tests.
- App tests for service contract, config persistence, boot behavior, and UI state after package rename.
- Full Gradle verification: `:library:testDebugUnitTest`, `:app:testDebugUnitTest`, and `:app:assembleDebug`.

Device verification should include app startup, permission guidance, foreground service start/stop, SSH password login, representative interactive commands, SCP, and SFTP aliases on a Rockchip Android target where available.

## Migration Constraints

Keep minSdk 24, targetSdk 35, Java 11, Kotlin Android, and Apache MINA SSHD 2.12.1 unless implementation reveals a concrete incompatibility.

Do not import unrelated `iot` dependencies such as Bugly, MQTT, service framework jars, OTA models, Alink data classes, or Aliyun secure tunnel code into the new library.

Prefer direct source migration with namespace replacement first. Add abstractions only where they separate library API from app-specific UI/service wiring or where Android framework dependencies need injectable test doubles.
