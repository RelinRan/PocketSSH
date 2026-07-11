package io.pocketssh.server.ssh

import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AndroidInteractiveShellFactoryTest {

    @Test
    fun shellStartsInConfiguredSharedStorageDirectory() {
        val root = kotlin.io.path.createTempDirectory("pocketssh-shell-root").toFile()
        File(root, "visible.txt").writeText("ok")
        try {
            val text = runShellWithFactory(
                "pwd\nls\nexit\n",
                AndroidInteractiveShellFactory(shellPath = shellPath(), initialDirectory = root),
            )
            assertTrue(text, text.contains(root.absolutePath))
            assertTrue(text, text.contains("visible.txt"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cdProtectedAndroidDirectoryReportsPermissionDeniedAndKeepsWorkingDirectory() {
        val text = runShell("pwd\ncd /data\npwd\ncd /storage/emulated/0/Android\npwd\nexit\n")

        assertTrue(text, text.contains("cd: /data: Permission denied"))
        assertTrue(text, text.contains("cd: /storage/emulated/0/Android: Permission denied"))
        val expectedDirectory = File("/").absolutePath
        val rootLines = text.lineSequence().map { it.trim() }.filter { it == expectedDirectory }.count()
        assertTrue(text, rootLines >= 3)
    }

    @Test
    fun cameraInfoByIdShowsAllPropertiesWithoutTableTruncation() {
        val longSizes = "3840x2160, 2560x1440, 1920x1080, 1280x720, 640x480"
        val text = runShellWithFactory(
            "camera-info 1\nexit\n",
            AndroidInteractiveShellFactory(
                shellPath = shellPath(),
                cameraResolver = {
                    listOf(
                        AndroidInteractiveShellFactory.CameraInfo(id = "0", facing = "BACK"),
                        AndroidInteractiveShellFactory.CameraInfo(
                            id = "1", facing = "FRONT", orientation = "270", hardwareLevel = "FULL",
                            flash = "no", autofocus = "AUTO, CONTINUOUS_PICTURE", fpsRanges = "[15,30], [30,30]",
                            photoSizes = longSizes, videoSizes = longSizes,
                            capabilities = "BACKWARD_COMPATIBLE, MANUAL_SENSOR, RAW, PRIVATE_REPROCESSING",
                        ),
                    )
                },
            ),
        )

        assertTrue(text, text.contains("id            : 1"))
        assertTrue(text, text.contains("facing        : FRONT"))
        assertTrue(text, text.contains("photo_sizes   : $longSizes"))
        assertTrue(text, text.contains("capabilities  : BACKWARD_COMPATIBLE, MANUAL_SENSOR, RAW, PRIVATE_REPROCESSING"))
        assertFalse(text, text.contains("id            : 0"))
    }

    @Test
    fun cameraInfoByUnknownIdListsAvailableIds() {
        val text = runShellWithFactory(
            "camera-info 3\nexit\n",
            AndroidInteractiveShellFactory(
                shellPath = shellPath(),
                cameraResolver = { listOf(AndroidInteractiveShellFactory.CameraInfo("0"), AndroidInteractiveShellFactory.CameraInfo("1")) },
            ),
        )

        assertTrue(text, text.contains("camera-info: camera ID not found: 3"))
        assertTrue(text, text.contains("available IDs: 0, 1"))
    }

    @Test
    fun shellRunsCommandsAndReturnsPromptUntilExit() {
        val shell = AndroidInteractiveShellFactory(shellPath()).createShell(ChannelSession())
        val output = ByteArrayOutputStream()
        val exited = CountDownLatch(1)
        shell.setInputStream(ByteArrayInputStream("${multiLineCommand()}\nexit\n".toByteArray()))
        shell.setOutputStream(output)
        shell.setErrorStream(output)
        shell.setExitCallback(object : ExitCallback {
            override fun onExit(exitValue: Int, exitMessage: String?, closeImmediately: Boolean) {
                exited.countDown()
            }
        })

        shell.start(ChannelSession(), ChannelSession().environment)

        assertTrue(exited.await(12, TimeUnit.SECONDS))
        val text = stripAnsi(output.toString())
        assertTrue(text.contains("Welcome to Android remote shell"))
        assertTrue(text.contains("$ "))
        assertTrue(text.contains("C1\r\nfirst\r\nsecond\r\n"))
    }

    @Test
    fun shellDoesNotCrashWhenClientChannelClosesDuringWrite() {
        val shell = AndroidInteractiveShellFactory(shellPath()).createShell(ChannelSession())
        val exited = CountDownLatch(1)
        shell.setInputStream(ByteArrayInputStream("exit\n".toByteArray()))
        shell.setOutputStream(ClosedOutputStream())
        shell.setErrorStream(ClosedOutputStream())
        shell.setExitCallback(object : ExitCallback {
            override fun onExit(exitValue: Int, exitMessage: String?, closeImmediately: Boolean) {
                exited.countDown()
            }
        })

        shell.start(ChannelSession(), ChannelSession().environment)

        assertTrue(exited.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun helpListsAllBuiltinCommandGroups() {
        val text = runShell("help\nexit\n")

        assertTrue(text.contains("Usage: help [command]"))
        assertTrue(text.contains("Commands:"))
        assertTrue(Regex("help \\[command]\\s+show all help").containsMatchIn(text))
        assertTrue(Regex("ls \\[path]\\s+list name").containsMatchIn(text))
        listOf(
            "create-file <path> [content]",
            "find-files [path]",
            "ip | ifconfig",
            "wifi-connect <ssid>",
            "wifi-disconnect",
            "wifi-set <enable|disable|status>",
            "lan-connect <iface>",
            "lan-disconnect <iface>",
            "lan-set <iface>",
            "ping <host>",
            "mem [package]",
            "apps [package]",
            "running-apps",
            "htop",
            "gpu",
            "npu",
            "start-app <package>",
            "kill-app <pid|package>",
            "logs [--tag TAG]",
            "install-apk <path>",
            "download <url> <dest>",
            "scp",
            "sqlite <dbPath>",
            "sqlite-dbs [dir]",
            "sqlite-create-db <dbPath>",
            "sqlite-delete-db <dbPath>",
            "sqlite-rename-db <oldPath>",
            "sqlite-create-table",
            "sqlite-drop-table",
            "sqlite-rename-table",
            "sqlite-table",
            "sqlite-columns",
            "sqlite-add-column",
            "sqlite-drop-column",
            "sqlite-rename-column",
            "sqlite-modify-column",
            "sqlite-version",
            "sqlite-set-version",
            "reboot",
            "<any other command>",
        ).forEach { command ->
            assertTrue(text, text.contains(command))
        }
    }

    @Test
    fun helpCanShowOneCommandAndHelpeAlias() {
        val lsHelp = runShell("help ls\nexit\n")
        val logsHelp = runShell("helpe logs\nexit\n")

        assertTrue(lsHelp, lsHelp.contains("ls [path]"))
        assertFalse(lsHelp, lsHelp.contains("sqlite <dbPath>"))
        assertTrue(logsHelp, logsHelp.contains("logs [--tag TAG]"))
        assertFalse(logsHelp, logsHelp.contains("ls [path]"))
    }

    @Test
    fun sqliteModeCanExitBackToShell() {
        val text = runShell("sqlite test.db\n.exit\nexit\n")

        assertTrue(text.contains("SQLITE"))
        assertTrue(text.contains("exit=.exit|.quit"))
        assertTrue(text.contains("LEAVE"))
        assertTrue(text.contains("$ exit"))
    }

    @Test
    fun sqliteSqlWithoutPathDoesNotTreatSelectAsDatabasePath() {
        val dir = createTempDir(prefix = "ssh-sqlite-parse-test")
        File(dir, "hand.db").writeText("")

        try {
            val text = runShell("cd ${dir.absolutePath}\nsqlite select * from TbPalm\nexit\n")

            assertFalse(text, text.contains("usage: sqlite <path>"))
            assertFalse(text, text.contains("sqlite: ${File(dir, "select").absolutePath}"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sqliteDatabaseFileCommandsCreateListRenameAndDelete() {
        val dir = createTempDir(prefix = "ssh-sqlite-db-test")
        val first = File(dir, "first.db")
        val second = File(dir, "second.db")

        try {
            val text = runShell(
                "sqlite-create-db ${first.absolutePath}\n" +
                    "sqlite-dbs ${dir.absolutePath}\n" +
                    "sqlite-rename-db ${first.absolutePath} ${second.absolutePath}\n" +
                    "sqlite-delete-db ${second.absolutePath}\n" +
                    "exit\n"
            )

            assertTrue(text, text.contains("first.db"))
            assertTrue(text, text.contains("ok"))
            assertFalse(first.exists())
            assertFalse(second.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sqliteHelpIncludesTableOperationCommands() {
        val text = runShell("help sqlite\nexit\n")

        assertTrue(text, text.contains("sqlite-create-table"))
        assertTrue(text, text.contains("sqlite-drop-table"))
        assertTrue(text, text.contains("sqlite-rename-table"))
        assertTrue(text, text.contains("sqlite-table"))
        assertTrue(text, text.contains("sqlite-columns"))
        assertTrue(text, text.contains("sqlite-add-column"))
        assertTrue(text, text.contains("sqlite-drop-column"))
        assertTrue(text, text.contains("sqlite-rename-column"))
        assertTrue(text, text.contains("sqlite-modify-column"))
        assertTrue(text, text.contains("sqlite-version"))
        assertTrue(text, text.contains("sqlite-set-version"))
    }

    @Test
    fun sqliteCreateTableHelpRequiresTypedColumnsOrCreateSql() {
        val text = runShell("help sqlite-create-table\nexit\n")

        assertTrue(text, text.contains("<typedColumns>"))
        assertTrue(text, text.contains("<createSql>"))
    }

    @Test
    fun promptLooksLikeNormalSshCliPrompt() {
        val text = runShell("pwd\nexit\n", promptUser = "tester", promptHost = "device")

        assertTrue(text, text.contains("tester@device:"))
        assertTrue(text, text.contains("$ pwd\r\n"))
        assertTrue(text, text.contains("$ exit\r\n"))
    }

    @Test
    fun tabCompletesBuiltinCommands() {
        val text = runShell("pw\t\nexit\n")

        assertTrue(text, text.contains("$ pwd\r\n"))
    }

    @Test
    fun arrowKeysRecallCommandHistory() {
        val text = runShell("pwd\n\u001B[A\nexit\n")

        assertTrue(text, Regex("\\$ pwd\\r\\n.*\\$ pwd\\r\\n", RegexOption.DOT_MATCHES_ALL).containsMatchIn(text))
    }

    @Test
    fun leftAndRightArrowsMoveInputCursor() {
        val text = runShell("echo abcd\u001B[D\u001B[DX\nexit\n")

        assertTrue(text, text.contains("abXcd"))
        assertFalse(text, text.contains("abcdX"))
    }

    @Test
    fun plainLeftAndRightArrowsDoNotClearAndRedrawInputLine() {
        val raw = runShellRaw("echo abcd\u001B[D\u001B[C\nexit\n")

        assertFalse(raw, raw.contains("\u001B[2K"))
        assertTrue(raw, raw.contains("\u001B[1D"))
        assertTrue(raw, raw.contains("\u001B[1C"))
    }

    @Test
    fun bracketedPasteInsertsTextAtCursor() {
        val text = runShell("echo ab\u001B[200~PASTE\u001B[201~cd\nexit\n")

        assertTrue(text, text.contains("\r\nabPASTEcd\r\n"))
    }

    @Test
    fun bracketedPasteDoesNotEchoRepeatedProgressiveLines() {
        val shell = AndroidInteractiveShellFactory(shellPath()).createShell(ChannelSession())
        val output = ByteArrayOutputStream()
        val exited = CountDownLatch(1)
        shell.setInputStream(ByteArrayInputStream("echo \u001B[200~PASTED_TEXT\u001B[201~\nexit\n".toByteArray()))
        shell.setOutputStream(output)
        shell.setErrorStream(output)
        shell.setExitCallback(object : ExitCallback {
            override fun onExit(exitValue: Int, exitMessage: String?, closeImmediately: Boolean) {
                exited.countDown()
            }
        })

        shell.start(ChannelSession(), ChannelSession().environment)

        assertTrue(exited.await(5, TimeUnit.SECONDS))
        val raw = output.toString()
        assertTrue(raw, raw.contains("\r\nPASTED_TEXT\r\n"))
        assertFalse(raw, raw.contains("PASTED_TEX\r"))
    }

    @Test
    fun shellEnablesBracketedPasteModeForClientPaste() {
        val shell = AndroidInteractiveShellFactory(shellPath()).createShell(ChannelSession())
        val output = ByteArrayOutputStream()
        val exited = CountDownLatch(1)
        shell.setInputStream(ByteArrayInputStream("exit\n".toByteArray()))
        shell.setOutputStream(output)
        shell.setErrorStream(output)
        shell.setExitCallback(object : ExitCallback {
            override fun onExit(exitValue: Int, exitMessage: String?, closeImmediately: Boolean) {
                exited.countDown()
            }
        })

        shell.start(ChannelSession(), ChannelSession().environment)

        assertTrue(exited.await(5, TimeUnit.SECONDS))
        val raw = output.toString()
        assertTrue(raw, raw.contains("\u001B[?2004h"))
        assertTrue(raw, raw.contains("\u001B[?2004l"))
    }

    @Test
    fun deleteRemovesCharacterAfterCursor() {
        val text = runShell("echo abxc\u001B[D\u001B[D\u001B[3~\nexit\n")

        assertTrue(text, text.contains("\r\nabc\r\n"))
    }

    @Test
    fun shiftArrowSelectionCanBeRemovedWithBackspace() {
        val text = runShell("echo abcd\u001B[1;2D\u001B[1;2D\b\nexit\n")

        assertTrue(text, text.contains("\r\nab\r\n"))
    }

    @Test
    fun shiftArrowSelectionCanBeRemovedWithDelete() {
        val text = runShell("echo abcd\u001B[1;2D\u001B[1;2D\u001B[3~\nexit\n")

        assertTrue(text, text.contains("\r\nab\r\n"))
    }

    @Test
    fun tabCompletesFilePaths() {
        val dir = createTempDir(prefix = "ssh-complete-test")
        val file = File(dir, "sample.txt")
        file.writeText("hello")

        try {
            val text = runShell("cat ${dir.absolutePath}/sam\t\nexit\n")

            assertTrue(text, text.contains("sample.txt"))
            assertTrue(text, text.contains("hello"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ctrlCInterruptsRunningCommand() {
        val command = if (File("/bin/sh").exists()) "sleep 5" else "Start-Sleep -Seconds 5"
        val started = System.currentTimeMillis()
        val text = runShell("$command\n\u0003exit\n", timeoutSeconds = 3)

        assertTrue(text, text.contains("^C"))
        assertTrue("command was not interrupted quickly", System.currentTimeMillis() - started < 3_000)
    }

    @Test
    fun lsSupportsDefaultAllAndLongModes() {
        val dir = createTempDir(prefix = "ssh-ls-test")
        val file = File(dir, "sample.txt")
        val hidden = File(dir, ".hidden.txt")
        file.writeText("hello")
        hidden.writeText("secret")

        try {
            val defaultText = runShell("ls ${dir.absolutePath}\nexit\n")
            assertTrue(defaultText.contains("sample.txt"))
            assertFalse(defaultText.contains(".hidden.txt"))
            assertFalse(defaultText.contains("PERM"))

            val allText = runShell("ls -a ${dir.absolutePath}\nexit\n")
            assertTrue(allText.contains("sample.txt"))
            assertTrue(allText.contains(".hidden.txt"))

            val longText = runShell("ls -l ${dir.absolutePath}\nexit\n")
            assertTrue(longText.contains("total "))
            assertTrue(longText.contains("sample.txt"))
            assertTrue(Regex("[-d][r-][w-][x-]\\s+5\\s+\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\s+sample\\.txt").containsMatchIn(longText))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun findFilesSupportsNameExtensionTypeAndDepth() {
        val dir = createTempDir(prefix = "ssh-find-test")
        File(dir, "root.log").writeText("root")
        File(dir, "nested").mkdirs()
        File(dir, "nested/child.log").writeText("child")
        File(dir, "nested/image.png").writeText("png")

        try {
            val byName = runShell("find-files ${dir.absolutePath} --name \"*.log\"\nexit\n")
            assertTrue(byName, byName.contains("root.log"))
            assertTrue(byName, byName.contains("child.log"))
            assertFalse(byName, byName.contains("image.png"))

            val byDepth = runShell("find-files ${dir.absolutePath} --ext log --max-depth 0\nexit\n")
            assertTrue(byDepth, byDepth.contains("root.log"))
            assertFalse(byDepth, byDepth.contains("child.log"))

            val dirs = runShell("find-files ${dir.absolutePath} --type dir\nexit\n")
            assertTrue(dirs, dirs.contains("nested"))
            assertTrue(dirs, dirs.contains("TYPE") && dirs.contains("PATH") && dirs.contains("SIZE"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pingAcceptsHostWithoutExplicitCount() {
        val text = runShell("ping 127.0.0.1\nexit\n")

        assertFalse(text, text.contains("usage: ping <host>"))
        assertTrue(text, text.contains("ping") || text.contains("127.0.0.1"))
    }

    @Test
    fun appsOutputIncludesAppNameColumn() {
        val shell = AndroidInteractiveShellFactory(
            shellPath = shellPath(),
            appNameResolver = { packageName ->
                if (packageName == "com.android.shell") "Android Shell" else null
            }
        ).createShell(ChannelSession())
        val output = ByteArrayOutputStream()
        val exited = CountDownLatch(1)
        shell.setInputStream(ByteArrayInputStream("apps\nexit\n".toByteArray()))
        shell.setOutputStream(output)
        shell.setErrorStream(output)
        shell.setExitCallback(object : ExitCallback {
            override fun onExit(exitValue: Int, exitMessage: String?, closeImmediately: Boolean) {
                exited.countDown()
            }
        })

        shell.start(ChannelSession(), ChannelSession().environment)

        assertTrue(exited.await(5, TimeUnit.SECONDS))
        val text = stripAnsi(output.toString())
        assertTrue(
            text,
            Regex("PACKAGE\\s{2,}APP_NAME\\s{2,}VERSION\\s{2,}VERSION_CODE\\s{2,}APK_PATH").containsMatchIn(text) ||
                text.contains("app list failed")
        )
    }

    @Test
    fun appsCanShowSingleAppVersionInfo() {
        val shell = AndroidInteractiveShellFactory(
            shellPath = shellPath(),
            appInfoResolver = { packageName ->
                if (packageName == "com.example.app") {
                    AndroidInteractiveShellFactory.AppInfo(
                        packageName = packageName,
                        appName = "Example App",
                        versionName = "1.2.3",
                        versionCode = "123",
                        apkPath = "/data/app/com.example.app/base.apk",
                        firstInstallTime = "2026-07-01 10:00:00",
                        lastUpdateTime = "2026-07-09 12:00:00",
                    )
                } else {
                    null
                }
            }
        ).createShell(ChannelSession())
        val output = ByteArrayOutputStream()
        val exited = CountDownLatch(1)
        shell.setInputStream(ByteArrayInputStream("apps com.example.app\nexit\n".toByteArray()))
        shell.setOutputStream(output)
        shell.setErrorStream(output)
        shell.setExitCallback(object : ExitCallback {
            override fun onExit(exitValue: Int, exitMessage: String?, closeImmediately: Boolean) {
                exited.countDown()
            }
        })

        shell.start(ChannelSession(), ChannelSession().environment)

        assertTrue(exited.await(5, TimeUnit.SECONDS))
        val text = stripAnsi(output.toString())
        assertTrue(text, Regex("package\\s*: com\\.example\\.app").containsMatchIn(text))
        assertTrue(text, Regex("app_name\\s*: Example App").containsMatchIn(text))
        assertTrue(text, Regex("version_name\\s*: 1\\.2\\.3").containsMatchIn(text))
        assertTrue(text, Regex("version_code\\s*: 123").containsMatchIn(text))
        assertTrue(text, Regex("apk_path\\s*: /data/app/com\\.example\\.app/base\\.apk").containsMatchIn(text))
    }

    @Test
    fun appsOutputDoesNotTruncatePackageName() {
        val packageName = "com.example.very.long.package.name.that.must.remain.visible"
        val shell = AndroidInteractiveShellFactory(
            shellPath = shellPath(),
            appListResolver = {
                listOf(
                    AndroidInteractiveShellFactory.AppInfo(
                        packageName = packageName,
                        appName = "Long Package",
                        versionName = "1.0",
                        versionCode = "1",
                        apkPath = "/data/app/$packageName/base.apk",
                    )
                )
            }
        ).createShell(ChannelSession())
        val output = ByteArrayOutputStream()
        val exited = CountDownLatch(1)
        shell.setInputStream(ByteArrayInputStream("apps\nexit\n".toByteArray()))
        shell.setOutputStream(output)
        shell.setErrorStream(output)
        shell.setExitCallback(object : ExitCallback {
            override fun onExit(exitValue: Int, exitMessage: String?, closeImmediately: Boolean) {
                exited.countDown()
            }
        })

        shell.start(ChannelSession(), ChannelSession().environment)

        assertTrue(exited.await(5, TimeUnit.SECONDS))
        val text = stripAnsi(output.toString())
        assertTrue(text, text.contains(packageName))
        assertFalse(text, text.contains("com.example.very.long.package~"))
    }

    @Test
    fun htopShowsOneShotProcessSnapshotInLinuxStyle() {
        val text = runShell("htop -a -n 5\nexit\n", timeoutSeconds = 3)

        assertTrue(text, text.contains("htop - one-shot snapshot"))
        assertTrue(text, text.contains("Tasks:"))
        assertTrue(text, Regex("PID\\s+USER\\s+PRI\\s+NI\\s+VIRT\\s+RES\\s+SHR\\s+S\\s+CPU%\\s+MEM%\\s+TIME\\+\\s+COMMAND").containsMatchIn(text))
        assertFalse(text, text.contains("htop: inaccessible or not found"))
    }

    @Test
    fun gpuAndNpuCommandsReturnStructuredStatusWhenDeviceNodesAreUnavailable() {
        val text = runShell("gpu\nnpu\nexit\n", timeoutSeconds = 3)

        assertTrue(text, text.contains("GPU"))
        assertTrue(text, text.contains("NPU"))
        assertTrue(text, text.contains("status"))
    }

    @Test
    fun htopUsesSameRunningAppSourceAsRunningApps() {
        val shell = AndroidInteractiveShellFactory(
            shellPath = shellPath(),
            appInfoResolver = { packageName ->
                if (packageName == "com.example.running") {
                    AndroidInteractiveShellFactory.AppInfo(packageName = packageName, appName = "Running App")
                } else {
                    null
                }
            },
            runningAppResolver = {
                listOf(
                    AndroidInteractiveShellFactory.RunningAppInfo(
                        packageName = "com.example.running",
                        pid = "12345",
                        processName = "com.example.running",
                        state = "SERVICE",
                    )
                )
            }
        ).createShell(ChannelSession())
        val output = ByteArrayOutputStream()
        val exited = CountDownLatch(1)
        shell.setInputStream(ByteArrayInputStream("running-apps\nhtop -n 20\nexit\n".toByteArray()))
        shell.setOutputStream(output)
        shell.setErrorStream(output)
        shell.setExitCallback(object : ExitCallback {
            override fun onExit(exitValue: Int, exitMessage: String?, closeImmediately: Boolean) {
                exited.countDown()
            }
        })

        shell.start(ChannelSession(), ChannelSession().environment)

        assertTrue(exited.await(5, TimeUnit.SECONDS))
        val text = stripAnsi(output.toString())
        assertTrue(text, text.contains("com.example.running"))
        assertTrue(text, Regex("Tasks:\\s+1 total").containsMatchIn(text))
    }

    @Test
    fun runningAppsAndHtopSortForegroundU0NonSystemAndSystemUsers() {
        val shell = AndroidInteractiveShellFactory(
            shellPath = shellPath(),
            runningAppResolver = {
                listOf(
                    AndroidInteractiveShellFactory.RunningAppInfo(
                        packageName = "com.example.system",
                        pid = "400",
                        user = "system",
                        processName = "com.example.system",
                        state = "BACKGROUND",
                    ),
                    AndroidInteractiveShellFactory.RunningAppInfo(
                        packageName = "com.example.other",
                        pid = "100",
                        user = "radio",
                        processName = "com.example.other",
                        state = "BACKGROUND",
                    ),
                    AndroidInteractiveShellFactory.RunningAppInfo(
                        packageName = "com.example.u0",
                        pid = "200",
                        user = "u0_a123",
                        processName = "com.example.u0",
                        state = "BACKGROUND",
                    ),
                    AndroidInteractiveShellFactory.RunningAppInfo(
                        packageName = "com.example.foreground",
                        pid = "300",
                        user = "system",
                        processName = "com.example.foreground",
                        state = "FOREGROUND",
                    )
                )
            }
        ).createShell(ChannelSession())
        val output = ByteArrayOutputStream()
        val exited = CountDownLatch(1)
        shell.setInputStream(ByteArrayInputStream("running-apps\nhtop -n 20\nexit\n".toByteArray()))
        shell.setOutputStream(output)
        shell.setErrorStream(output)
        shell.setExitCallback(object : ExitCallback {
            override fun onExit(exitValue: Int, exitMessage: String?, closeImmediately: Boolean) {
                exited.countDown()
            }
        })

        shell.start(ChannelSession(), ChannelSession().environment)

        assertTrue(exited.await(5, TimeUnit.SECONDS))
        val text = stripAnsi(output.toString())
        val foreground = text.indexOf("com.example.foreground")
        val u0 = text.indexOf("com.example.u0")
        val other = text.indexOf("com.example.other")
        val system = text.indexOf("com.example.system")
        assertTrue(text, foreground >= 0 && u0 >= 0 && other >= 0 && system >= 0)
        assertTrue(text, foreground < u0)
        assertTrue(text, u0 < other)
        assertTrue(text, other < system)
    }

    @Test
    fun downloadCopiesUrlToDestinationWithoutCurlOrWget() {
        val dir = createTempDir(prefix = "ssh-download-test")
        val source = File(dir, "source.txt")
        val destination = File(dir, "nested/destination.txt")
        source.writeText("download body")

        try {
            val text = runShell("download ${source.toURI()} ${destination.absolutePath}\nexit\n")

            assertTrue(text, text.contains("ok path=") && text.contains("bytes="))
            assertTrue(text, text.contains("PROGRESS") && text.contains("100%"))
            assertTrue(destination.readText() == "download body")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun downloadToDirectoryUsesUrlFileName() {
        val dir = createTempDir(prefix = "ssh-download-dir-test")
        val source = File(dir, "source.apk")
        val destinationDir = File(dir, "downloads")
        source.writeText("apk body")

        try {
            val text = runShell("download ${source.toURI()} ${destinationDir.absolutePath}${File.separator}\nexit\n")
            val destination = File(destinationDir, "source.apk")

            assertTrue(text, text.contains("source.apk"))
            assertTrue(destination.readText() == "apk body")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun downloadCanBeInterruptedWithCtrlC() {
        val dir = createTempDir(prefix = "ssh-download-interrupt-test")
        val source = File(dir, "source.bin")
        val destination = File(dir, "destination.bin")
        source.writeBytes(ByteArray(512 * 1024) { 7 })

        try {
            val text = runShellWithInput(
                SlowInputStream("download ${source.toURI()} ${destination.absolutePath}\n\u0003exit\n".toByteArray())
            )

            assertTrue(text, text.contains("^C"))
            assertFalse(destination.exists() && destination.length() == source.length())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun copyCanBeInterruptedWithCtrlC() {
        val dir = createTempDir(prefix = "ssh-copy-interrupt-test")
        val source = File(dir, "source.bin")
        val destination = File(dir, "destination.bin")
        source.writeBytes(ByteArray(512 * 1024) { 9 })

        try {
            val text = runShellWithInput(
                SlowInputStream("cp ${source.absolutePath} ${destination.absolutePath}\n\u0003exit\n".toByteArray())
            )

            assertTrue(text, text.contains("^C"))
            assertFalse(destination.exists() && destination.length() == source.length())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun createFileCreatesEmptyAndContentFiles() {
        val dir = createTempDir(prefix = "ssh-create-file-test")
        val empty = File(dir, "empty.txt")
        val content = File(dir, "content.txt")

        try {
            val text = runShell(
                "create-file ${empty.absolutePath}\n" +
                    "mkfile ${content.absolutePath} hello-world\n" +
                    "exit\n"
            )

            assertTrue(text, text.contains("ok path=") && text.contains("bytes="))
            assertTrue(empty.exists())
            assertTrue(content.readText() == "hello-world")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rmDeletesFilesAndDirectoriesWithOptions() {
        val dir = createTempDir(prefix = "ssh-rm-test")
        val file = File(dir, "file.txt")
        val recursiveDir = File(dir, "recursive")
        val forceDir = File(dir, "force")
        file.writeText("delete")
        File(recursiveDir, "child.txt").also {
            it.parentFile?.mkdirs()
            it.writeText("delete")
        }
        File(forceDir, "child.txt").also {
            it.parentFile?.mkdirs()
            it.writeText("delete")
        }

        try {
            val text = runShell(
                "rm ${file.absolutePath}\n" +
                    "rm -r ${recursiveDir.absolutePath}\n" +
                    "rm -rf ${forceDir.absolutePath}\n" +
                    "exit\n"
            )

            assertTrue(text, text.contains("ok"))
            assertFalse(file.exists())
            assertFalse(recursiveDir.exists())
            assertFalse(forceDir.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun listLikeCommandResultsUseTableOutput() {
        val command = if (File("/bin/sh").exists()) {
            "printf 'alpha beta\\ngamma delta\\n'"
        } else {
            "Write-Output 'alpha beta'; Write-Output 'gamma delta'"
        }
        val text = runShell("$command\nexit\n")

        assertTrue(text, Regex("C1\\s{2,}C2").containsMatchIn(text))
        assertTrue(text, Regex("alpha\\s{2,}beta").containsMatchIn(text))
        assertTrue(text, Regex("gamma\\s{2,}delta").containsMatchIn(text))
    }

    @Test
    fun genericCommandMultiplePropertiesUseTableOutput() {
        val command = if (File("/bin/sh").exists()) {
            "printf 'name=device\\nstatus=online\\n'"
        } else {
            "Write-Output 'name=device'; Write-Output 'status=online'"
        }
        val text = runShell("$command\nexit\n")

        assertTrue(text, Regex("name\\s*: device").containsMatchIn(text))
        assertTrue(text, Regex("status\\s*: online").containsMatchIn(text))
    }

    private fun runShell(
        commands: String,
        promptUser: String = "android",
        promptHost: String = "android",
        timeoutSeconds: Long = 5,
    ): String {
        return stripAnsi(runShellRaw(commands, promptUser, promptHost, timeoutSeconds))
    }

    private fun runShellRaw(
        commands: String,
        promptUser: String = "android",
        promptHost: String = "android",
        timeoutSeconds: Long = 5,
    ): String {
        val shell = AndroidInteractiveShellFactory(
            shellPath = shellPath(),
            promptUser = promptUser,
            promptHost = promptHost,
        ).createShell(ChannelSession())
        val output = ByteArrayOutputStream()
        val exited = CountDownLatch(1)
        shell.setInputStream(ByteArrayInputStream(commands.toByteArray()))
        shell.setOutputStream(output)
        shell.setErrorStream(output)
        shell.setExitCallback(object : ExitCallback {
            override fun onExit(exitValue: Int, exitMessage: String?, closeImmediately: Boolean) {
                exited.countDown()
            }
        })
        shell.start(ChannelSession(), ChannelSession().environment)
        assertTrue(exited.await(timeoutSeconds, TimeUnit.SECONDS))
        return output.toString()
    }

    private fun runShellWithFactory(commands: String, factory: AndroidInteractiveShellFactory): String {
        val shell = factory.createShell(ChannelSession())
        val output = ByteArrayOutputStream()
        val exited = CountDownLatch(1)
        shell.setInputStream(ByteArrayInputStream(commands.toByteArray()))
        shell.setOutputStream(output)
        shell.setErrorStream(output)
        shell.setExitCallback(object : ExitCallback {
            override fun onExit(exitValue: Int, exitMessage: String?, closeImmediately: Boolean) {
                exited.countDown()
            }
        })
        shell.start(ChannelSession(), ChannelSession().environment)
        assertTrue(exited.await(5, TimeUnit.SECONDS))
        return stripAnsi(output.toString())
    }

    private fun runShellWithInput(input: InputStream, timeoutSeconds: Long = 5): String {
        val shell = AndroidInteractiveShellFactory(
            shellPath = shellPath(),
        ).createShell(ChannelSession())
        val output = ByteArrayOutputStream()
        val exited = CountDownLatch(1)
        shell.setInputStream(input)
        shell.setOutputStream(output)
        shell.setErrorStream(output)
        shell.setExitCallback(object : ExitCallback {
            override fun onExit(exitValue: Int, exitMessage: String?, closeImmediately: Boolean) {
                exited.countDown()
            }
        })
        shell.start(ChannelSession(), ChannelSession().environment)
        assertTrue(exited.await(timeoutSeconds, TimeUnit.SECONDS))
        return stripAnsi(output.toString())
    }

    private fun stripAnsi(text: String): String {
        return Regex("""\u001B\[[0-9;]*m""").replace(text, "")
    }

    private fun shellPath(): String {
        return if (File("/bin/sh").exists()) "/bin/sh" else "powershell.exe"
    }

    private fun multiLineCommand(): String {
        return if (File("/bin/sh").exists()) {
            "printf 'first\\nsecond\\n'"
        } else {
            "Write-Output first; Write-Output second"
        }
    }

    private class SlowInputStream(
        private val data: ByteArray,
        private val pauseAfterLineFeedMillis: Long = 50L,
    ) : InputStream() {
        private var index = 0

        override fun read(): Int {
            if (index >= data.size) return -1
            val value = data[index++].toInt() and 0xff
            if (value == '\n'.code) {
                Thread.sleep(pauseAfterLineFeedMillis)
            }
            return value
        }

        override fun available(): Int {
            return data.size - index
        }
    }

    private class ClosedOutputStream : OutputStream() {
        override fun write(b: Int) {
            throw IOException("channel already closed")
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            throw IOException("channel already closed")
        }

        override fun flush() {
            throw IOException("channel already closed")
        }
    }
}
