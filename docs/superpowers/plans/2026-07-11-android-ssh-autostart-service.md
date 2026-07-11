# Android SSH Autostart Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a headless Android app that starts a standards-compatible SSH, SCP, and SFTP foreground service at boot.

**Architecture:** A boot receiver starts a sticky foreground service. The service loads validated build defaults plus a private properties override and delegates the Apache MINA SSHD lifecycle to a focused manager that supplies shell, exec, SCP, and SFTP channels.

**Tech Stack:** Kotlin, Android SDK 24-35, Apache MINA SSHD 2.12.1, JUnit 4, Gradle 8.x.

---

### Task 1: Add SSHD dependencies and build-time defaults

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/libs/sshd-common-2.12.1.jar`
- Create: `app/libs/sshd-core-2.12.1.jar`
- Create: `app/libs/sshd-scp-2.12.1.jar`
- Create: `app/libs/sshd-sftp-2.12.1.jar`
- Create: `app/libs/slf4j-api-1.7.32.jar`

- [ ] Copy the exact SSHD and logging jars from the source `iot/libs` directory.
- [ ] Enable `buildConfig` and define `SSH_BIND_ADDRESS`, `SSH_PORT`, `SSH_USERNAME`, `SSH_PASSWORD`, and `SSH_ENABLED` fields.
- [ ] Add file dependencies for all copied jars.
- [ ] Run `./gradlew :app:dependencies` and verify the app configuration resolves.

### Task 2: Implement validated local configuration with TDD

**Files:**
- Create: `app/src/test/java/io/pocketssh/server/config/SshConfigRepositoryTest.kt`
- Create: `app/src/main/java/io/pocketssh/server/config/SshConfig.kt`
- Create: `app/src/main/java/io/pocketssh/server/config/SshConfigRepository.kt`

- [ ] Write tests proving property overrides merge with defaults and invalid host, port, username, and password values are rejected.
- [ ] Run `./gradlew :app:testDebugUnitTest --tests io.pocketssh.server.config.SshConfigRepositoryTest` and confirm failure because the types do not exist.
- [ ] Implement immutable `SshConfig` validation and a repository that reads `ssh-server.properties` through `java.util.Properties`.
- [ ] Re-run the focused tests and confirm they pass.

### Task 3: Implement command execution with TDD

**Files:**
- Create: `app/src/test/java/io/pocketssh/server/ssh/ProcessCommandTest.kt`
- Create: `app/src/main/java/io/pocketssh/server/ssh/ProcessCommand.kt`
- Create: `app/src/main/java/io/pocketssh/server/ssh/AndroidShellFactory.kt`

- [ ] Write tests for stdout, stderr, and exit status propagation from a real local process.
- [ ] Run the focused test and confirm failure because `ProcessCommand` does not exist.
- [ ] Implement the MINA `Command` lifecycle, stream pumps, process destruction, and exit callback.
- [ ] Implement a shell factory that creates `/system/bin/sh -i` process commands.
- [ ] Re-run the focused tests and confirm they pass.

### Task 4: Implement the SSH server manager with TDD

**Files:**
- Create: `app/src/test/java/io/pocketssh/server/ssh/SshServerManagerTest.kt`
- Create: `app/src/main/java/io/pocketssh/server/ssh/SshServerManager.kt`

- [ ] Write lifecycle tests proving repeated start/stop calls are idempotent and password matching is exact.
- [ ] Run the focused test and confirm failure because the manager does not exist.
- [ ] Configure MINA host key persistence, password authentication, SCP command dispatch, exec command handling, interactive shell, and SFTP subsystem.
- [ ] Re-run focused tests and confirm they pass.

### Task 5: Add Android boot and foreground-service integration with TDD

**Files:**
- Create: `app/src/test/java/io/pocketssh/server/boot/BootActionsTest.kt`
- Create: `app/src/main/java/io/pocketssh/server/boot/BootActions.kt`
- Create: `app/src/main/java/io/pocketssh/server/boot/BootReceiver.kt`
- Create: `app/src/main/java/io/pocketssh/server/service/SshServerService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] Write tests proving only boot and locked-boot actions are accepted.
- [ ] Run the focused test and confirm failure because `BootActions` does not exist.
- [ ] Implement the pure action filter and receiver foreground-service dispatch.
- [ ] Implement notification channel creation, immediate foreground entry, configuration loading, manager lifecycle, `START_STICKY`, and clean shutdown.
- [ ] Declare boot, network, notification, foreground service, receiver, and service manifest entries with no launcher activity.
- [ ] Re-run focused tests and confirm they pass.

### Task 6: Full verification

**Files:**
- Modify only files required by compilation or test findings.

- [ ] Run `./gradlew :app:testDebugUnitTest` and resolve all failures.
- [ ] Run `./gradlew :app:assembleDebug` and resolve all compilation, manifest, and packaging failures.
- [ ] Inspect the merged manifest to verify there is no launcher activity and both boot actions are present.
- [ ] Record APK location and the remaining requirement for device-level OpenSSH/SCP/SFTP testing.

Commits are omitted because `E:/Android/Demo/PocketSSH` currently has no Git metadata.
