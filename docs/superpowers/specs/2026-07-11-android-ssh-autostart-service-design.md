# Android SSH Autostart Service Design

## Goal

PocketSSH is a headless Android application that starts a local SSH server after device boot and exposes it to standard SSH clients. It provides interactive shell, remote command execution, SCP, and SFTP without bringing the service process up as root.

## Scope

The implementation reuses only the local SSH server concepts and Apache MINA SSHD libraries from `D:/Workspace/Android/AiMultiHandHygiene/iot`. MQTT, Aliyun secure tunnel, OTA, Bugly, device-management commands, and other IoT business functionality are excluded.

## Architecture

- `SshServerService` is a sticky Android foreground service. It creates a low-priority notification immediately, starts the server, and releases all SSH resources when destroyed.
- `BootReceiver` handles `BOOT_COMPLETED` and `LOCKED_BOOT_COMPLETED` and starts the service with the API-appropriate foreground-service call.
- `SshServerManager` owns one Apache MINA `SshServer`, makes start and stop idempotent, and reports startup failures without leaving partial resources behind.
- `SshConfigRepository` merges private local configuration over build defaults and validates the result.
- `AndroidShellFactory` provides `/system/bin/sh` interactive sessions. The process retains application privileges; a client may explicitly invoke `su`, subject to device policy.
- Apache MINA SSHD provides password authentication, shell and exec channels, SCP, and SFTP.

## Configuration

Build defaults are exposed through `BuildConfig`:

```text
bindAddress=0.0.0.0
port=2222
username=android
password=android
enabled=true
```

At startup, `filesDir/ssh-server.properties` overrides individual defaults. Missing fields retain their build defaults. Invalid values such as blank host/user/password or a port outside `1..65535` cause startup to fail with a clear Logcat message rather than silently exposing an unintended configuration. Secrets are never logged. Deployments must replace the development password.

The SSH host key is generated once under `filesDir/ssh/` and reused across restarts so client host-key verification remains stable.

## Startup Flow

1. Android delivers a supported boot broadcast.
2. `BootReceiver` starts `SshServerService` using `startForegroundService` on Android 8 and later.
3. The service creates its notification channel and enters the foreground before performing server setup.
4. Configuration is loaded. If `enabled=false`, the service stops itself.
5. `SshServerManager` configures authentication, host key, shell, exec, SCP, and SFTP, then binds the configured address and port.
6. `START_STICKY` allows Android to recreate the service after process reclamation.

No privileged-port redirection or iptables rules are used. Port `2222` is the supported default.

## Protocol Behavior

- Authentication accepts exactly the configured username and password. Anonymous and empty-password authentication are disabled.
- Interactive sessions run `/system/bin/sh` with terminal streams connected to the SSH channel.
- Exec channels run the command supplied by the SSH client and return stdout, stderr, and the process exit status.
- SCP is registered through MINA's SCP command factory.
- SFTP is registered as a subsystem. Visible paths remain constrained by Android process permissions; the server does not bypass the sandbox.
- Server start and stop are idempotent, preventing duplicate listeners after repeated boot/service callbacks.

## Android Integration

The manifest declares internet/network access, boot-completed reception, foreground-service permissions, and the data-sync foreground-service type where required by the target SDK. Android 13 notification permission is declared. Notification denial does not authorize background execution beyond Android platform behavior, so device provisioning should grant notifications and remove battery restrictions when continuous availability is required.

The application has no launcher activity. Its only user-visible surface is the low-priority ongoing foreground-service notification.

## Error Handling

Configuration errors, bind failures, and host-key failures are logged without credentials. A failed start closes any partially created server and stops the foreground service. Destroy and repeated stop calls are safe. Unexpected client session failures are isolated from the listening server where Apache MINA supports it.

## Verification

Unit tests cover configuration merging and validation, receiver action filtering, idempotent lifecycle behavior, and command-process exit handling. Integration checks cover valid and invalid password authentication, interactive shell, exec output and exit status, SCP, and SFTP. The final APK is exercised with a standard OpenSSH client, including host-key persistence across service restarts.

## Constraints

- Minimum SDK remains 24 and Java/Kotlin bytecode target remains 11.
- The service runs with normal application permissions and does not automatically elevate to root.
- Device OEM boot, battery, and background restrictions may require provisioning outside the application.
- The repository currently has no Git metadata, so this specification cannot be committed until the workspace is initialized or attached to its intended repository.
