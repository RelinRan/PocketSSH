# SSH Control UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a technology-styled Android launcher screen for SSH configuration, status, and start/stop control.

**Architecture:** Device-protected SharedPreferences is the single configuration store. A command/state contract coordinates MainActivity and the foreground service, while a pure endpoint selector supports testable LAN address display.

**Tech Stack:** Kotlin, Android XML Views, AppCompat, Material Components, Apache MINA SSHD, JUnit 4.

---

### Task 1: Persist configuration

**Files:**
- Modify: `app/src/main/java/io/pocketssh/server/config/SshConfigRepository.kt`
- Modify: `app/src/test/java/io/pocketssh/server/config/SshConfigRepositoryTest.kt`

- [ ] Add failing tests for preference-compatible key/value persistence and default fallback.
- [ ] Run the focused configuration tests and confirm the missing save/load API failure.
- [ ] Implement map-based serialization helpers used by Android SharedPreferences.
- [ ] Re-run focused tests and confirm success.

### Task 2: Define service commands and state

**Files:**
- Create: `app/src/test/java/io/pocketssh/server/service/SshServiceContractTest.kt`
- Create: `app/src/main/java/io/pocketssh/server/service/SshServiceContract.kt`
- Modify: `app/src/main/java/io/pocketssh/server/service/SshServerService.kt`
- Modify: `app/src/main/java/io/pocketssh/server/boot/BootReceiver.kt`

- [ ] Add failing tests for START/STOP action mapping and state labels.
- [ ] Run focused tests and confirm missing contract failure.
- [ ] Implement command constants, state snapshot persistence, state broadcast, explicit stop, and existing boot start behavior.
- [ ] Re-run focused tests and confirm success.

### Task 3: Select the LAN endpoint

**Files:**
- Create: `app/src/test/java/io/pocketssh/server/network/LanAddressSelectorTest.kt`
- Create: `app/src/main/java/io/pocketssh/server/network/LanAddressSelector.kt`

- [ ] Add failing tests preferring non-loopback IPv4 addresses and rejecting loopback/IPv6 candidates.
- [ ] Run focused tests and confirm missing selector failure.
- [ ] Implement pure candidate selection plus network-interface enumeration.
- [ ] Re-run focused tests and confirm success.

### Task 4: Build the native control screen

**Files:**
- Create: `app/src/main/java/io/pocketssh/server/MainActivity.kt`
- Create: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/res/drawable/bg_status_panel.xml`
- Create: `app/src/main/res/drawable/bg_terminal_grid.xml`
- Modify: `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values/themes.xml`
- Modify: `app/src/main/res/values-night/themes.xml`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] Implement the graphite/cyan terminal-style layout with stable responsive dimensions and Material fields.
- [ ] Implement preference load/save, inline validation, notification permission, state observation, restart after save, and start/stop controls.
- [ ] Add MAIN/LAUNCHER activity declaration while preserving boot/service entries.
- [ ] Compile resources and correct any theme/layout errors.

### Task 5: Verify

**Files:**
- Modify only files required by verification findings.

- [ ] Run the entire unit suite.
- [ ] Build the debug APK.
- [ ] Inspect the merged manifest for launcher, boot receiver, and foreground service entries.
- [ ] Report APK location and remaining device UI/client checks.

Commits are omitted because the workspace has no Git metadata.
