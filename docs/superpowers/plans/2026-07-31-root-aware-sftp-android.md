# Root-Aware SFTP Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** On rooted devices, expose the real filesystem root through SFTP and allow direct access to `/storage/emulated/0/Android`; preserve the current restricted mapping on non-root devices.

**Architecture:** Detect root once when the SSH service starts by executing `su 0 id` with a short timeout. Pass the result into `SftpPathAliasAccessor`; rooted mode maps `/` and storage paths to the real filesystem and skips Android-directory denial, while non-root mode keeps the existing shadow tree and protected-path behavior.

**Tech Stack:** Kotlin, Android Service, Apache MINA SSHD SFTP, JUnit.

---

### Task 1: Add root-aware path resolution tests

**Files:**
- Modify: `library/src/test/java/io/rockchip/sshsftp/ssh/SftpPathAliasTest.kt`
- Modify: `library/src/main/java/io/rockchip/sshsftp/ssh/SftpPathAliasAccessor.kt`

- [ ] Add tests covering:
  - rooted `/` resolves to `/`;
  - rooted `/storage/emulated/0/Android` resolves to the real shared-storage Android directory;
  - rooted `/data/local/tmp` resolves to `/data/local/tmp`;
  - non-root `/` still resolves to shared storage;
  - non-root Android paths remain denied.
- [ ] Run the focused test and confirm the new rooted expectations fail before implementation.

### Task 2: Implement root-aware SFTP path mapping

**Files:**
- Modify: `library/src/main/java/io/rockchip/sshsftp/ssh/SftpPathAliasAccessor.kt`

- [ ] Add a `rootAccess` boolean to `resolveRemoteSftpPath` and `SftpPathAliasAccessor`.
- [ ] In rooted mode:
  - return `Paths.get("/")` for remote `/`;
  - resolve absolute remote paths directly with `Paths.get(remote)`;
  - keep `/sdcard` and `/storage/self/primary` aliases mapped to shared storage;
  - bypass `isDeniedSftpPath`.
- [ ] Keep the existing shadow-root behavior unchanged when `rootAccess` is false.
- [ ] Run the focused tests and confirm they pass.

### Task 3: Detect root during SSH service startup

**Files:**
- Modify: `library/src/main/java/io/rockchip/sshsftp/ssh/SshServerManager.kt`
- Modify: `app/src/main/java/io/rockchip/sshsftp/app/service/SshServerService.kt`

- [ ] Add a small root probe that runs `su 0 id`, waits at most two seconds, and returns false on missing `su`, timeout, non-zero exit, or output without `uid=0`.
- [ ] Pass the probe result into `SshServerManager`.
- [ ] Log whether rooted mode is enabled without logging credentials.
- [ ] Pass the same mode into `SftpPathAliasAccessor`.

### Task 4: Verify on device

**Files:**
- No source changes expected.

- [ ] Build and install the debug APK.
- [ ] Start the service through the app UI on `192.168.15.104`.
- [ ] Use an SFTP client to verify `/` lists root-level directories.
- [ ] Verify `/storage/emulated/0/Android` can be listed and accessed.
- [ ] Verify the SSH banner and port `2222`.
- [ ] Run all unit tests and `git diff --check`.

