# rockchip-ssh-sftp Library and App Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the current Android SSH/SFTP app into `:library` plus `:app`, rename the project to `rockchip-ssh-sftp`, change Android identity to `io.rockchip.sshsftp`, and align SSH/SFTP behavior with the `AiMultiHandHygiene/iot` module.

**Architecture:** Move reusable SSH/SFTP runtime code into an Android library module and keep UI, service, boot, persistence, permissions, and packaging in the app module. Use the `iot` module's local SSH/SFTP implementation as the behavior baseline while excluding IoT business features. Rename package identity without preserving old `io.pocketssh.server` compatibility.

**Tech Stack:** Kotlin, Android Gradle Plugin, Android library/application modules, Apache MINA SSHD 2.12.1, JUnit 4, Gradle Version Catalog.

---

### Task 1: Create the Android library module

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `library/build.gradle.kts`
- Create: `library/proguard-rules.pro`
- Create: `library/src/main/AndroidManifest.xml`

- [ ] Add the Android library plugin alias to `gradle/libs.versions.toml`.
- [ ] Rename `rootProject.name` to `rockchip-ssh-sftp` and include `:library`.
- [ ] Create `library/build.gradle.kts` with namespace `io.rockchip.sshsftp`, minSdk 24, compileSdk 35, Java 11, Kotlin JVM 11, Apache MINA SSHD jar dependencies, AndroidX core/appcompat/material dependencies, and unit test dependencies.
- [ ] Create a minimal `library/src/main/AndroidManifest.xml` with package-level permissions only if the library code requires manifest merging.
- [ ] Run `.\gradlew.bat --no-daemon :library:tasks` and expect the module to configure successfully.
- [ ] Commit with `chore: add ssh sftp library module`.

### Task 2: Move reusable SSH/SFTP code and tests into `:library`

**Files:**
- Move from `app/src/main/java/io/pocketssh/server/ssh/` to `library/src/main/java/io/rockchip/sshsftp/ssh/`
- Move from `app/src/test/java/io/pocketssh/server/ssh/` to `library/src/test/java/io/rockchip/sshsftp/ssh/`
- Modify package declarations and imports from `io.pocketssh.server.ssh` to `io.rockchip.sshsftp.ssh`
- Keep `app/src/main/java/io/pocketssh/server/config/` in app for now
- Keep `app/src/main/java/io/pocketssh/server/service/` in app for now

- [ ] Move all SSH implementation files into the library module.
- [ ] Move all SSH unit tests into the library module.
- [ ] Replace package declarations in moved files.
- [ ] Replace imports in moved tests.
- [ ] Run `.\gradlew.bat --no-daemon :library:testDebugUnitTest`; expected first failure may identify app-only dependencies that still need library boundaries.
- [ ] Fix moved-code references so library tests compile without app classes.
- [ ] Commit with `refactor: move ssh runtime into library`.

### Task 3: Align library SSH/SFTP logic with the `iot` baseline

**Files:**
- Source reference: `D:/Workspace/Android/AiMultiHandHygiene/iot/src/main/java/androidx/iot/remote/SSH.kt`
- Source reference: `D:/Workspace/Android/AiMultiHandHygiene/iot/src/main/java/androidx/iot/remote/SftpPathAliasAccessor.kt`
- Source reference: `D:/Workspace/Android/AiMultiHandHygiene/iot/src/main/java/androidx/iot/remote/AndroidInteractiveShellFactory.kt`
- Modify: `library/src/main/java/io/rockchip/sshsftp/ssh/SshServerManager.kt` or replace with `SshServer.kt`
- Modify: `library/src/main/java/io/rockchip/sshsftp/ssh/SftpPathAliasAccessor.kt`
- Modify: `library/src/main/java/io/rockchip/sshsftp/ssh/AndroidInteractiveShellFactory.kt`
- Modify matching tests under `library/src/test/java/io/rockchip/sshsftp/ssh/`

- [ ] Compare `iot` and library versions of `AndroidInteractiveShellFactory`, parser files, `RemoteCommandRunner`, and `SftpPathAliasAccessor`.
- [ ] Replace the library SFTP accessor with the `iot` alias behavior, removing app-only root-backed SFTP extensions.
- [ ] Add or expose shared-storage candidate selection from the `iot` SSH logic.
- [ ] Preserve a service-friendly library API that accepts host key path, host, port, username, password, and Android resolvers.
- [ ] Keep low-port fallback inside the library only if tests can cover it without app UI exposure.
- [ ] Run focused tests: `.\gradlew.bat --no-daemon :library:testDebugUnitTest --tests io.rockchip.sshsftp.ssh.SftpPathAliasTest --tests io.rockchip.sshsftp.ssh.SshServerManagerTest`.
- [ ] Commit with `feat: align ssh sftp library with iot logic`.

### Task 4: Rename app package identity and wire it to `:library`

**Files:**
- Modify: `app/build.gradle.kts`
- Move `app/src/main/java/io/pocketssh/server/` to `app/src/main/java/io/rockchip/sshsftp/app/`
- Move `app/src/test/java/io/pocketssh/server/` to `app/src/test/java/io/rockchip/sshsftp/app/`
- Move `app/src/androidTest/java/io/pocketssh/server/` to `app/src/androidTest/java/io/rockchip/sshsftp/app/`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify app imports to use `io.rockchip.sshsftp.ssh.*`

- [ ] Set app namespace and applicationId to `io.rockchip.sshsftp`.
- [ ] Add `implementation(project(":library"))` to app dependencies.
- [ ] Rename app package declarations to `io.rockchip.sshsftp.app`.
- [ ] Update Manifest activity, service, and receiver class names to `.app.MainActivity`, `.app.boot.BootReceiver`, and `.app.service.SshServerService`.
- [ ] Update app service code to instantiate the library SSH manager/API and pass app-owned config/resolvers.
- [ ] Update app tests and androidTest expected package name to `io.rockchip.sshsftp`.
- [ ] Run `.\gradlew.bat --no-daemon :app:testDebugUnitTest`.
- [ ] Commit with `refactor: rename app identity and consume library`.

### Task 5: Rename product branding and release output

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `.github/workflows/android.yml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify docs: `README.md`, `README.zh-CN.md`, `USAGE.md`, `USAGE.zh-CN.md`, `DISCLAIMER.md`, `DISCLAIMER.zh-CN.md`, `LICENSE.md`, `LICENSE.zh-CN.md`, `CHANGELOG.md`, `CHANGELOG.zh-CN.md`

- [ ] Change APK output naming from `PocketSSH-v...apk` to `rockchip-ssh-sftp-v...apk`.
- [ ] Change GitHub Actions artifact/release asset discovery to expect the new APK name.
- [ ] Change app display name to `rockchip-ssh-sftp`.
- [ ] Change notification titles and docs from `PocketSSH`/`PSSH` to `rockchip-ssh-sftp`.
- [ ] Keep historical changelog entries readable while making current product name clear.
- [ ] Run `rg -n "PocketSSH|PSSH|pocketssh|io\\.pocketssh"` and verify only intentional historical references remain.
- [ ] Commit with `chore: rename product to rockchip ssh sftp`.

### Task 6: Full verification and release readiness

**Files:**
- Modify only files required by observed failures.

- [ ] Stop existing Gradle daemons with `.\gradlew.bat --stop`.
- [ ] Run clean verification: `.\gradlew.bat --no-daemon clean`.
- [ ] Run library tests: `.\gradlew.bat --no-daemon :library:testDebugUnitTest`.
- [ ] Run app tests: `.\gradlew.bat --no-daemon :app:testDebugUnitTest`.
- [ ] Run app build: `.\gradlew.bat --no-daemon :app:assembleDebug`.
- [ ] Check output APK name under `app/build/outputs/apk/debug/`.
- [ ] Run `git status --short` and ensure only intended files changed.
- [ ] Commit any final fixes with a focused message.
- [ ] Report verification output and any device-verification gap if no Rockchip device is connected.
