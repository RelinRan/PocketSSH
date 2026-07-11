# IoT Shell and SFTP Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port every local interactive SSH command and SFTP alias behavior from the IoT module into PocketSSH.

**Architecture:** Preserve the tested IoT command engine as a cohesive unit in the PocketSSH namespace, backed by focused parser/runner files and an Android resolver. Wire it into the existing manager without importing remote-tunnel or IoT business code.

**Tech Stack:** Kotlin, Android SDK, Apache MINA SSHD 2.12.1, JUnit 4.

---

### Task 1: Port parser and runner dependency closure

**Files:**
- Create: `app/src/main/java/io/pocketssh/server/ssh/AcceleratorInfoParser.kt`
- Create: `app/src/main/java/io/pocketssh/server/ssh/CpuInfoParser.kt`
- Create: `app/src/main/java/io/pocketssh/server/ssh/LogcatOptions.kt`
- Create: `app/src/main/java/io/pocketssh/server/ssh/MemoryInfoParser.kt`
- Create: `app/src/main/java/io/pocketssh/server/ssh/RemoteCommandRunner.kt`
- Create: `app/src/main/java/io/pocketssh/server/ssh/SystemTimeParser.kt`
- Create: `app/src/main/java/io/pocketssh/server/ssh/TopProcessMetricsParser.kt`
- Test: matching parser and runner tests under `app/src/test/java/io/pocketssh/server/ssh/`

- [ ] Copy the existing IoT tests with namespace replacement and run them to confirm missing-symbol failures.
- [ ] Copy the production files with namespace replacement only.
- [ ] Run the focused parser/runner tests and resolve platform-independent compilation differences.

### Task 2: Port SFTP path aliases

**Files:**
- Create: `app/src/main/java/io/pocketssh/server/ssh/SftpPathAliasAccessor.kt`
- Create: `app/src/test/java/io/pocketssh/server/ssh/SftpPathAliasTest.kt`
- Modify: `app/src/main/java/io/pocketssh/server/ssh/SshServerManager.kt`

- [ ] Port alias tests and confirm missing-symbol failures.
- [ ] Port the accessor and configure shared-storage candidate selection plus shadow directories.
- [ ] Run focused SFTP tests.

### Task 3: Port the complete interactive command engine

**Files:**
- Replace: `app/src/main/java/io/pocketssh/server/ssh/AndroidShellFactory.kt`
- Create: `app/src/test/java/io/pocketssh/server/ssh/AndroidInteractiveShellFactoryTest.kt`

- [ ] Port all IoT interactive-shell tests and confirm the simple current shell fails the required behaviors.
- [ ] Port the complete factory with namespace replacement.
- [ ] Run the focused 42-test shell suite and resolve Android/JVM compatibility issues without removing commands.

### Task 4: Implement Android resolvers and manager wiring

**Files:**
- Create: `app/src/main/java/io/pocketssh/server/ssh/AndroidCommandResolvers.kt`
- Modify: `app/src/main/java/io/pocketssh/server/ssh/SshServerManager.kt`
- Modify: `app/src/main/java/io/pocketssh/server/service/SshServerService.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] Implement PackageManager app metadata/list/start, running process, CameraManager and AudioManager resolvers.
- [ ] Pass resolvers and configured username into the interactive shell.
- [ ] Add only permissions required by migrated command behavior.
- [ ] Compile and run all unit tests.

### Task 5: Full and device verification

**Files:**
- Modify only files required by observed failures.

- [ ] Run `:app:testDebugUnitTest` and `:app:assembleDebug`.
- [ ] Install the APK on `192.168.15.109:5555` and start the SSH service.
- [ ] Verify SSH banner and representative help, file, system and SQLite commands.
- [ ] Verify SFTP `/sdcard` alias behavior subject to device permissions.
- [ ] Report unsupported commands with their observed root/SELinux constraints rather than silently omitting them.

Commits are omitted because this workspace has no Git metadata.
