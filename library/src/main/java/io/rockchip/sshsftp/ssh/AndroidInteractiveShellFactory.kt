package io.rockchip.sshsftp.ssh

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.shell.ShellFactory
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #文件下载
 * download https://xxxx xxx.apk
 *
 * #拉取设备文件到本�?
 * scp -O -P 2222 -r BSD874EF28FA48@192.168.15.109:/sdcard/HandHygiene/db E:\db
 *
 * #拷贝本地文件到设�?
 * scp -O -P 2222 -r E:\novel.zip BSD874EF28FA48@192.168.15.109:/sdcard/
 */
class AndroidInteractiveShellFactory(
    private val shellPath: String = DEFAULT_SHELL,
    private val promptUser: String = DEFAULT_PROMPT_USER,
    private val promptHost: String = DEFAULT_PROMPT_HOST,
    private val appNameResolver: (String) -> String? = { null },
    private val appInfoResolver: (String) -> AppInfo? = { packageName ->
        appNameResolver(packageName)?.let { appName -> AppInfo(packageName = packageName, appName = appName) }
    },
    private val appListResolver: () -> List<AppInfo> = { emptyList() },
    private val appLaunchActivityResolver: (String) -> String? = { null },
    private val appStartResolver: (String, String?) -> AppStartResult? = { _, _ -> null },
    private val runningAppResolver: () -> List<RunningAppInfo> = { emptyList() },
    private val cameraResolver: () -> List<CameraInfo> = { emptyList() },
    private val volumeResolver: () -> List<VolumeInfo> = { emptyList() },
    private val initialDirectory: File = File("/"),
) : ShellFactory {

    override fun createShell(channel: ChannelSession): Command {
        return AndroidInteractiveShell(shellPath, promptUser, promptHost, appInfoResolver, appListResolver, appLaunchActivityResolver, appStartResolver, runningAppResolver, cameraResolver, volumeResolver, initialDirectory)
    }

    data class AppInfo(
        val packageName: String,
        val appName: String = "-",
        val versionName: String = "-",
        val versionCode: String = "-",
        val apkPath: String = "-",
        val firstInstallTime: String = "-",
        val lastUpdateTime: String = "-",
    )

    data class AppStartResult(
        val success: Boolean,
        val message: String = "",
    )

    data class RunningAppInfo(
        val packageName: String,
        val pid: String = "",
        val user: String = "",
        val processName: String = "",
        val state: String = "BACKGROUND",
        val source: String = "activity-manager",
    )

    data class CameraInfo(
        val id: String,
        val facing: String = "-",
        val orientation: String = "-",
        val hardwareLevel: String = "-",
        val flash: String = "-",
        val autofocus: String = "-",
        val fpsRanges: String = "-",
        val photoSizes: String = "-",
        val videoSizes: String = "-",
        val capabilities: String = "-",
    )

    data class VolumeInfo(
        val stream: String,
        val min: String = "-",
        val current: String = "-",
        val max: String = "-",
        val muted: String = "-",
    )

    private class AndroidInteractiveShell(
        private val shellPath: String,
        private val promptUser: String,
        private val promptHost: String,
        private val appInfoResolver: (String) -> AppInfo?,
        private val appListResolver: () -> List<AppInfo>,
        private val appLaunchActivityResolver: (String) -> String?,
        private val appStartResolver: (String, String?) -> AppStartResult?,
        private val runningAppResolver: () -> List<RunningAppInfo>,
        private val cameraResolver: () -> List<CameraInfo>,
        private val volumeResolver: () -> List<VolumeInfo>,
        initialDirectory: File,
    ) : Command {
        private val running = AtomicBoolean(false)
        private val executor: ExecutorService = Executors.newSingleThreadExecutor()
        private var input: InputStream? = null
        private var output: OutputStream? = null
        private var error: OutputStream? = null
        private var callback: ExitCallback? = null
        private var cwd: File = initialDirectory.takeIf { it.isDirectory } ?: File("/")
        private val commandRunner = RemoteCommandRunner(shellPath, { cwd }, ::consumeCtrlC)
        private var sqliteDb: File? = null
        private var ignoreNextLf = false
        private val pendingInput = ArrayDeque<Int>()
        private val history = mutableListOf<String>()
        private var historyIndex = 0

        override fun setInputStream(input: InputStream) {
            this.input = input
        }

        override fun setOutputStream(output: OutputStream) {
            this.output = output
        }

        override fun setErrorStream(error: OutputStream) {
            this.error = error
        }

        override fun setExitCallback(callback: ExitCallback) {
            this.callback = callback
        }

        override fun start(channel: ChannelSession, env: Environment) {
            running.set(true)
            executor.execute {
                try {
                    write(BRACKETED_PASTE_ENABLE)
                    write("Welcome to Android remote shell\r\n")
                    writePrompt()
                    val reader = EchoingLineReader(input ?: return@execute)
                    while (running.get()) {
                        val line = reader.readLine() ?: break
                        val command = line.trim()
                        addHistory(command)
                        if (!handleLine(command, reader)) break
                        writePrompt()
                    }
                    callback?.onExit(0)
                } catch (e: Throwable) {
                    writeError("${e.message ?: e.javaClass.simpleName}\r\n")
                    callback?.onExit(1, e.message)
                } finally {
                    safeWrite(BRACKETED_PASTE_DISABLE)
                    running.set(false)
                }
            }
        }

        override fun destroy(channel: ChannelSession) {
            safeWrite(BRACKETED_PASTE_DISABLE)
            running.set(false)
            executor.shutdownNow()
        }

        private fun addHistory(command: String) {
            if (command.isBlank() || command == "\u0003") return
            if (history.lastOrNull() != command) {
                history += command
                if (history.size > HISTORY_LIMIT) {
                    history.removeAt(0)
                }
            }
            historyIndex = history.size
        }

        private fun handleLine(line: String, reader: LineReader): Boolean {
            try {
                return handleLineInterruptibly(line, reader)
            } catch (_: RemoteCommandInterrupted) {
                write("^C\r\n")
                return true
            } catch (_: InterruptedException) {
                Thread.interrupted()
                write("^C\r\n")
                return true
            }
        }

        private fun handleLineInterruptibly(line: String, reader: LineReader): Boolean {
            sqliteDb?.let { db ->
                handleSqliteLine(db, line)
                return true
            }
            if (line == "\u0003") return true
            if (line.isBlank()) return true
            val lineArgs = splitArgs(line)
            if (lineArgs.firstOrNull() == "help" || lineArgs.firstOrNull() == "helpe") {
                writeHelp(lineArgs.getOrNull(1))
                return true
            }
            when (line) {
                "exit", "logout" -> return false
                "pwd" -> {
                    write("${cwd.absolutePath}\r\n")
                    return true
                }
                "clear" -> {
                    write("\u001B[2J\u001B[H")
                    return true
                }
            }
            if (handleBuiltinCommand(line, reader)) return true
            if (line == "cd" || line.startsWith("cd ")) {
                changeDirectory(line.removePrefix("cd").trim())
                return true
            }

            executeCommand(line)
            return true
        }

        private fun builtinCommands(): List<String> {
            return listOf(
                "help",
                "helpe",
                "pwd",
                "cd",
                "clear",
                "exit",
                "logout",
                "ls",
                "cat",
                "find-files",
                "find-file",
                "search-files",
                "rm",
                "mkdir",
                "cp",
                "mv",
                "touch",
                "create-file",
                "mkfile",
                "zip",
                "unzip",
                "ip",
                "ifconfig",
                "wifi-connect",
                "wifi-disconnect",
                "wifi-set",
                "lan-connect",
                "lan-disconnect",
                "lan-set",
                "ping",
                "mem",
                "cpu",
                "apps",
                "running-apps",
                "ps-apps",
                "htop",
                "logs",
                "logcat",
                "start-app",
                "kill-app",
                "install-apk",
                "uninstall-apk",
                "download",
                "screenshot",
                "hardware",
                "hw",
                "gpu",
                "npu",
                "wifi-info",
                "usb",
                "usb-info",
                "cameras",
                "camera-info",
                "volume",
                "brightness",
                "system-time",
                "set-system-time",
                "reboot",
                "sqlite",
                "sqlite-dbs",
                "sqlite-create-db",
                "sqlite-delete-db",
                "sqlite-rename-db",
                "sqlite-tables",
                "sqlite-schema",
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
            )
        }

        private fun handleBuiltinCommand(line: String, reader: LineReader): Boolean {
            val args = splitArgs(line)
            if (args.isEmpty()) return true
            when (args[0]) {
                "ls" -> {
                    listFiles(args.drop(1))
                    return true
                }
                "cat" -> {
                    readFile(args.getOrNull(1))
                    return true
                }
                "find-files", "find-file", "search-files" -> {
                    findFiles(args.drop(1))
                    return true
                }
                "rm" -> {
                    removeFile(args.drop(1))
                    return true
                }
                "mkdir" -> {
                    makeDirectory(args.getOrNull(1))
                    return true
                }
                "cp" -> {
                    copyFile(args.getOrNull(1), args.getOrNull(2))
                    return true
                }
                "mv" -> {
                    moveFile(args.getOrNull(1), args.getOrNull(2))
                    return true
                }
                "touch" -> {
                    touchFile(args.getOrNull(1))
                    return true
                }
                "create-file", "mkfile" -> {
                    createFile(args.getOrNull(1), args.drop(2).joinToString(" "))
                    return true
                }
                "zip" -> {
                    zipFiles(args.drop(1))
                    return true
                }
                "unzip" -> {
                    unzipFile(args.drop(1))
                    return true
                }
                "ip", "ifconfig" -> {
                    queryIp()
                    return true
                }
                "wifi-connect" -> {
                    wifiConnect(args.drop(1))
                    return true
                }
                "wifi-disconnect" -> {
                    wifiDisconnect()
                    return true
                }
                "wifi-set" -> {
                    wifiSet(args.drop(1))
                    return true
                }
                "lan-connect" -> {
                    lanSet(args.drop(1), connectOnly = true)
                    return true
                }
                "lan-disconnect" -> {
                    lanDisconnect(args.getOrNull(1))
                    return true
                }
                "lan-set" -> {
                    lanSet(args.drop(1), connectOnly = false)
                    return true
                }
                "ping" -> {
                    pingHost(args.drop(1))
                    return true
                }
                "mem" -> {
                    queryMemory(args.getOrNull(1))
                    return true
                }
                "cpu" -> {
                    queryCpu(args.getOrNull(1))
                    return true
                }
                "apps" -> {
                    listApps(args.getOrNull(1))
                    return true
                }
                "running-apps", "ps-apps" -> {
                    listRunningApps()
                    return true
                }
                "htop" -> {
                    htopSnapshot(args.drop(1))
                    return true
                }
                "logs", "logcat" -> {
                    streamLogs(args, reader)
                    return true
                }
                "start-app" -> {
                    startApp(args.getOrNull(1), args.getOrNull(2))
                    return true
                }
                "kill-app" -> {
                    killApp(args.getOrNull(1))
                    return true
                }
                "install-apk" -> {
                    installApk(args.getOrNull(1))
                    return true
                }
                "uninstall-apk" -> {
                    uninstallApk(args.getOrNull(1))
                    return true
                }
                "download" -> {
                    downloadFile(args.getOrNull(1), args.getOrNull(2))
                    return true
                }
                "screenshot" -> {
                    screenshot(args.getOrNull(1))
                    return true
                }
                "hardware", "hw" -> {
                    queryHardware()
                    return true
                }
                "gpu" -> {
                    queryGpuInfo()
                    return true
                }
                "npu" -> {
                    queryNpuInfo()
                    return true
                }
                "wifi-info" -> {
                    queryWifiInfo()
                    return true
                }
                "usb", "usb-info" -> {
                    queryUsbInfo()
                    return true
                }
                "cameras" -> {
                    queryCameras()
                    return true
                }
                "camera-info" -> {
                    queryCameras(args.getOrNull(1))
                    return true
                }
                "volume" -> {
                    queryVolume()
                    return true
                }
                "brightness" -> {
                    queryBrightness()
                    return true
                }
                "system-time" -> {
                    querySystemTime()
                    return true
                }
                "set-system-time" -> {
                    setSystemTime(args.drop(1).joinToString(" "))
                    return true
                }
                "reboot" -> {
                    rebootDevice()
                    return true
                }
                "sqlite" -> {
                    sqlite(args.drop(1))
                    return true
                }
                "sqlite-dbs" -> {
                    sqliteListDbs(args.getOrNull(1))
                    return true
                }
                "sqlite-create-db" -> {
                    sqliteCreateDb(args.getOrNull(1))
                    return true
                }
                "sqlite-delete-db" -> {
                    sqliteDeleteDb(args.getOrNull(1))
                    return true
                }
                "sqlite-rename-db" -> {
                    sqliteRenameDb(args.getOrNull(1), args.getOrNull(2))
                    return true
                }
                "sqlite-tables" -> {
                    val db = args.getOrNull(1)?.let(::resolvePath) ?: defaultSqliteDb() ?: return true
                    sqliteQuery(db, ".tables")
                    return true
                }
                "sqlite-schema" -> {
                    val first = args.getOrNull(1)
                    val firstPath = first?.let(::resolvePath)
                    val db = if (firstPath?.exists() == true) firstPath else defaultSqliteDb() ?: return true
                    val table = if (firstPath?.exists() == true) args.getOrNull(2) else first
                    sqliteQuery(db, if (table.isNullOrBlank()) ".schema" else ".schema $table")
                    return true
                }
                "sqlite-create-table" -> {
                    sqliteCreateTable(args.drop(1))
                    return true
                }
                "sqlite-drop-table" -> {
                    sqliteDropTable(args.drop(1))
                    return true
                }
                "sqlite-rename-table" -> {
                    sqliteRenameTable(args.drop(1))
                    return true
                }
                "sqlite-table" -> {
                    sqliteQueryTable(args.drop(1))
                    return true
                }
                "sqlite-columns" -> {
                    sqliteColumns(args.drop(1))
                    return true
                }
                "sqlite-add-column" -> {
                    sqliteAddColumn(args.drop(1))
                    return true
                }
                "sqlite-drop-column" -> {
                    sqliteDropColumn(args.drop(1))
                    return true
                }
                "sqlite-rename-column" -> {
                    sqliteRenameColumn(args.drop(1))
                    return true
                }
                "sqlite-modify-column" -> {
                    sqliteModifyColumn(args.drop(1))
                    return true
                }
                "sqlite-version" -> {
                    sqliteVersion(args.drop(1))
                    return true
                }
                "sqlite-set-version" -> {
                    sqliteSetVersion(args.drop(1))
                    return true
                }
            }
            return false
        }

        private fun changeDirectory(path: String) {
            val requestedPath = path.ifBlank { "/" }
            if (requestedPath.startsWith("/") && isDeniedSftpPath(requestedPath)) {
                write("cd: $requestedPath: Permission denied\r\n")
                return
            }
            val target = when {
                path.isBlank() -> File("/")
                path.startsWith("/") -> File(path)
                else -> File(cwd, path)
            }.normalize()

            when {
                target.isDirectory && target.canRead() && target.canExecute() ->
                    cwd = target
                target.exists() ->
                    write("cd: ${target.path}: Permission denied\r\n")
                else ->
                    write("cd: ${target.path}: No such directory\r\n")
            }
        }

        private fun executeCommand(command: String) {
            try {
                val process = ProcessBuilder(shellPath, "-c", command)
                    .directory(cwd)
                    .redirectErrorStream(true)
                    .start()
                val outputBuffer = ByteArrayOutputStream()
                val outputThread = Thread {
                    process.inputStream.use { source ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = source.read(buffer)
                            if (count == -1) break
                            if (count > 0) {
                                outputBuffer.write(buffer, 0, count)
                            }
                        }
                    }
                }
                outputThread.name = "ssh-command-output"
                outputThread.isDaemon = true
                outputThread.start()

                val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(COMMAND_TIMEOUT_SECONDS)
                var interruptedByCtrlC = false
                var timedOut = false
                while (process.isAlive) {
                    if (consumeCtrlC()) {
                        interruptedByCtrlC = true
                        process.destroy()
                        if (!process.waitFor(1, TimeUnit.SECONDS)) {
                            process.destroyForcibly()
                        }
                        break
                    }
                    if (System.currentTimeMillis() >= deadline) {
                        timedOut = true
                        process.destroyForcibly()
                        break
                    }
                    Thread.sleep(COMMAND_POLL_INTERVAL_MILLIS)
                }
                outputThread.join(1_000)
                if (timedOut) {
                    write("command timeout\r\n")
                    return
                }
                if (interruptedByCtrlC) {
                    write("^C\r\n")
                    return
                }
                val result = outputBuffer.toString(StandardCharsets.UTF_8.name())
                val exit = process.exitValue()
                writeCommandResult(result)
                if (exit != 0) {
                    write("[exit $exit]\r\n")
                }
            } catch (e: IOException) {
                write("${e.message ?: "command failed"}\r\n")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                write("interrupted\r\n")
            }
        }

        private fun consumeCtrlC(): Boolean {
            val source = input ?: return false
            while (source.available() > 0) {
                val next = source.read()
                if (next == 3) return true
                if (next >= 0) pendingInput.addLast(next)
            }
            return false
        }

        private fun readInputByte(source: InputStream): Int =
            if (pendingInput.isEmpty()) source.read() else pendingInput.removeFirst()

        private fun listFiles(args: List<String>) {
            val showAll = args.any { option -> option.startsWith("-") && option.contains("a") }
            val longFormat = args.any { option -> option.startsWith("-") && option.contains("l") }
            val path = args.firstOrNull { !it.startsWith("-") }
            val target = resolvePath(path ?: ".")
            if (!target.exists()) {
                write("ls: ${target.path}: No such file or directory\r\n")
                return
            }
            if (target.isFile) {
                if (longFormat) {
                    writeLongFileList(listOf(target))
                } else {
                    writeColumns(listOf(coloredFileName(target)))
                }
                return
            }
            val files = target.listFiles()
                ?.asSequence()
                ?.filter { showAll || !it.name.startsWith(".") }
                ?.sortedBy { it.name }
                ?.toList()
                .orEmpty()
            if (longFormat) {
                writeLongFileList(files)
            } else {
                writeColumns(files.map(::coloredFileName))
            }
        }

        private fun writeLongFileList(files: List<File>) {
            val total = files.filter { it.isFile }.sumOf { it.length() / 1024L }
            write("total $total\r\n")
            files.forEach { file ->
                val row = fileRow(file)
                write(
                    String.format(
                        Locale.US,
                        "%s %10s %s %s\r\n",
                        row.getOrElse(0) { "" },
                        row.getOrElse(1) { "" },
                        row.getOrElse(2) { "" },
                        row.getOrElse(3) { "" },
                    )
                )
            }
        }

        private fun fileRow(file: File): List<String> {
            val permissions = buildString {
                append(if (file.isDirectory) 'd' else '-')
                append(if (file.canRead()) 'r' else '-')
                append(if (file.canWrite()) 'w' else '-')
                append(if (file.canExecute()) 'x' else '-')
            }
            val size = if (file.isDirectory) "-" else file.length().toString()
            val modified = DATE_FORMAT.get()!!.format(Date(file.lastModified()))
            return listOf(colorPermissions(permissions), colorSize(size), colorMuted(modified), coloredFileName(file))
        }

        private fun displayFileName(file: File): String {
            return if (file.isDirectory) "${file.name}/" else file.name
        }

        private fun coloredFileName(file: File): String {
            val name = displayFileName(file)
            return when {
                file.name.startsWith(".") -> colorHidden(name)
                file.isDirectory -> colorDirectory(name)
                file.canExecute() -> colorExecutable(name)
                else -> colorFile(name)
            }
        }

        private fun readFile(path: String?) {
            val target = resolveRequiredPath(path, "cat") ?: return
            if (!target.isFile) {
                write("cat: ${target.path}: not a file\r\n")
                return
            }
            writeCommandResult(target.readText())
        }

        private data class FindOptions(
            val root: File,
            val namePattern: String? = null,
            val extension: String? = null,
            val type: String = "all",
            val maxDepth: Int = 8,
            val limit: Int = 200,
            val minSize: Long? = null,
            val maxSize: Long? = null,
            val modifiedWithinDays: Long? = null,
        )

        private fun findFiles(args: List<String>) {
            val options = parseFindOptions(args) ?: return
            if (!options.root.exists()) {
                write("find-files: ${options.root.absolutePath}: No such file or directory\r\n")
                return
            }
            val rows = mutableListOf<List<String>>()
            val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(COMMAND_TIMEOUT_SECONDS)
            if (options.root.isDirectory) {
                val children = runCatching { options.root.listFiles()?.sortedBy { it.name }.orEmpty() }.getOrDefault(emptyList())
                children.forEach { child ->
                    walkFind(child, depth = 0, options, rows, deadline)
                }
            } else {
                walkFind(options.root, depth = 0, options, rows, deadline)
            }
            if (rows.isEmpty()) {
                write("empty\r\n")
            } else {
                writeTable(listOf("TYPE", "SIZE", "MODIFIED", "PATH"), rows)
            }
        }

        private fun parseFindOptions(args: List<String>): FindOptions? {
            var index = 0
            var root: File? = null
            var namePattern: String? = null
            var extension: String? = null
            var type = "all"
            var maxDepth = 8
            var limit = 200
            var minSize: Long? = null
            var maxSize: Long? = null
            var modifiedWithinDays: Long? = null

            while (index < args.size) {
                when (val arg = args[index]) {
                    "--name", "-name" -> {
                        namePattern = args.getOrNull(index + 1)
                        index += 2
                    }
                    "--ext", "-ext" -> {
                        extension = args.getOrNull(index + 1)?.trimStart('.')?.lowercase(Locale.US)
                        index += 2
                    }
                    "--type", "-type" -> {
                        type = args.getOrNull(index + 1)?.lowercase(Locale.US).orEmpty()
                        index += 2
                    }
                    "--max-depth", "-maxdepth" -> {
                        maxDepth = args.getOrNull(index + 1)?.toIntOrNull()?.coerceAtLeast(0) ?: maxDepth
                        index += 2
                    }
                    "--limit" -> {
                        limit = args.getOrNull(index + 1)?.toIntOrNull()?.coerceIn(1, 10_000) ?: limit
                        index += 2
                    }
                    "--min-size" -> {
                        minSize = args.getOrNull(index + 1)?.let(::parseSizeBytes)
                        index += 2
                    }
                    "--max-size" -> {
                        maxSize = args.getOrNull(index + 1)?.let(::parseSizeBytes)
                        index += 2
                    }
                    "--mtime-days" -> {
                        modifiedWithinDays = args.getOrNull(index + 1)?.toLongOrNull()
                        index += 2
                    }
                    "--help", "-h" -> {
                        write("usage: find-files [path] [--name PATTERN] [--ext EXT] [--type file|dir] [--max-depth N] [--limit N] [--min-size SIZE] [--max-size SIZE] [--mtime-days DAYS]\r\n")
                        return null
                    }
                    else -> {
                        if (arg.startsWith("-")) {
                            write("find-files: unknown option $arg\r\n")
                            return null
                        }
                        if (root == null) root = resolvePath(arg)
                        index++
                    }
                }
            }
            if (type !in setOf("all", "file", "dir", "directory")) {
                write("find-files: --type must be file or dir\r\n")
                return null
            }
            return FindOptions(
                root = root ?: cwd,
                namePattern = namePattern,
                extension = extension,
                type = type,
                maxDepth = maxDepth,
                limit = limit,
                minSize = minSize,
                maxSize = maxSize,
                modifiedWithinDays = modifiedWithinDays,
            )
        }

        private fun walkFind(
            file: File,
            depth: Int,
            options: FindOptions,
            rows: MutableList<List<String>>,
            deadline: Long,
        ) {
            if (rows.size >= options.limit || System.currentTimeMillis() > deadline) return
            if (matchesFind(file, options)) {
                rows += listOf(
                    if (file.isDirectory) "dir" else "file",
                    if (file.isFile) file.length().toString() else "-",
                    DATE_FORMAT.get()!!.format(Date(file.lastModified())),
                    file.absolutePath,
                )
            }
            if (!file.isDirectory || depth >= options.maxDepth) return
            val children = runCatching { file.listFiles()?.sortedBy { it.name }.orEmpty() }.getOrDefault(emptyList())
            for (child in children) {
                walkFind(child, depth + 1, options, rows, deadline)
                if (rows.size >= options.limit || System.currentTimeMillis() > deadline) return
            }
        }

        private fun matchesFind(file: File, options: FindOptions): Boolean {
            if (options.type == "file" && !file.isFile) return false
            if ((options.type == "dir" || options.type == "directory") && !file.isDirectory) return false
            options.namePattern?.let { pattern ->
                if (!wildcardMatches(pattern, file.name)) return false
            }
            options.extension?.let { ext ->
                if (!file.isFile || file.extension.lowercase(Locale.US) != ext) return false
            }
            options.minSize?.let { min ->
                if (!file.isFile || file.length() < min) return false
            }
            options.maxSize?.let { max ->
                if (!file.isFile || file.length() > max) return false
            }
            options.modifiedWithinDays?.let { days ->
                val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days)
                if (file.lastModified() < since) return false
            }
            return true
        }

        private fun wildcardMatches(pattern: String, value: String): Boolean {
            val regex = pattern
                .split('*')
                .joinToString(".*") { Regex.escape(it) }
                .replace("?", ".")
            return Regex("^$regex$", RegexOption.IGNORE_CASE).matches(value)
        }

        private fun parseSizeBytes(value: String): Long? {
            val match = Regex("""^(\d+)([kKmMgG]?[bB]?)?$""").matchEntire(value.trim()) ?: return null
            val number = match.groupValues[1].toLongOrNull() ?: return null
            return when (match.groupValues[2].lowercase(Locale.US).trimEnd('b')) {
                "k" -> number * 1024L
                "m" -> number * 1024L * 1024L
                "g" -> number * 1024L * 1024L * 1024L
                else -> number
            }
        }

        private fun removeFile(args: List<String>) {
            val recursive = args.any { option -> option.startsWith("-") && option.contains("r") }
            val force = args.any { option -> option.startsWith("-") && option.contains("f") }
            val path = args.firstOrNull { !it.startsWith("-") }
            val target = resolveRequiredPath(path, "rm") ?: return
            if (!target.exists()) {
                if (force) {
                    write("ok\r\n")
                } else {
                    write("rm: ${target.path}: No such file or directory\r\n")
                }
                return
            }
            if (target.isDirectory && !recursive) {
                write("rm: ${target.path}: is a directory; use rm -r\r\n")
                return
            }
            val ok = if (target.isDirectory) {
                target.deleteRecursively()
            } else {
                target.delete()
            }
            if (ok || !target.exists()) {
                write("ok\r\n")
            } else {
                write("rm: failed: ${target.path}\r\n")
            }
        }

        private fun makeDirectory(path: String?) {
            val target = resolveRequiredPath(path, "mkdir") ?: return
            write(if (target.mkdirs() || target.isDirectory) "ok\r\n" else "mkdir: failed: ${target.path}\r\n")
        }

        private fun copyFile(source: String?, destination: String?) {
            val src = resolveRequiredPath(source, "cp") ?: return
            val dst = resolveRequiredPath(destination, "cp") ?: return
            try {
                if (!src.isFile) {
                    write("cp: ${src.path}: not a file\r\n")
                    return
                }
                dst.parentFile?.mkdirs()
                src.inputStream().use { input ->
                    dst.outputStream().use { output ->
                        copyInterruptibly(input, output, src.length()) { copied, total ->
                            writeCopyProgress("COPY_PROGRESS", copied, total, final = false)
                        }
                    }
                }
                writeCopyProgress("COPY_PROGRESS", src.length(), src.length(), final = true)
                write("ok\r\n")
            } catch (_: TransferInterruptedException) {
                dst.delete()
                write("^C\r\n")
            } catch (e: IOException) {
                write("cp: ${e.message ?: "failed"}\r\n")
            }
        }

        private fun moveFile(source: String?, destination: String?) {
            val src = resolveRequiredPath(source, "mv") ?: return
            val dst = resolveRequiredPath(destination, "mv") ?: return
            try {
                dst.parentFile?.mkdirs()
                if (dst.exists() && !dst.deleteRecursively()) {
                    write("mv: failed to replace: ${dst.path}\r\n")
                    return
                }
                val renamed = src.renameTo(dst)
                if (!renamed) {
                    if (src.isFile) {
                        src.inputStream().use { input ->
                            dst.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (!src.delete()) {
                            write("mv: copied but failed to delete source: ${src.path}\r\n")
                            return
                        }
                    } else {
                        write("mv: failed: ${src.path}\r\n")
                        return
                    }
                }
                write("ok\r\n")
            } catch (e: IOException) {
                write("mv: ${e.message ?: "failed"}\r\n")
            }
        }

        private fun touchFile(path: String?) {
            val target = resolveRequiredPath(path, "touch") ?: return
            try {
                if (!target.exists()) {
                    target.parentFile?.mkdirs()
                    target.createNewFile()
                }
                target.setLastModified(System.currentTimeMillis())
                write("ok\r\n")
            } catch (e: IOException) {
                write("touch: ${e.message ?: "failed"}\r\n")
            }
        }

        private fun createFile(path: String?, content: String) {
            val target = resolveRequiredPath(path, "create-file") ?: return
            try {
                target.parentFile?.mkdirs()
                if (content.isBlank()) {
                    if (!target.exists()) {
                        target.createNewFile()
                    }
                } else {
                    target.writeText(content)
                }
                writeTable(
                    listOf("STATUS", "PATH", "BYTES"),
                    listOf(listOf("ok", target.absolutePath, target.length().toString()))
                )
            } catch (e: IOException) {
                write("create-file: ${e.message ?: "failed"}\r\n")
            }
        }

        private fun zipFiles(args: List<String>) {
            val effectiveArgs = args.filterNot { it == "-r" || it == "--recursive" }
            if (effectiveArgs.size < 2) {
                write("usage: zip <zipPath> <file|dir>...\r\n")
                return
            }
            val zip = resolvePath(effectiveArgs[0])
            val sources = effectiveArgs.drop(1).map(::resolvePath)
            val missing = sources.firstOrNull { !it.exists() }
            if (missing != null) {
                write("zip: ${missing.path}: No such file or directory\r\n")
                return
            }
            try {
                val result = RemoteArchive(::consumeCtrlC).zip(zip, sources)
                writeTable(
                    listOf("STATUS", "ZIP", "FILES", "DIRS", "BYTES"),
                    listOf(listOf("ok", zip.absolutePath, result.files.toString(), result.dirs.toString(), result.bytes.toString()))
                )
            } catch (_: RemoteArchive.Interrupted) {
                zip.delete()
                write("^C\r\n")
            } catch (e: Throwable) {
                zip.delete()
                write("zip failed: ${e.message ?: e.javaClass.simpleName}\r\n")
            }
        }

        private fun unzipFile(args: List<String>) {
            val zipArg = args.firstOrNull { it != "-d" && !it.startsWith("--") }
            val zip = resolveRequiredPath(zipArg, "unzip") ?: return
            if (!zip.isFile) {
                write("unzip: ${zip.path}: not a file\r\n")
                return
            }
            val destArg = optionValue(args, "--destination", "-d")
                ?: args.dropWhile { it == zipArg }.firstOrNull { it != "-d" && !it.startsWith("--") }
            val dest = destArg?.let(::resolvePath) ?: cwd
            try {
                val result = RemoteArchive(::consumeCtrlC).unzip(zip, dest)
                writeTable(
                    listOf("STATUS", "ZIP", "DEST", "FILES", "DIRS", "BYTES"),
                    listOf(listOf("ok", zip.absolutePath, dest.canonicalFile.absolutePath, result.files.toString(), result.dirs.toString(), result.bytes.toString()))
                )
            } catch (_: RemoteArchive.Interrupted) {
                write("^C\r\n")
            } catch (e: Throwable) {
                write("unzip failed: ${e.message ?: e.javaClass.simpleName}\r\n")
            }
        }

        private fun queryIp() {
            val result = listOf(
                "/system/bin/ip -o addr",
                "/system/bin/ip addr",
                "/system/bin/ifconfig",
                "ip addr",
                "ifconfig",
            ).firstNotNullOfOrNull { command ->
                executeForText(listOf(shellPath, "-c", command))?.takeIf(String::isNotBlank)
            } ?: networkInterfaceFallback().takeIf(String::isNotBlank)
            if (result.isNullOrBlank()) {
                write("ip query failed\r\n")
                return
            }
            writeWhitespaceTable(result, fallbackHeader = listOf("IP"))
        }

        private fun wifiConnect(args: List<String>) {
            val ssid = args.getOrNull(0)
            if (ssid.isNullOrBlank()) {
                write("usage: wifi-connect <ssid> [password] [open|wpa2|wpa3]\r\n")
                return
            }
            val password = args.getOrNull(1).orEmpty()
            val security = args.getOrNull(2)
                ?: if (password.isBlank()) "open" else "wpa2"
            val command = if (security == "open") {
                "cmd wifi connect-network ${shellEscape(ssid)} open"
            } else {
                "cmd wifi connect-network ${shellEscape(ssid)} $security ${shellEscape(password)}"
            }
            val result = executeFirstResult(
                listOf(listOf(shellPath, "-c", command)) + rootShellCommands(command)
            )
            writeNetworkResult("wifi-connect", ssid, result)
        }

        private fun wifiDisconnect() {
            val result = executeFirstResult(
                listOf(
                    listOf(shellPath, "-c", "cmd wifi disconnect"),
                    listOf(shellPath, "-c", "cmd wifi disconnect-network"),
                    listOf(shellPath, "-c", "svc wifi disable"),
                ) + rootShellCommands("svc wifi disable")
            )
            writeNetworkResult("wifi-disconnect", "wifi", result)
        }

        private fun wifiSet(args: List<String>) {
            when (args.firstOrNull()) {
                "enable", "on" -> {
                    val result = executeFirstResult(
                        listOf(
                            listOf(shellPath, "-c", "svc wifi enable"),
                            listOf(shellPath, "-c", "cmd wifi set-wifi-enabled enabled"),
                        ) + rootShellCommands("svc wifi enable")
                    )
                    writeNetworkResult("wifi-set", "enable", result)
                }
                "disable", "off" -> {
                    val result = executeFirstResult(
                        listOf(
                            listOf(shellPath, "-c", "svc wifi disable"),
                            listOf(shellPath, "-c", "cmd wifi set-wifi-enabled disabled"),
                        ) + rootShellCommands("svc wifi disable")
                    )
                    writeNetworkResult("wifi-set", "disable", result)
                }
                "status" -> {
                    val result = executeForResult(listOf(shellPath, "-c", "dumpsys wifi | head -n 20"))
                    writeNetworkResult("wifi-set", "status", result)
                }
                else -> write("usage: wifi-set <enable|disable|status>\r\n")
            }
        }

        private fun lanSet(args: List<String>, connectOnly: Boolean) {
            val iface = args.getOrNull(0)
            if (iface.isNullOrBlank()) {
                write("usage: ${if (connectOnly) "lan-connect" else "lan-set"} <iface> [dhcp|static <ip> <prefix> <gateway> [dns]]\r\n")
                return
            }
            val mode = args.getOrNull(1) ?: "dhcp"
            val command = when (mode) {
                "dhcp" -> "ip link set $iface up; (dhcpcd $iface || true)"
                "static" -> {
                    val ip = args.getOrNull(2)
                    val prefix = args.getOrNull(3) ?: "24"
                    val gateway = args.getOrNull(4)
                    val dns = args.getOrNull(5)
                    if (ip.isNullOrBlank() || gateway.isNullOrBlank()) {
                        write("usage: lan-set <iface> static <ip> <prefix> <gateway> [dns]\r\n")
                        return
                    }
                    buildString {
                        append("ip link set $iface up; ")
                        append("ip addr flush dev $iface; ")
                        append("ip addr add $ip/$prefix dev $iface; ")
                        append("ip route replace default via $gateway dev $iface")
                        if (!dns.isNullOrBlank()) {
                            append("; setprop net.dns1 $dns")
                        }
                    }
                }
                else -> {
                    write("usage: ${if (connectOnly) "lan-connect" else "lan-set"} <iface> [dhcp|static <ip> <prefix> <gateway> [dns]]\r\n")
                    return
                }
            }
            val result = executeFirstResult(
                listOf(listOf(shellPath, "-c", command)) + rootShellCommands(command)
            )
            writeNetworkResult(if (connectOnly) "lan-connect" else "lan-set", iface, result)
        }

        private fun lanDisconnect(iface: String?) {
            if (iface.isNullOrBlank()) {
                write("usage: lan-disconnect <iface>\r\n")
                return
            }
            val command = "ip link set $iface down"
            val result = executeFirstResult(
                listOf(
                    listOf(shellPath, "-c", command),
                    listOf(shellPath, "-c", "ifconfig $iface down"),
                ) + rootShellCommands(command)
            )
            writeNetworkResult("lan-disconnect", iface, result)
        }

        private fun pingHost(args: List<String>) {
            val countIndex = args.indexOf("-c")
            val count = if (countIndex >= 0) {
                args.getOrNull(countIndex + 1) ?: "4"
            } else {
                args.getOrNull(1) ?: "4"
            }
            val host = if (countIndex >= 0) {
                args.filterIndexed { index, value ->
                    index != countIndex && index != countIndex + 1 && value != "-c"
                }.firstOrNull()
            } else {
                args.firstOrNull()
            }
            if (host.isNullOrBlank()) {
                write("usage: ping <host> [count] | ping -c <count> <host>\r\n")
                return
            }
            val result = executeForResult(listOf(shellPath, "-c", "ping -c ${shellEscape(count)} ${shellEscape(host)}"))
            val parsed = parsePingResult(host, result.message)
            if (parsed != null) {
                writeTable(listOf("HOST", "SENT", "RECEIVED", "LOSS", "AVG_MS", "STATUS"), listOf(parsed + if (result.code == 0) "ok" else "failed"))
            } else {
                writeNetworkResult("ping", host, result)
            }
        }

        private fun parsePingResult(host: String, text: String): List<String>? {
            val packets = PING_PACKETS_REGEX.find(text) ?: return null
            val avg = PING_RTT_REGEX.find(text)?.groupValues?.getOrNull(2).orEmpty()
            return listOf(
                host,
                packets.groupValues[1],
                packets.groupValues[2],
                packets.groupValues[3],
                avg.ifBlank { "-" },
            )
        }

        private fun writeNetworkResult(action: String, target: String, result: CommandResult) {
            writeTable(
                listOf("ACTION", "TARGET", "STATUS", "MESSAGE"),
                listOf(listOf(action, target, if (result.code == 0) "ok" else "failed", result.message.ifBlank { "exit ${result.code}" }))
            )
        }

        private fun queryMemory(packageName: String?) {
            if (packageName.isNullOrBlank()) {
                val memInfo = executeForText(listOf(shellPath, "-c", "cat /proc/meminfo"))
                val processMemInfo = executeForText(listOf(shellPath, "-c", "dumpsys meminfo"))
                if (memInfo == null && processMemInfo == null) {
                    write("memory query failed\r\n")
                    return
                }
                if (memInfo != null) {
                    writeTableTitle("SYSTEM_MEMORY")
                    calculateMemoryUsagePercent(memInfo)?.let { usage ->
                        writeLinuxKeyValueRows(listOf(listOf("usage_percent", String.format(Locale.US, "%.1f%%", usage))))
                    }
                    writeKeyValueTable(memInfo)
                }
                val processRows = processMemInfo?.let(::parseRunningProcessMemoryRows).orEmpty()
                if (processRows.isNotEmpty()) {
                    writeTableTitle("RUNNING_APP_MEMORY")
                    writeTable(listOf("PSS_KB", "PROCESS"), processRows)
                }
            } else {
                val result = executeForText(listOf(shellPath, "-c", "dumpsys meminfo $packageName"))
                if (result == null) {
                    write("memory query failed for $packageName\r\n")
                    return
                }
                writeWhitespaceTable(result)
            }
        }

        private fun parseRunningProcessMemoryRows(text: String): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            var inProcessSection = false
            normalizedLines(text).forEach { line ->
                when {
                    line.contains("Total PSS by process", ignoreCase = true) ||
                        line.contains("PSS by process", ignoreCase = true) -> {
                        inProcessSection = true
                    }
                    inProcessSection && line.startsWith("Total PSS by", ignoreCase = true) -> {
                        inProcessSection = false
                    }
                    inProcessSection -> {
                        parseProcessMemoryLine(line)?.let(rows::add)
                    }
                }
            }
            return rows
        }

        private fun parseProcessMemoryLine(line: String): List<String>? {
            val match = PROCESS_MEMORY_REGEX.find(line) ?: return null
            val pss = match.groupValues[1].replace(",", "")
            val process = match.groupValues[2].trim()
            if (process.isBlank()) return null
            return listOf(pss, process)
        }

        private fun queryCpu(packageName: String?) {
            if (packageName.isNullOrBlank()) {
                val result = collectCpuInfo()
                if (result.first.isEmpty() && result.second.isEmpty()) {
                    write("cpu query failed\r\n")
                    return
                }
                writeTableTitle("CPU")
                writeLinuxKeyValueRows(result.first)
                if (result.second.isNotEmpty()) {
                    writeTable(
                        listOf("CORE", "ONLINE", "CURRENT", "MIN", "MAX", "GOVERNOR", "USAGE"),
                        result.second,
                    )
                }
            } else {
                val result = executeForText(listOf(shellPath, "-c", "top -b -n 1 | grep $packageName"))
                    ?: queryProcessStat(packageName)
                if (result == null) {
                    write("cpu query failed for $packageName\r\n")
                    return
                }
                writeWhitespaceTable(result)
            }
        }

        private fun collectCpuInfo(): Pair<List<List<String>>, List<List<String>>> {
            if (!shellPath.startsWith("/")) return emptyList<List<String>>() to emptyList()
            val before = readProcStat()
                ?: return basicCpuInfo() to emptyList()
            Thread.sleep(CPU_SAMPLE_MILLIS)
            val after = readProcStat()
                ?: return basicCpuInfo() to emptyList()
            val usage = calculateCpuUsage(before, after)
            val cpuDirs = File("/sys/devices/system/cpu").listFiles { file -> file.name.matches(Regex("cpu\\d+")) }
                ?.sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: Int.MAX_VALUE }
                .orEmpty()
            val props = getProperties()
            val summary = mutableListOf<List<String>>()
            summary += listOf("model", props["ro.soc.model"] ?: props["ro.board.platform"] ?: System.getProperty("os.arch").orEmpty())
            summary += listOf("architecture", props["ro.product.cpu.abi"] ?: System.getProperty("os.arch").orEmpty())
            summary += listOf("cores", cpuDirs.size.toString())
            File("/sys/devices/system/cpu/online").readTextOrNull()?.let { summary += listOf("online_cores", it.trim()) }
            usage["cpu"]?.let {
                summary += listOf("usage", formatCpuPercent(it.usagePercent))
                summary += listOf("user", formatCpuPercent(it.userPercent))
                summary += listOf("system", formatCpuPercent(it.systemPercent))
                summary += listOf("idle", formatCpuPercent(it.idlePercent))
            }
            queryCpuTemperature()?.let { summary += listOf("temperature", it) }
            val policyByCpu = queryCpuFrequencyPolicies()
            val cores = cpuDirs.map { dir ->
                val name = dir.name
                val cpufreq = File(dir, "cpufreq")
                val policy = name.removePrefix("cpu").toIntOrNull()?.let(policyByCpu::get)
                fun frequencyValue(fileName: String): String? =
                    File(cpufreq, fileName).readTextOrNull() ?: policy?.let { File(it, fileName).readTextOrNull() }
                listOf(
                    name.removePrefix("cpu"),
                    if (File(dir, "online").readTextOrNull()?.trim() == "0") "no" else "yes",
                    formatCpuFrequency(frequencyValue("scaling_cur_freq")),
                    formatCpuFrequency(frequencyValue("scaling_min_freq")),
                    formatCpuFrequency(frequencyValue("scaling_max_freq")),
                    (frequencyValue("scaling_governor")?.trim()).orEmpty().ifBlank { "-" },
                    usage[name]?.let { formatCpuPercent(it.usagePercent) } ?: "-",
                )
            }
            return summary to cores
        }

        private fun basicCpuInfo(): List<List<String>> {
            val processors = File("/proc/cpuinfo").readTextOrNull()
                ?.lineSequence()?.count { it.startsWith("processor") }
                ?.takeIf { it > 0 }
            return listOf(
                listOf("model", System.getProperty("os.arch").orEmpty().ifBlank { "unknown" }),
                listOf("architecture", System.getProperty("os.arch").orEmpty().ifBlank { "unknown" }),
                listOf("cores", (processors ?: Runtime.getRuntime().availableProcessors()).toString()),
                listOf("usage", "unavailable on this Android device"),
            )
        }

        private fun queryCpuFrequencyPolicies(): Map<Int, File> {
            val root = File("/sys/devices/system/cpu/cpufreq")
            val policies = root.listFiles { file -> file.isDirectory && file.name.matches(Regex("policy\\d+")) }.orEmpty()
            return buildMap {
                policies.forEach { policy ->
                    val cpuList = File(policy, "related_cpus").readTextOrNull()
                        ?: File(policy, "affected_cpus").readTextOrNull()
                        ?: policy.name.removePrefix("policy")
                    parseCpuIndexList(cpuList).forEach { cpu -> put(cpu, policy) }
                }
            }
        }

        private fun queryCpuTemperature(): String? {
            val zones = File("/sys/class/thermal").listFiles { file -> file.name.startsWith("thermal_zone") }.orEmpty()
            val preferred = zones.firstOrNull { zone ->
                val type = File(zone, "type").readTextOrNull()?.trim()?.lowercase(Locale.US).orEmpty()
                type.contains("cpu") || type.contains("soc") || type.contains("cluster") || type.contains("package")
            } ?: return null
            return File(preferred, "temp").readTextOrNull()?.let(::formatCpuTemperature)
        }

        private fun formatCpuFrequency(value: String?): String {
            val khz = value?.trim()?.toLongOrNull() ?: return "-"
            return String.format(Locale.US, "%,d MHz", khz / 1000L)
        }

        private fun formatCpuPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value)

        private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

        private fun readProcStat(): String? = runCatching {
            FileInputStream("/proc/stat").use { input ->
                input.readBytes().toString(StandardCharsets.UTF_8)
            }.takeIf { it.lineSequence().any { line -> line.startsWith("cpu ") } }
        }.getOrNull()

        private fun listApps(packageName: String?) {
            if (!packageName.isNullOrBlank()) {
                val info = appInfoResolver(packageName)
                if (info == null) {
                    write("apps: package not found or not visible: $packageName\r\n")
                    return
                }
                writeTable(
                    listOf("KEY", "VALUE"),
                    listOf(
                        listOf("PACKAGE", info.packageName),
                        listOf("APP_NAME", info.appName),
                        listOf("VERSION_NAME", info.versionName),
                        listOf("VERSION_CODE", info.versionCode),
                        listOf("APK_PATH", info.apkPath),
                        listOf("FIRST_INSTALL", info.firstInstallTime),
                        listOf("LAST_UPDATE", info.lastUpdateTime),
                    )
                )
                return
            }

            val appMap = linkedMapOf<String, AppInfo>()
            appListResolver().forEach { info ->
                if (info.packageName.isNotBlank()) {
                    appMap[info.packageName] = info
                }
            }

            val packageLines = if (appMap.isEmpty()) executeAppListCommands() else emptyList()
            packageLines.forEach { line ->
                val parsed = parsePackageListLine(line) ?: return@forEach
                val existing = appMap[parsed.packageName]
                val resolved = existing ?: appInfoResolver(parsed.packageName)
                appMap[parsed.packageName] = AndroidInteractiveShellFactory.AppInfo(
                    packageName = parsed.packageName,
                    appName = resolved?.appName ?: "-",
                    versionName = resolved?.versionName ?: "-",
                    versionCode = resolved?.versionCode ?: "-",
                    apkPath = parsed.apkPath ?: resolved?.apkPath ?: "-",
                    firstInstallTime = resolved?.firstInstallTime ?: "-",
                    lastUpdateTime = resolved?.lastUpdateTime ?: "-",
                )
            }

            if (appMap.isEmpty()) {
                write("app list failed\r\n")
                return
            }

            val rows = appMap.values
                .sortedBy { it.packageName }
                .map { info ->
                    listOf(info.packageName, info.appName, info.versionName, info.versionCode, info.apkPath)
                }
                .toList()
            writeTable(listOf("PACKAGE", "APP_NAME", "VERSION", "VERSION_CODE", "APK_PATH"), rows)
        }

        private data class PackageListEntry(
            val packageName: String,
            val apkPath: String?,
        )

        private fun executeAppListCommands(): List<String> {
            if (!shellPath.startsWith("/")) return emptyList()
            val commands = listOf(
                "pm list packages -f -a --user 0",
                "pm list packages -f --user 0",
                "pm list packages -f -a",
                "pm list packages -f",
                "pm list packages -a --user 0",
                "pm list packages --user 0",
                "pm list packages -a",
                "pm list packages",
            )
            return commands
                .mapNotNull { command -> executeForText(listOf(shellPath, "-c", command)) }
                .flatMap { text -> normalizedLines(text) }
                .distinct()
        }

        private fun parsePackageListLine(line: String): PackageListEntry? {
            val body = line.trim().removePrefix("package:").trim()
            if (body.isBlank()) return null
            val separator = body.lastIndexOf('=')
            return if (separator > 0) {
                PackageListEntry(
                    packageName = body.substring(separator + 1).trim(),
                    apkPath = body.substring(0, separator).trim().ifBlank { null },
                )
            } else {
                PackageListEntry(packageName = body, apkPath = null)
            }.takeIf { it.packageName.isNotBlank() }
        }

        private fun listRunningApps() {
            val ps = querySystemProcessList()
            if (ps.isNullOrBlank()) {
                write("running process list failed\r\n")
                return
            }
            val foregroundPackage = queryForegroundPackage()
            val rows = buildRunningProcessSnapshotRows(ps, foregroundPackage)
            if (rows.isEmpty()) {
                write("running process list empty or not visible\r\n")
                return
            }
            writeTable(listOf("PID", "USER", "STATE", "PACKAGE", "APP_NAME"), rows.map { row ->
                listOf(row.pid, row.user, row.state, row.packageName, row.appName)
            })
        }

        private fun htopSnapshot(args: List<String>) {
            val includeNative = args.any { it == "-a" || it == "--all" }
            val ps = if (includeNative) queryFastSystemProcessList() else querySystemProcessList()
            if (ps.isNullOrBlank()) {
                write("process snapshot failed\r\n")
                return
            }

            val limit = optionValue(args, "--limit", "-n")?.toIntOrNull()?.coerceIn(1, 500) ?: HTOP_DEFAULT_LIMIT
            val topMetricsByPid = queryTopProcessMetricsByPid()
            val memInfo = queryMemorySnapshot()
            val processRows = if (includeNative) {
                parseAllProcessRows(ps)
            } else {
                buildRunningProcessSnapshotRows(ps, queryForegroundPackage())
            }
            val selectedRows = processRows
                .asSequence()
                .sortedWith(
                    compareBy<RunningProcessRow> { runningProcessSortRank(it) }
                        .thenByDescending { topMetricsByPid[it.pid]?.cpuPercent?.toDoubleOrNull() ?: -1.0 }
                        .thenBy { it.pid.toIntOrNull() ?: Int.MAX_VALUE }
                )
                .take(limit)
                .toList()

            val rows = selectedRows
                .asSequence()
                .map { row ->
                    val metrics = queryProcessSnapshotMetrics(row.pid, topMetricsByPid, memInfo.totalKb)
                    ProcessSnapshotRow(
                        pid = row.pid,
                        user = row.user,
                        priority = metrics.priority,
                        nice = metrics.nice,
                        virtKb = metrics.virtKb,
                        cpuPercent = metrics.cpuPercent,
                        memPercent = metrics.memPercent,
                        rssKb = metrics.rssKb,
                        sharedKb = metrics.sharedKb,
                        stateChar = metrics.stateChar.ifBlank { row.state.take(1) },
                        timePlus = metrics.timePlus,
                        state = row.state,
                        packageName = row.packageName,
                        appName = row.appName,
                        command = row.process.takeIf { it != "-" } ?: row.packageName,
                    )
                }
                .toList()

            writeHtopSnapshot(rows, totalTasks = processRows.size, memInfo = memInfo)
        }

        private data class ProcessSnapshotMetrics(
            val priority: String,
            val nice: String,
            val virtKb: String,
            val cpuPercent: String,
            val memPercent: String,
            val rssKb: String,
            val sharedKb: String,
            val stateChar: String,
            val timePlus: String,
        )

        private data class ProcessSnapshotRow(
            val pid: String,
            val user: String,
            val priority: String,
            val nice: String,
            val virtKb: String,
            val cpuPercent: String,
            val memPercent: String,
            val rssKb: String,
            val sharedKb: String,
            val stateChar: String,
            val timePlus: String,
            val state: String,
            val packageName: String,
            val appName: String,
            val command: String,
        )

        private data class MemorySnapshot(
            val totalKb: Long?,
            val freeKb: Long?,
            val availableKb: Long?,
            val swapTotalKb: Long?,
            val swapFreeKb: Long?,
        )

        private fun queryProcessSnapshotMetrics(
            pid: String,
            topMetricsByPid: Map<String, TopProcessMetrics>,
            memTotalKb: Long?,
        ): ProcessSnapshotMetrics {
            val top = topMetricsByPid[pid]
            val status = queryProcessStatus(pid)
            val rssKb = top?.rssKb ?: status["VmRSS"]
            val fallbackMemPercent = if (rssKb != null && memTotalKb != null && memTotalKb > 0) {
                String.format(Locale.US, "%.1f", rssKb.toDouble() * 100.0 / memTotalKb.toDouble())
            } else {
                "-"
            }
            val stat = queryProcessStatByPid(pid)
            return ProcessSnapshotMetrics(
                priority = top?.priority ?: stat?.priority ?: "-",
                nice = top?.nice ?: stat?.nice ?: "-",
                virtKb = (top?.virtKb ?: status["VmSize"])?.toString() ?: "-",
                cpuPercent = top?.cpuPercent ?: "-",
                memPercent = top?.memPercent ?: fallbackMemPercent,
                rssKb = rssKb?.toString() ?: "-",
                sharedKb = (top?.sharedKb ?: status["RssFile"])?.toString() ?: "-",
                stateChar = top?.state ?: stat?.state ?: "",
                timePlus = top?.timePlus ?: stat?.timePlus ?: "-",
            )
        }

        private fun writeHtopSnapshot(
            rows: List<ProcessSnapshotRow>,
            totalTasks: Int,
            memInfo: MemorySnapshot,
        ) {
            val now = DATE_FORMAT.get()!!.format(Date())
            val load = queryLoadAverage()
            val uptime = queryUptime()
            val shown = rows.size
            val running = rows.count { it.stateChar == "R" || it.state == "FOREGROUND" }
            val sleeping = (shown - running).coerceAtLeast(0)
            val usedKb = if (memInfo.totalKb != null && memInfo.availableKb != null) {
                (memInfo.totalKb - memInfo.availableKb).coerceAtLeast(0)
            } else {
                null
            }
            val swapUsedKb = if (memInfo.swapTotalKb != null && memInfo.swapFreeKb != null) {
                (memInfo.swapTotalKb - memInfo.swapFreeKb).coerceAtLeast(0)
            } else {
                null
            }

            write("${colorHeader("htop - one-shot snapshot")}  $now\r\n")
            write("Tasks: $totalTasks total, $shown shown, $running running, $sleeping sleeping")
            if (load != "-") write("  Load average: $load")
            if (uptime != "-") write("  Uptime: $uptime")
            write("\r\n")
            write("Mem: ${formatKb(memInfo.totalKb)} total, ${formatKb(usedKb)} used, ${formatKb(memInfo.freeKb)} free, ${formatKb(memInfo.availableKb)} avail\r\n")
            write("Swp: ${formatKb(memInfo.swapTotalKb)} total, ${formatKb(swapUsedKb)} used, ${formatKb(memInfo.swapFreeKb)} free\r\n")
            write("\r\n")
            write(colorHeader(String.format(Locale.US, "%7s %-10s %4s %4s %8s %8s %8s %-1s %6s %6s %9s %s", "PID", "USER", "PRI", "NI", "VIRT", "RES", "SHR", "S", "CPU%", "MEM%", "TIME+", "COMMAND")) + "\r\n")
            rows.forEach { row ->
                val command = when {
                    row.packageName != "-" && row.appName != "-" -> "${row.packageName} (${row.appName})"
                    row.packageName != "-" -> row.packageName
                    else -> row.command
                }
                val line = String.format(
                    Locale.US,
                    "%7s %-10s %4s %4s %8s %8s %8s %-1s %6s %6s %9s %s",
                    row.pid,
                    row.user.take(10),
                    row.priority,
                    row.nice,
                    formatKbCompact(row.virtKb),
                    formatKbCompact(row.rssKb),
                    formatKbCompact(row.sharedKb),
                    row.stateChar.take(1).ifBlank { "-" },
                    row.cpuPercent,
                    row.memPercent,
                    row.timePlus,
                    command,
                )
                write(colorHtopProcessLine(row, line) + "\r\n")
            }
        }

        private fun colorHtopProcessLine(row: ProcessSnapshotRow, line: String): String {
            return when {
                row.state == "FOREGROUND" -> colorSuccess(line)
                row.stateChar == "R" -> colorSuccess(line)
                row.packageName != "-" -> colorPackage(line)
                else -> colorMuted(line)
            }
        }

        private fun queryTopProcessMetricsByPid(): Map<String, TopProcessMetrics> {
            if (!shellPath.startsWith("/")) return emptyMap()
            val commands = listOf("top -b -n 1", "top -n 1")
            commands.forEach { command ->
                val candidates = rootShellCommands(command).take(2) + listOf(listOf(shellPath, "-c", command))
                candidates.forEach { candidate ->
                    val text = executeForText(candidate, timeoutSeconds = HTOP_COMMAND_TIMEOUT_SECONDS)
                        ?: return@forEach
                    val metrics = parseTopProcessMetrics(text)
                    if (metrics.isNotEmpty()) return metrics
                }
            }
            return emptyMap()
        }

        private data class ProcessStatSnapshot(
            val state: String,
            val priority: String,
            val nice: String,
            val timePlus: String,
        )

        private fun queryMemorySnapshot(): MemorySnapshot {
            if (!shellPath.startsWith("/")) return MemorySnapshot(null, null, null, null, null)
            val text = runCatching { File("/proc/meminfo").takeIf { it.isFile }?.readText() }.getOrNull()
                ?: return MemorySnapshot(null, null, null, null, null)
            val values = parseProcKeyValueKb(text)
            return MemorySnapshot(
                totalKb = values["MemTotal"],
                freeKb = values["MemFree"],
                availableKb = values["MemAvailable"],
                swapTotalKb = values["SwapTotal"],
                swapFreeKb = values["SwapFree"],
            )
        }

        private fun queryProcessStatus(pid: String): Map<String, Long> {
            if (!shellPath.startsWith("/")) return emptyMap()
            if (!pid.all(Char::isDigit)) return emptyMap()
            val path = "/proc/$pid/status"
            val text = runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull()
                ?: return emptyMap()
            return parseProcKeyValueKb(text)
        }

        private fun parseProcKeyValueKb(text: String): Map<String, Long> {
            return normalizedLines(text).mapNotNull { line ->
                val key = line.substringBefore(':').trim()
                val value = line.substringAfter(':', "").trim()
                    .split(Regex("\\s+"))
                    .firstOrNull { it.all(Char::isDigit) }
                    ?.toLongOrNull()
                    ?: return@mapNotNull null
                key to value
            }.toMap()
        }

        private fun queryProcessStatByPid(pid: String): ProcessStatSnapshot? {
            if (!shellPath.startsWith("/") || !pid.all(Char::isDigit)) return null
            val text = runCatching { File("/proc/$pid/stat").takeIf { it.isFile }?.readText() }.getOrNull()
                ?: return null
            val rightParen = text.lastIndexOf(')')
            if (rightParen < 0 || rightParen + 2 >= text.length) return null
            val fields = text.substring(rightParen + 2).trim().split(Regex("\\s+"))
            val state = fields.getOrNull(0) ?: "-"
            val utime = fields.getOrNull(11)?.toLongOrNull() ?: 0L
            val stime = fields.getOrNull(12)?.toLongOrNull() ?: 0L
            return ProcessStatSnapshot(
                state = state,
                priority = fields.getOrNull(15) ?: "-",
                nice = fields.getOrNull(16) ?: "-",
                timePlus = formatProcessTime((utime + stime) / 100L),
            )
        }

        private fun queryLoadAverage(): String {
            if (!shellPath.startsWith("/")) return "-"
            return runCatching { File("/proc/loadavg").takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
                ?.split(Regex("\\s+"))
                ?.take(3)
                ?.joinToString(" ")
                ?: "-"
        }

        private fun queryUptime(): String {
            if (!shellPath.startsWith("/")) return "-"
            val seconds = runCatching {
                File("/proc/uptime").takeIf { it.isFile }?.readText()?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toDoubleOrNull()?.toLong()
            }.getOrNull() ?: return "-"
            val days = seconds / 86_400
            val hours = (seconds % 86_400) / 3_600
            val minutes = (seconds % 3_600) / 60
            return if (days > 0) "${days}d ${hours}h ${minutes}m" else "${hours}h ${minutes}m"
        }

        private fun formatProcessTime(seconds: Long): String {
            val minutes = seconds / 60
            val remain = seconds % 60
            return String.format(Locale.US, "%d:%02d.00", minutes, remain)
        }

        private fun formatKb(value: Long?): String {
            return value?.let(::formatKbCompact) ?: "-"
        }

        private fun formatKbCompact(value: String): String {
            return value.toLongOrNull()?.let(::formatKbCompact) ?: "-"
        }

        private fun formatKbCompact(value: Long): String {
            val abs = kotlin.math.abs(value.toDouble())
            return when {
                abs >= 1024.0 * 1024.0 -> String.format(Locale.US, "%.1fg", value / 1024.0 / 1024.0)
                abs >= 1024.0 -> String.format(Locale.US, "%.1fm", value / 1024.0)
                else -> "${value}k"
            }
        }

        private fun querySystemProcessList(): String? {
            if (!shellPath.startsWith("/")) {
                return queryHostProcessList() ?: "USER PID PPID NAME ARGS"
            }
            val psCommands = listOf(
                "ps -A -o USER,PID,PPID,NAME,ARGS",
                "ps -A -o USER,PID,NAME,ARGS",
                "ps -A -o PID,NAME,ARGS",
                "ps -A",
                "ps",
            )
            psCommands.forEach { command ->
                executeFirstText(rootShellCommands(command) + listOf(listOf(shellPath, "-c", command)))
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
            return queryProcProcessList()
        }

        private fun queryFastSystemProcessList(): String? {
            if (!shellPath.startsWith("/")) {
                return queryHostProcessList() ?: "USER PID PPID NAME ARGS"
            }
            val processLists = mutableListOf<String>()
            queryFastProcProcessList()?.let(processLists::add)
            val psCommands = listOf(
                "ps -A -o USER,PID,PPID,NAME,ARGS",
                "ps -A -o USER,PID,NAME,ARGS",
                "ps -A -o PID,NAME,ARGS",
                "ps -A",
                "ps",
            )
            psCommands.forEach { command ->
                val ps = executeForText(listOf(shellPath, "-c", command), timeoutSeconds = HTOP_COMMAND_TIMEOUT_SECONDS)
                    ?.takeIf { it.isNotBlank() }
                if (ps != null) {
                    processLists += ps
                }
            }
            return mergeProcessLists(processLists).ifBlank { "USER PID PPID NAME ARGS" }
        }

        private fun mergeProcessLists(lists: List<String>): String {
            val rows = linkedMapOf<String, RunningProcessRow>()
            lists.forEach { text ->
                parseAllProcessRows(text).forEach { row ->
                    val existing = rows[row.pid]
                    rows[row.pid] = when {
                        existing == null -> row
                        existing.packageName == "-" && row.packageName != "-" -> row
                        existing.packageName != "-" && row.packageName == "-" -> existing
                        existing.args.length < row.args.length -> row
                        else -> existing
                    }
                }
            }
            if (rows.isEmpty()) return ""
            return (listOf("USER PID PPID NAME ARGS") + rows.values.map { row ->
                "${row.user} ${row.pid} 0 ${row.process} ${row.args}"
            }).joinToString("\n")
        }

        private fun queryFastProcProcessList(): String? {
            if (!shellPath.startsWith("/")) return null
            val rows = File("/proc")
                .listFiles()
                ?.asSequence()
                ?.filter { file -> file.name.all(Char::isDigit) }
                ?.mapNotNull { dir -> fastProcProcessLine(dir) }
                ?.toList()
                .orEmpty()
            if (rows.isEmpty()) return null
            return (listOf("USER PID PPID NAME ARGS") + rows).joinToString("\n")
        }

        private fun fastProcProcessLine(dir: File): String? {
            val pid = dir.name.takeIf { it.all(Char::isDigit) } ?: return null
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return null
            val rightParen = stat.lastIndexOf(')')
            val leftParen = stat.indexOf('(')
            if (leftParen < 0 || rightParen <= leftParen) return null
            val name = stat.substring(leftParen + 1, rightParen).ifBlank { pid }
            val fields = stat.substring(rightParen + 2).trim().split(Regex("\\s+"))
            val ppid = fields.getOrNull(1) ?: "0"
            val cmdline = runCatching {
                File(dir, "cmdline")
                    .readBytes()
                    .toString(StandardCharsets.UTF_8)
                    .replace('\u0000', ' ')
                    .trim()
            }.getOrNull().orEmpty()
            val command = cmdline.ifBlank { name }
            return "- $pid $ppid $name $command"
        }

        private fun queryHostProcessList(): String? {
            val script = """
                Write-Output 'USER PID PPID NAME ARGS'
                Get-Process | Select-Object -First 200 | ForEach-Object {
                  Write-Output ("{0} {1} 0 {2} {2}" -f ${'$'}env:USERNAME, ${'$'}_.Id, ${'$'}_.ProcessName)
                }
            """.trimIndent()
            return executeForText(listOf(shellPath, "-NoProfile", "-Command", script), timeoutSeconds = 2)
                ?.takeIf { it.isNotBlank() }
        }

        private fun queryProcProcessList(): String? {
            if (!shellPath.startsWith("/")) return null
            val script = """
                echo "USER PID PPID NAME ARGS"
                for d in /proc/[0-9]*; do
                  pid="${'$'}{d##*/}"
                  [ -r "${'$'}d/stat" ] || continue
                  stat="${'$'}(cat "${'$'}d/stat" 2>/dev/null)"
                  name="${'$'}{stat#*(}"
                  name="${'$'}{name%%)*}"
                  rest="${'$'}{stat##*) }"
                  set -- ${'$'}rest
                  ppid="${'$'}2"
                  user="${'$'}(ls -ld "${'$'}d" 2>/dev/null | awk '{print ${'$'}3}')"
                  cmd="${'$'}(tr '\000' ' ' < "${'$'}d/cmdline" 2>/dev/null)"
                  [ -n "${'$'}cmd" ] || cmd="${'$'}name"
                  echo "${'$'}user ${'$'}pid ${'$'}ppid ${'$'}name ${'$'}cmd"
                done
            """.trimIndent()
            return executeFirstText(rootShellCommands(script) + listOf(listOf(shellPath, "-c", script)))
                ?.takeIf { it.isNotBlank() }
        }

        private data class RunningProcessRow(
            val pid: String,
            val user: String,
            val state: String,
            val packageName: String,
            val appName: String,
            val process: String,
            val args: String,
        )

        private fun buildRunningProcessRows(ps: String, foregroundPackage: String?): List<List<String>> {
            return buildRunningProcessSnapshotRows(ps, foregroundPackage).map { row ->
                listOf(row.pid, row.user, row.state, row.packageName, row.appName)
            }
        }

        private fun buildRunningProcessSnapshotRows(ps: String, foregroundPackage: String?): List<RunningProcessRow> {
            val packageRows = buildRunningPackageRows(ps, foregroundPackage)
            val processRows = parseAllProcessRows(ps)
            val byPackage = processRows
                .filter { row -> row.packageName != "-" }
                .groupBy { row -> row.packageName }
            return packageRows.values
                .flatMap { packageName ->
                    val rows = byPackage[packageName.packageName].orEmpty()
                    if (rows.isNotEmpty()) {
                        rows.map { row -> row.copy(state = packageName.state) }
                    } else {
                        listOf(
                            RunningProcessRow(
                                pid = packageName.pids.firstOrNull().orEmpty().ifBlank { "-" },
                                user = packageName.users.firstOrNull().orEmpty().ifBlank { "-" },
                                state = packageName.state,
                                packageName = packageName.packageName,
                                appName = appInfoResolver(packageName.packageName)?.appName ?: "-",
                                process = packageName.processes.firstOrNull().orEmpty().ifBlank { packageName.packageName },
                                args = packageName.processes.firstOrNull().orEmpty().ifBlank { packageName.packageName },
                            )
                        )
                    }
                }
                .distinctBy { row -> "${row.pid}:${row.packageName}:${row.process}" }
                .sortedWith(runningProcessComparator())
        }

        private fun runningProcessComparator(): Comparator<RunningProcessRow> {
            return compareBy<RunningProcessRow> { row -> runningProcessSortRank(row) }
                .thenBy { row -> row.pid.toIntOrNull() ?: Int.MAX_VALUE }
                .thenBy { row -> row.packageName }
        }

        private fun runningProcessSortRank(row: RunningProcessRow): Int {
            return when {
                row.state == "FOREGROUND" -> 0
                row.user.startsWith("u0") -> 1
                !isSystemUser(row.user) -> 2
                isSystemUser(row.user) -> 3
                else -> 4
            }
        }

        private fun isSystemUser(user: String): Boolean {
            return user == "system" || user == "root" || user == "shell"
        }

        private fun parseAllProcessRows(ps: String): List<RunningProcessRow> {
            val lines = normalizedLines(ps)
            return lines.asSequence()
                .filterNot { it.startsWith("USER ") || it.startsWith("PID ") || it.startsWith("LABEL ") }
                .mapNotNull(::parseProcessLine)
                .distinctBy { row -> row.pid }
                .sortedBy { row -> row.pid.toIntOrNull() ?: Int.MAX_VALUE }
                .toList()
        }

        private fun parseProcessLine(line: String): RunningProcessRow? {
            val columns = line.split(Regex("\\s+")).filter { it.isNotBlank() }
            val pidIndex = columns.indexOfFirst { it.all(Char::isDigit) }
            if (pidIndex < 0) return null
            val pid = columns[pidIndex]
            val user = if (pidIndex > 0) columns[pidIndex - 1] else "-"
            val processIndex = when {
                columns.getOrNull(pidIndex + 1)?.all(Char::isDigit) == true -> pidIndex + 2
                else -> pidIndex + 1
            }
            val processRaw = columns.getOrNull(processIndex)?.substringAfterLast('/') ?: columns.lastOrNull()?.substringAfterLast('/') ?: return null
            val process = processRaw.ifBlank { "-" }
            val packageName = process.substringBefore(':').takeIf { it.contains('.') }
                ?: PACKAGE_NAME_REGEX.find(line)?.value
                ?: ""
            val appName = packageName.takeIf { it.isNotBlank() }?.let { appInfoResolver(it)?.appName } ?: "-"
            val args = if (columns.size > processIndex) columns.drop(processIndex).joinToString(" ") else process
            val state = if (packageName.isNotBlank()) "BACKGROUND" else "PROCESS"
            return RunningProcessRow(
                pid = pid,
                user = user,
                state = state,
                packageName = packageName.ifBlank { "-" },
                appName = appName,
                process = process,
                args = args,
            )
        }

        private fun buildRunningPackageStateMap(ps: String, foregroundPackage: String?): Map<String, String> {
            return buildRunningPackageRows(ps, foregroundPackage).mapValues { it.value.state }
        }

        private fun buildRunningPackageRows(ps: String, foregroundPackage: String?): Map<String, RunningAppRow> {
            val rows = linkedMapOf<String, RunningAppRow>()
            runningAppResolver().forEach { app ->
                mergeRunningAppRow(rows, app.packageName, app.pid, app.processName, app.source, app.state, app.user)
            }
            parseRunningProcessRows(ps).forEach { row ->
                mergeRunningAppRow(rows, row.packageName, row.pid, row.process, "ps", row.state)
            }
            foregroundPackage?.let { packageName ->
                mergeRunningAppRow(rows, packageName, "", packageName, "foreground", "FOREGROUND")
            }
            queryRunningServicePackages().forEach { service ->
                mergeRunningAppRow(rows, service.packageName, service.pid, service.process, "service", "SERVICE")
            }
            queryActivityProcessPackages().forEach { process ->
                mergeRunningAppRow(rows, process.packageName, process.pid, process.process, "activity", "BACKGROUND")
            }
            queryRecentTaskPackages().forEach { process ->
                mergeRunningAppRow(rows, process.packageName, process.pid, process.process, "recent", "BACKGROUND")
            }
            return rows
        }

        private data class RunningAppRow(
            val packageName: String,
            val pids: MutableSet<String> = linkedSetOf(),
            val processes: MutableSet<String> = linkedSetOf(),
            val sources: MutableSet<String> = linkedSetOf(),
            val users: MutableSet<String> = linkedSetOf(),
            var state: String = "BACKGROUND",
        )

        private data class RunningProcessEntry(
            val pid: String,
            val packageName: String,
            val process: String,
            val state: String,
        )

        private fun parseRunningProcessRows(ps: String): List<RunningProcessEntry> {
            return normalizedLines(ps)
                .filterNot { it.startsWith("USER ") || it.startsWith("PID ") || it.startsWith("LABEL ") }
                .mapNotNull { line ->
                    val columns = line.split(Regex("\\s+")).filter { it.isNotBlank() }
                    val pidIndex = columns.indexOfFirst { it.all(Char::isDigit) }
                    if (pidIndex < 0) return@mapNotNull null
                    val pid = columns[pidIndex]
                    val process = columns.lastOrNull()?.substringAfterLast('/') ?: return@mapNotNull null
                    val packageName = process.substringBefore(':')
                    if (!packageName.contains('.')) return@mapNotNull null
                    RunningProcessEntry(pid, packageName, process, "BACKGROUND")
                }
                .distinctBy { entry -> "${entry.pid}:${entry.process}" }
                .toList()
        }

        private data class RunningPackageEntry(
            val packageName: String,
            val pid: String = "",
            val process: String = "",
        )

        private fun queryPidofInstalledPackages(): List<RunningPackageEntry> {
            if (!shellPath.startsWith("/")) return emptyList()
            val packages = appListResolver()
                .map { it.packageName }
                .filter { it.isNotBlank() && it.contains('.') }
                .distinct()
            if (packages.isEmpty()) return emptyList()
            return packages.chunked(PIDOF_PACKAGE_CHUNK_SIZE).flatMap { chunk ->
                val packageArgs = chunk.joinToString(" ") { shellEscape(it) }
                val script = """
                    for p in $packageArgs; do
                      pid="${'$'}(pidof "${'$'}p" 2>/dev/null || true)"
                      if [ -n "${'$'}pid" ]; then
                        echo "${'$'}pid ${'$'}p"
                      fi
                    done
                """.trimIndent()
                executeForText(listOf(shellPath, "-c", script))
                    ?.let(::parsePidofPackageRows)
                    .orEmpty()
            }
        }

        private fun parsePidofPackageRows(text: String): List<RunningPackageEntry> {
            return normalizedLines(text).flatMap { line ->
                val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
                val packageName = parts.lastOrNull()?.takeIf { it.contains('.') } ?: return@flatMap emptyList()
                parts.dropLast(1)
                    .filter { it.all(Char::isDigit) }
                    .map { pid -> RunningPackageEntry(packageName = packageName, pid = pid, process = packageName) }
            }
                .distinctBy { "${it.packageName}:${it.pid}" }
                .toList()
        }

        private fun fillMissingRunningAppPids(rows: MutableMap<String, RunningAppRow>) {
            rows.values
                .filter { row -> row.pids.isEmpty() }
                .forEach { row ->
                    queryPackagePid(row.packageName).forEach { pid ->
                        row.pids += pid
                        row.sources += "pid-lookup"
                    }
                }
        }

        private fun queryPackagePid(packageName: String): List<String> {
            if (!shellPath.startsWith("/")) return emptyList()
            val pkg = shellEscape(packageName)
            val script = """
                target=$pkg
                {
                  pidof "${'$'}target" 2>/dev/null || true
                  for d in /proc/[0-9]*; do
                    pid="${'$'}{d##*/}"
                    [ -r "${'$'}d/cmdline" ] || continue
                    cmd="${'$'}(tr '\000' ' ' < "${'$'}d/cmdline" 2>/dev/null)"
                    case "${'$'}cmd" in
                      "${'$'}target"|${'$'}target" "*|${'$'}target":"*|${'$'}target": "*)
                        echo "${'$'}pid"
                        ;;
                    esac
                  done
                  ps -A 2>/dev/null | while read line; do
                    case "${'$'}line" in
                      *"${'$'}target"*)
                        set -- ${'$'}line
                        echo "${'$'}2"
                        ;;
                    esac
                  done
                } | tr ' ' '\n' | grep '^[0-9][0-9]*${'$'}' | sort -u
            """.trimIndent()
            return executeFirstText(listOf(listOf(shellPath, "-c", script)) + rootShellCommands(script))
                ?.split(Regex("\\s+"))
                ?.filter { it.all(Char::isDigit) }
                ?.distinct()
                .orEmpty()
        }

        private fun queryRunningServicePackages(): List<RunningPackageEntry> {
            if (!shellPath.startsWith("/")) return emptyList()
            val result = executeFirstText(listOf(listOf(shellPath, "-c", "dumpsys activity services")) + rootShellCommands("dumpsys activity services"))
                ?: executeFirstText(listOf(listOf(shellPath, "-c", "dumpsys activity service all")) + rootShellCommands("dumpsys activity service all"))
                ?: return emptyList()
            return normalizedLines(result).mapNotNull { line ->
                val packageName = SERVICE_COMPONENT_REGEX.find(line)?.groupValues?.getOrNull(1)
                    ?: PACKAGE_NAME_REGEX.find(line)?.value
                    ?: return@mapNotNull null
                RunningPackageEntry(
                    packageName = packageName,
                    pid = extractPid(line),
                    process = extractProcessName(line, packageName),
                )
            }
                .distinctBy { "${it.packageName}:${it.pid}:${it.process}" }
                .toList()
        }

        private fun queryActivityProcessPackages(): List<RunningPackageEntry> {
            if (!shellPath.startsWith("/")) return emptyList()
            val result = executeFirstText(listOf(listOf(shellPath, "-c", "dumpsys activity processes")) + rootShellCommands("dumpsys activity processes"))
                ?: return emptyList()
            return normalizedLines(result).mapNotNull { line ->
                val packageName = PACKAGE_NAME_REGEX.find(line)?.value ?: return@mapNotNull null
                RunningPackageEntry(
                    packageName = packageName,
                    pid = extractPid(line),
                    process = extractProcessName(line, packageName),
                )
            }
                .distinctBy { "${it.packageName}:${it.pid}:${it.process}" }
                .toList()
        }

        private fun queryRecentTaskPackages(): List<RunningPackageEntry> {
            if (!shellPath.startsWith("/")) return emptyList()
            val result = executeFirstText(listOf(listOf(shellPath, "-c", "dumpsys activity recents")) + rootShellCommands("dumpsys activity recents"))
                ?: return emptyList()
            return normalizedLines(result).mapNotNull { line ->
                val packageName = SERVICE_COMPONENT_REGEX.find(line)?.groupValues?.getOrNull(1)
                    ?: PACKAGE_NAME_REGEX.find(line)?.value
                    ?: return@mapNotNull null
                RunningPackageEntry(
                    packageName = packageName,
                    pid = extractPid(line),
                    process = extractProcessName(line, packageName),
                )
            }
                .distinctBy { "${it.packageName}:${it.pid}:${it.process}" }
                .toList()
        }

        private fun extractPid(line: String): String {
            return PID_REGEX.find(line)
                ?.groupValues
                ?.drop(1)
                ?.firstOrNull { it.isNotBlank() }
                .orEmpty()
        }

        private fun extractProcessName(line: String, packageName: String): String {
            return PROCESS_NAME_REGEX.find(line)?.groupValues?.getOrNull(1)
                ?: PROCESS_RECORD_REGEX.find(line)?.groupValues?.getOrNull(2)
                ?: packageName
        }

        private fun mergeRunningAppRow(
            rows: MutableMap<String, RunningAppRow>,
            packageName: String,
            pid: String,
            process: String,
            source: String,
            state: String,
            user: String = "",
        ) {
            if (packageName.isBlank() || !packageName.contains('.')) return
            val row = rows.getOrPut(packageName) { RunningAppRow(packageName) }
            if (pid.isNotBlank()) row.pids += pid
            if (process.isNotBlank()) row.processes += process
            if (user.isNotBlank()) row.users += user
            row.sources += source
            if (runningStateRank(state) < runningStateRank(row.state)) {
                row.state = state
            }
        }

        private fun runningStateRank(state: String): Int {
            return when (state) {
                "FOREGROUND" -> 0
                "SERVICE" -> 1
                "BACKGROUND" -> 2
                else -> 3
            }
        }

        private fun queryForegroundPackage(): String? {
            if (!shellPath.startsWith("/")) return null
            val result = executeForText(listOf(shellPath, "-c", "dumpsys window windows"))
                ?: executeForText(listOf(shellPath, "-c", "dumpsys activity top"))
                ?: return null
            return FOREGROUND_PACKAGE_REGEX.find(result)?.groupValues?.getOrNull(1)
        }

        private fun startApp(packageName: String?, activity: String?) {
            if (packageName.isNullOrBlank()) {
                write("usage: start-app <packageName> [activity]\r\n")
                return
            }
            appStartResolver(packageName, activity)?.let { result ->
                writeTable(
                    listOf("STATUS", "PACKAGE", "MESSAGE"),
                    listOf(listOf(if (result.success) "ok" else "failed", packageName, result.message.ifBlank { "start requested" }))
                )
                return
            }
            val commands = if (activity.isNullOrBlank()) {
                val launcher = appLaunchActivityResolver(packageName)
                buildList {
                    if (!launcher.isNullOrBlank()) {
                        add(listOf(shellPath, "-c", "am start -n ${shellEscape(launcher)}"))
                    }
                    add(listOf(shellPath, "-c", resolveAndStartLauncherCommand(packageName)))
                    add(listOf(shellPath, "-c", "am start --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${shellEscape(packageName)}"))
                }
            } else {
                val component = when {
                    activity.startsWith("/") -> "$packageName$activity"
                    "/" in activity -> activity
                    else -> "$packageName/$activity"
                }
                listOf(listOf(shellPath, "-c", "am start -n ${shellEscape(component)}"))
            }
            val result = executeFirstResult(commands)
            val ok = result.code == 0
            writeTable(
                listOf("STATUS", "PACKAGE", "MESSAGE"),
                listOf(listOf(if (ok) "ok" else "failed", packageName, result.message.ifBlank { "start failed" }))
            )
        }

        private fun resolveAndStartLauncherCommand(packageName: String): String {
            val pkg = shellEscape(packageName)
            return """
                component="${'$'}(cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg 2>/dev/null | tail -n 1)"
                if [ -n "${'$'}component" ] && echo "${'$'}component" | grep -q '/'; then
                  am start --user 0 -n "${'$'}component"
                else
                  echo "launcher activity not found for $packageName" >&2
                  exit 1
                fi
            """.trimIndent()
        }

        private fun killApp(target: String?) {
            if (target.isNullOrBlank()) {
                write("usage: kill-app <pid|packageName>\r\n")
                return
            }
            val result = if (target.all(Char::isDigit)) {
                killPid(target)
            } else {
                val forceStop = executeFirstResult(
                    listOf(listOf(shellPath, "-c", "am force-stop ${shellEscape(target)}")) +
                        rootShellCommands("am force-stop ${shellEscape(target)}")
                )
                if (forceStop.code == 0) {
                    forceStop
                } else {
                    val pids = executeFirstText(
                        listOf(listOf(shellPath, "-c", "pidof ${shellEscape(target)}")) +
                            rootShellCommands("pidof ${shellEscape(target)}")
                    )
                        ?.trim()
                        ?.split(Regex("\\s+"))
                        ?.filter { it.all(Char::isDigit) }
                        .orEmpty()
                    if (pids.isEmpty()) {
                        forceStop
                    } else {
                        killPid(pids.joinToString(" "))
                    }
                }
            }
            val ok = result.code == 0
            val message = result.message.ifBlank { "exit ${result.code}" }
                .let { text ->
                    if (!ok && text.contains("Operation not permitted", ignoreCase = true)) {
                        "$text; root/system permission required to kill another app uid"
                    } else {
                        text
                    }
                }
            writeTable(
                listOf("STATUS", "TARGET", "MESSAGE"),
                listOf(listOf(if (ok) "ok" else "failed", target, message))
            )
        }

        private fun killPid(pidOrPids: String): CommandResult {
            val escaped = pidOrPids
                .split(Regex("\\s+"))
                .filter { it.all(Char::isDigit) }
                .joinToString(" ")
            if (escaped.isBlank()) return CommandResult(1, "invalid pid")
            return executeFirstResult(
                listOf(listOf(shellPath, "-c", "kill -9 $escaped")) +
                    rootShellCommands("kill -9 $escaped")
            )
        }

        private fun streamLogs(args: List<String>, reader: LineReader) {
            val options = try {
                parseLogcatOptions(args)
            } catch (e: IllegalArgumentException) {
                write("logs: ${e.message}\r\n")
                return
            }
            val packageName = options.packageName
            val packagePids = java.util.concurrent.atomic.AtomicReference(queryPackagePids(packageName))
            if (!packageName.isNullOrBlank() && packagePids.get().isEmpty()) {
                write("logs: package not running or pid unavailable: $packageName\r\n")
                return
            }
            val command = selectLogcatCommand(options.command())

            write(listOf("LOGS", "STARTED", "stop=q|stop|exit|quit|Ctrl-C").joinToString("\t") + "\r\n")
            val process = try {
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: IOException) {
                write("logs: ${e.message ?: "failed to start logcat"}\r\n")
                return
            }

            val streaming = AtomicBoolean(true)
            val thread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { logReader ->
                        while (streaming.get()) {
                            val line = logReader.readLine() ?: break
                            if (!packageName.isNullOrBlank()) {
                                val linePid = LOGCAT_THREADTIME_REGEX.matchEntire(line)?.groupValues?.getOrNull(3)
                                if (linePid == null || linePid !in packagePids.get()) continue
                            }
                            write("${colorLogLine(line)}\r\n")
                        }
                    }
                } catch (_: IOException) {
                }
            }
            thread.name = "ssh-logcat-stream"
            thread.isDaemon = true
            thread.start()

            val pidRefreshThread = if (!packageName.isNullOrBlank()) Thread {
                while (streaming.get()) {
                    try {
                        Thread.sleep(LOGCAT_PID_REFRESH_MILLIS)
                        packagePids.set(queryPackagePids(packageName))
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }.apply {
                name = "ssh-logcat-pid-refresh"
                isDaemon = true
                start()
            } else null

            while (running.get() && streaming.get()) {
                val stop = reader.readLine() ?: break
                val normalized = stop.trim()
                if (normalized == "q" ||
                    normalized == "stop" ||
                    normalized == "exit" ||
                    normalized == "quit" ||
                    normalized == "\u0003"
                ) {
                    break
                }
            }

            streaming.set(false)
            pidRefreshThread?.interrupt()
            process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
            thread.join(1_000)
            pidRefreshThread?.join(1_000)
            write(listOf("LOGS", "STOPPED").joinToString("\t") + "\r\n")
        }

        private fun selectLogcatCommand(command: List<String>): List<String> {
            val shellCommand = command.joinToString(" ", transform = ::shellEscape)
            return rootShellCommands("logcat -b all -d -t 1 >/dev/null 2>&1")
                .firstOrNull { executeForText(it, timeoutSeconds = 2) != null }
                ?.let { root -> root.dropLast(1) + shellCommand }
                ?: command
        }

        private fun queryPackagePids(packageName: String?): Set<String> {
            if (packageName.isNullOrBlank()) return emptySet()
            val escaped = shellEscape(packageName)
            val text = executeFirstText(
                listOf(
                    listOf(shellPath, "-c", "pidof $escaped"),
                    listOf(shellPath, "-c", "ps -A -o PID,NAME | grep $escaped"),
                ),
                timeoutSeconds = 2,
            ).orEmpty()
            return Regex("\\b\\d+\\b").findAll(text).map { it.value }.toSet()
        }

        private fun colorLogLine(line: String): String {
            val match = LOGCAT_THREADTIME_REGEX.matchEntire(line) ?: return line
            val date = match.groupValues[1]
            val time = match.groupValues[2]
            val pid = match.groupValues[3]
            val tid = match.groupValues[4]
            val level = match.groupValues[5]
            val tag = match.groupValues[6].trim()
            val message = match.groupValues[7]
            val tagColor = tagColor(tag)
            return buildString {
                append(colorMuted(date))
                append(' ')
                append(colorMuted(time))
                append(' ')
                append(colorMuted(pid.padStart(5)))
                append(' ')
                append(colorMuted(tid.padStart(5)))
                append(' ')
                append(colorLogLevel(level))
                append(' ')
                append(color(tag.padEnd(24), tagColor))
                append(": ")
                append(colorLogMessage(level, message))
            }
        }

        private fun tagColor(tag: String): String {
            if (tag.isBlank()) return ANSI_CYAN
            return LOG_TAG_COLORS[(tag.hashCode() and Int.MAX_VALUE) % LOG_TAG_COLORS.size]
        }

        private fun colorLogLevel(level: String): String {
            return when (level.uppercase(Locale.US)) {
                "E", "F" -> color(level, ANSI_RED)
                "W" -> color(level, ANSI_YELLOW)
                "I" -> color(level, ANSI_GREEN)
                "D" -> color(level, ANSI_CYAN)
                "V" -> color(level, ANSI_DIM)
                else -> level
            }
        }

        private fun colorLogMessage(level: String, message: String): String {
            return when (level.uppercase(Locale.US)) {
                "E", "F" -> color(message, ANSI_RED)
                "W" -> color(message, ANSI_YELLOW)
                else -> message
            }
        }

        private fun findPid(packageName: String): String? {
            return try {
                val process = ProcessBuilder(shellPath, "-c", "pidof $packageName")
                    .redirectErrorStream(true)
                    .start()
                val result = process.inputStream.readAllText()
                if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return null
                }
                result.trim().split(Regex("\\s+")).firstOrNull { it.isNotBlank() }
            } catch (_: Throwable) {
                null
            }
        }

        private fun queryProcessStat(packageName: String): String? {
            val pid = findPid(packageName) ?: return null
            val stat = File("/proc/$pid/stat")
            return runCatching {
                if (stat.isFile) stat.readText() else null
            }.getOrNull()
        }

        private fun optionValue(args: List<String>, longName: String, shortName: String): String? {
            val longIndex = args.indexOf(longName)
            if (longIndex >= 0) return args.getOrNull(longIndex + 1)
            val shortIndex = args.indexOf(shortName)
            if (shortIndex >= 0) return args.getOrNull(shortIndex + 1)
            return args.firstOrNull { it.startsWith("$longName=") }?.substringAfter("=")
        }

        private fun installApk(path: String?) {
            val target = resolveRequiredPath(path, "install-apk") ?: return
            if (!target.isFile) {
                write("install-apk: ${target.path}: not a file\r\n")
                return
            }

            val apk = shellEscape(target.absolutePath)
            val tmpApk = "/data/local/tmp/${target.name}"
            val tmp = shellEscape(tmpApk)
            val result = executeFirstResult(
                listOf(
                    listOf(shellPath, "-c", "pm install -r --user 0 $apk"),
                    listOf(shellPath, "-c", "cmd package install -r --user 0 $apk"),
                ) + rootShellCommands("pm install -r --user 0 $apk") +
                    rootShellCommands("cp $apk $tmp && chmod 644 $tmp && pm install -r --user 0 $tmp; code=\$?; rm -f $tmp; exit \$code")
            )
            if (result.code == 0) {
                writeTable(
                    listOf("STATUS", "APK"),
                    listOf(listOf("ok", target.absolutePath))
                )
            } else {
                writeTable(
                    listOf("STATUS", "REASON"),
                    listOf(
                        listOf(
                            "failed",
                            "silent install requires system/privileged permission or root; ${result.message.ifBlank { "pm install failed" }}"
                        )
                    )
                )
            }
        }

        private fun uninstallApk(packageName: String?) {
            if (packageName.isNullOrBlank()) {
                write("usage: uninstall-apk <packageName>\r\n")
                return
            }
            val pkg = shellEscape(packageName)
            val result = executeFirstResult(
                listOf(
                    listOf(shellPath, "-c", "pm uninstall --user 0 $pkg"),
                    listOf(shellPath, "-c", "cmd package uninstall --user 0 $pkg"),
                ) + rootShellCommands("pm uninstall --user 0 $pkg")
            )
            if (result.code == 0) {
                writeTable(listOf("STATUS", "PACKAGE"), listOf(listOf("ok", packageName)))
            } else {
                writeTable(
                    listOf("STATUS", "REASON"),
                    listOf(listOf("failed", result.message.ifBlank { "pm uninstall failed" }))
                )
            }
        }

        private fun downloadFile(url: String?, destination: String?) {
            if (url.isNullOrBlank() || destination.isNullOrBlank()) {
                write("usage: download <url> <dest>\r\n")
                return
            }
            try {
                val sourceUrl = URL(url)
                val dst = resolveDownloadDestination(sourceUrl, destination)
                dst.parentFile?.mkdirs()
                val connection = sourceUrl.openConnection()
                connection.connectTimeout = DOWNLOAD_TIMEOUT_MILLIS
                connection.readTimeout = DOWNLOAD_TIMEOUT_MILLIS
                if (connection is HttpURLConnection) {
                    connection.instanceFollowRedirects = true
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        write("download failed: http $code\r\n")
                        connection.disconnect()
                        return
                    }
                }
                val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
                val bytes = copyWithDownloadProgress(connection, dst, totalBytes)
                if (connection is HttpURLConnection) {
                    connection.disconnect()
                }
                writeTable(
                    listOf("STATUS", "PATH", "BYTES"),
                    listOf(listOf("ok", dst.absolutePath, bytes.toString()))
                )
            } catch (_: TransferInterruptedException) {
                runCatching {
                    val sourceUrl = URL(url)
                    resolveDownloadDestination(sourceUrl, destination).delete()
                }
                write("^C\r\n")
            } catch (e: Throwable) {
                write("download failed: ${e.message ?: e.javaClass.simpleName}\r\n")
            }
        }

        private fun screenshot(path: String?) {
            val target = resolvePath(path ?: "screenshot-${System.currentTimeMillis()}.png")
            target.parentFile?.mkdirs()
            val command = "screencap -p ${shellEscape(target.absolutePath)}"
            val result = executeFirstResult(listOf(listOf(shellPath, "-c", command)) + rootShellCommands(command))
            if (result.code == 0 && target.isFile) {
                writeTable(listOf("STATUS", "PATH", "BYTES"), listOf(listOf("ok", target.absolutePath, target.length().toString())))
            } else {
                writeTable(listOf("STATUS", "REASON"), listOf(listOf("failed", result.message.ifBlank { "screencap failed; may require shell/root permission" })))
            }
        }

        private fun queryHardware() {
            val rows = mutableListOf<List<String>>()
            fun add(key: String, value: String?) {
                if (!value.isNullOrBlank()) rows += listOf(key, value.trim())
            }
            val props = getProperties()
            add("manufacturer", props["ro.product.manufacturer"])
            add("brand", props["ro.product.brand"])
            add("model", props["ro.product.model"])
            add("device", props["ro.product.device"])
            add("board", props["ro.product.board"])
            add("hardware", props["ro.hardware"] ?: props["ro.boot.hardware"])
            add("platform", props["ro.board.platform"])
            add("soc_manufacturer", props["ro.soc.manufacturer"])
            add("soc_model", props["ro.soc.model"])
            add("cpu_abi", props["ro.product.cpu.abi"])
            add("cpu_abilist", props["ro.product.cpu.abilist"])
            add("serial", props["ro.serialno"] ?: props["ro.boot.serialno"])
            add("android", props["ro.build.version.release"])
            add("sdk", props["ro.build.version.sdk"])
            add("build", props["ro.build.display.id"])
            add("kernel", executeForText(listOf(shellPath, "-c", "uname -r"))?.trim())
            add("cpu_cores", File("/sys/devices/system/cpu").listFiles { file -> file.name.matches(Regex("""cpu\d+""")) }?.size?.toString())
            parseMemTotalKb()?.let { add("memory_total", formatKb(it)) }
            val gpuRows = collectGpuInfo()
            val npuRows = collectNpuInfo()
            add("gpu", acceleratorSummary(gpuRows))
            add("npu", acceleratorSummary(npuRows))
            if (rows.isEmpty()) {
                write("hardware query failed\r\n")
            } else {
                writeLinuxKeyValueRows(rows)
            }
        }

        private fun queryGpuInfo() {
            val rows = collectGpuInfo().ifEmpty { unavailableAcceleratorRows("GPU") }
            writeTableTitle("GPU")
            writeLinuxKeyValueRows(formatAcceleratorRows(rows))
        }

        private fun queryNpuInfo() {
            val rows = collectNpuInfo().ifEmpty { unavailableAcceleratorRows("NPU/APU") }
            writeTableTitle("NPU")
            writeLinuxKeyValueRows(formatAcceleratorRows(rows))
        }

        private fun collectGpuInfo(): List<List<String>> {
            if (!shellPath.startsWith("/")) return emptyList()
            val rows = mutableListOf<List<String>>()
            if (File("/sys/class/misc/mali0").exists()) {
                rows += listOf("type", "Mali GPU")
                rows += listOf("vendor", "ARM")
                rows += listOf("path", "/sys/class/misc/mali0")
                return withAcceleratorUsage(rows.distinctBy { it.firstOrNull() })
            }
            val props = getProperties()
            props["ro.hardware.egl"]?.takeIf(String::isNotBlank)?.let { rows += listOf("egl", it) }
            props["ro.opengles.version"]?.takeIf(String::isNotBlank)?.let { rows += listOf("opengles_version", it) }
            val usageScript = """
                for p in /sys/devices/platform/fb000000.gpu/utilisation /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage; do
                  if [ -r "${'$'}p" ]; then value="${'$'}(cat "${'$'}p" 2>/dev/null | tr '\n' ' ')"; [ -n "${'$'}value" ] && echo "utilisation=${'$'}value"; break; fi
                done
            """.trimIndent()
            rows += collectAcceleratorSysfsRows(
                directoryPatterns = "/sys/class/devfreq/*gpu* /sys/devices/platform/*gpu*/devfreq/* /sys/devices/platform/gpufreq /sys/class/misc/mali0/device/devfreq/*",
                extraScript = usageScript,
            )
            return withAcceleratorUsage(rows.distinctBy { it.firstOrNull() })
        }

        private fun collectNpuInfo(): List<List<String>> {
            if (!shellPath.startsWith("/")) return emptyList()
            val loadPaths = RKNN_LOAD_PATHS.joinToString(" ")
            val frequencyPaths = RKNN_FREQUENCY_PATHS.joinToString(" ")
            val extra = """
                if [ -e /dev/rknpu ]; then echo "device=/dev/rknpu"; fi
                for p in /sys/module/rknpu/version /sys/kernel/debug/rknpu/driver_version /proc/rknpu/driver_version; do
                  if [ -r "${'$'}p" ]; then value="${'$'}(cat "${'$'}p" 2>/dev/null | tr '\n' ' ')"; [ -n "${'$'}value" ] && echo "driver=${'$'}value"; break; fi
                done
                for p in $loadPaths; do
                  if [ -r "${'$'}p" ]; then value="${'$'}(cat "${'$'}p" 2>/dev/null | tr '\n' ' ')"; [ -n "${'$'}value" ] && echo "core_load=${'$'}value"; break; fi
                done
                for p in $frequencyPaths; do
                  if [ -r "${'$'}p" ]; then value="${'$'}(cat "${'$'}p" 2>/dev/null | tr '\n' ' ')"; [ -n "${'$'}value" ] && echo "debug_freq=${'$'}value"; break; fi
                done
            """.trimIndent()
            val rows = collectAcceleratorSysfsRows(
                directoryPatterns = "/sys/class/devfreq/*npu* /sys/devices/platform/*npu*/devfreq/* /sys/devices/platform/*apu*/devfreq/* /sys/devices/platform/*mdla*/devfreq/*",
                extraScript = extra,
            ).distinctBy { it.firstOrNull() }
            if (rows.isNotEmpty()) return withAcceleratorUsage(rows)
            val mediatekNode = listOf(
                "/sys/devices/platform/apusys",
                "/sys/devices/platform/19034000.mdla",
                "/sys/devices/platform/apu_debug.0",
            ).firstOrNull { File(it).exists() }
            return if (mediatekNode != null) {
                unavailableAcceleratorRows("NPU/APU", "MediaTek") + listOf(listOf("path", mediatekNode))
            } else {
                emptyList()
            }
        }

        private fun collectAcceleratorSysfsRows(
            directoryPatterns: String,
            extraScript: String,
        ): List<List<String>> {
            val script = """
                base=""
                for d in $directoryPatterns; do
                  if [ -d "${'$'}d" ]; then base="${'$'}d"; break; fi
                done
                if [ -n "${'$'}base" ]; then
                  echo "path=${'$'}base"
                  for key in name governor cur_freq min_freq max_freq available_frequencies load cur_load busy utilisation utilization gpu_busy_percentage usage; do
                    file="${'$'}base/${'$'}key"
                    if [ -r "${'$'}file" ]; then
                      value="${'$'}(cat "${'$'}file" 2>/dev/null | tr '\n' ' ')"
                      [ -n "${'$'}value" ] && echo "${'$'}key=${'$'}value"
                    fi
                  done
                fi
                $extraScript
            """.trimIndent()
            val text = executeRootFirstText(script, timeoutSeconds = 5) ?: return emptyList()
            return parseAcceleratorKeyValueRows(text)
        }

        private fun executeRootFirstText(command: String, timeoutSeconds: Long): String? {
            executeForText(listOf(shellPath, "-c", command), timeoutSeconds)
                ?.takeIf(String::isNotBlank)?.let { return it }
            val rootCandidates = if (File("/system/bin/su").canExecute() || File("/system/xbin/su").canExecute()) {
                rootShellCommands(command).take(2)
            } else {
                emptyList()
            }
            rootCandidates.forEach { candidate ->
                executeForText(candidate, 1)?.takeIf(String::isNotBlank)?.let { return it }
            }
            return null
        }

        private fun acceleratorSummary(rows: List<List<String>>): String? {
            if (rows.isEmpty()) return null
            val values = rows.associate { row -> row.getOrElse(0) { "" } to row.getOrElse(1) { "" } }
            val identity = values["renderer"] ?: values["name"] ?: values["device"] ?: values["path"]
            val frequency = values["cur_freq"]?.let(::formatAcceleratorFrequency)
            val usage = values["usage_percent"]?.let { "usage $it" }
            return listOfNotNull(identity, frequency, usage).filter(String::isNotBlank).joinToString(" @ ").ifBlank { null }
        }

        private fun queryWifiInfo() {
            val rows = mutableListOf<List<String>>()
            fun add(key: String, value: String?) {
                if (!value.isNullOrBlank()) rows += listOf(key, value.trim())
            }
            val props = getProperties()
            add("wifi.interface", props["wifi.interface"] ?: props["wifi.interface.primary"])
            add("dhcp.wlan0.ipaddress", props["dhcp.wlan0.ipaddress"])
            add("dhcp.wlan0.gateway", props["dhcp.wlan0.gateway"])
            add("dhcp.wlan0.dns1", props["dhcp.wlan0.dns1"])
            add("dhcp.wlan0.dns2", props["dhcp.wlan0.dns2"])
            add("dhcp.wlan0.mask", props["dhcp.wlan0.mask"])
            add("dhcp.wlan0.server", props["dhcp.wlan0.server"])
            val wifiDump = executeFirstText(
                listOf(
                    listOf(shellPath, "-c", "cmd wifi status"),
                    listOf(shellPath, "-c", "dumpsys wifi"),
                ),
                timeoutSeconds = 8,
            )
            if (!wifiDump.isNullOrBlank()) {
                parseWifiDumpRows(wifiDump).forEach { row ->
                    if (rows.none { it[0] == row[0] }) rows += row
                }
            }
            if (rows.isEmpty()) {
                write("wifi-info query failed\r\n")
            } else {
                writeLinuxKeyValueRows(rows)
            }
        }

        private fun queryUsbInfo() {
            val rows = readUsbDeviceRows()
            if (rows.isNotEmpty()) {
                writeTableTitle("USB_DEVICES count=${rows.size}")
                writeTable(
                    listOf("BUS", "DEV", "VID", "PID", "MANUFACTURER", "PRODUCT", "SERIAL", "CLASS", "SPEED", "PATH"),
                    rows,
                )
                return
            }
            val fallback = executeForText(listOf(shellPath, "-c", "lsusb"), timeoutSeconds = 5)
            if (!fallback.isNullOrBlank()) {
                val fallbackRows = normalizedLines(fallback).map(::parseLsusbLine).toList()
                writeTableTitle("USB_DEVICES count=${fallbackRows.size}")
                writeTable(listOf("BUS", "DEV", "VID", "PID", "DETAIL"), fallbackRows)
            } else {
                write("usb query failed\r\n")
            }
        }

        private fun queryCameras(cameraId: String? = null) {
            val cameras = runCatching { cameraResolver() }.getOrDefault(emptyList())
            if (!cameraId.isNullOrBlank() && cameras.isNotEmpty()) {
                val camera = cameras.firstOrNull { it.id == cameraId }
                if (camera == null) {
                    write("camera-info: camera ID not found: $cameraId\r\n")
                    write("available IDs: ${cameras.joinToString(", ") { it.id }}\r\n")
                    return
                }
                writeTableTitle("CAMERA")
                writeLinuxKeyValueRows(cameraDetailRows(camera))
                return
            }
            if (!cameraId.isNullOrBlank()) {
                write("camera-info: camera details unavailable for ID: $cameraId\r\n")
                return
            }
            val apiRows = cameras
                .map { info ->
                    listOf(
                        info.id,
                        info.facing,
                        info.orientation,
                        info.hardwareLevel,
                        info.flash,
                        info.autofocus,
                        info.fpsRanges,
                        info.photoSizes,
                        info.videoSizes,
                        info.capabilities,
                    )
                }
            if (apiRows.isNotEmpty()) {
                writeTableTitle("CAMERAS count=${apiRows.size}")
                writeTable(
                    listOf("ID", "FACING", "ORIENTATION", "LEVEL", "FLASH", "AF", "FPS", "PHOTO_SIZES", "VIDEO_SIZES", "CAPABILITIES"),
                    apiRows,
                )
                return
            }
            val dump = executeFirstText(
                listOf(
                    listOf(shellPath, "-c", "dumpsys media.camera"),
                    listOf(shellPath, "-c", "dumpsys camera"),
                ),
                timeoutSeconds = 8,
            )
            val rows = dump?.let(::parseCameraRows).orEmpty()
            if (rows.isEmpty()) {
                val count = getProperties()["vendor.camera.aux.packagelist"]?.takeIf { it.isNotBlank() }?.let { "unknown" }
                if (count != null) {
                    writeTable(listOf("ID", "FACING", "ORIENTATION", "DETAIL"), listOf(listOf("-", "-", "-", "camera service visible but camera list unavailable")))
                } else {
                    write("cameras query failed\r\n")
                }
            } else {
                writeTableTitle("CAMERAS count=${rows.size}")
                writeTable(listOf("ID", "FACING", "ORIENTATION", "DETAIL"), rows)
            }
        }

        private fun cameraDetailRows(info: CameraInfo): List<List<String>> = listOf(
            listOf("id", info.id),
            listOf("facing", info.facing),
            listOf("orientation", info.orientation),
            listOf("hardware_level", info.hardwareLevel),
            listOf("flash", info.flash),
            listOf("autofocus", info.autofocus),
            listOf("fps_ranges", info.fpsRanges),
            listOf("photo_sizes", info.photoSizes),
            listOf("video_sizes", info.videoSizes),
            listOf("capabilities", info.capabilities),
        )

        private fun queryVolume() {
            val apiRows = runCatching { volumeResolver() }.getOrDefault(emptyList())
                .map { info -> listOf(info.stream, info.min, info.current, info.max, info.muted) }
            if (apiRows.isNotEmpty()) {
                writeTable(listOf("STREAM", "MIN", "CURRENT", "MAX", "MUTED"), apiRows)
                return
            }
            val dump = executeForText(listOf(shellPath, "-c", "dumpsys audio"), timeoutSeconds = 8)
            val rows = dump?.let(::parseVolumeRows).orEmpty()
            if (rows.isNotEmpty()) {
                writeTable(listOf("STREAM", "MIN", "CURRENT", "MAX", "MUTED"), rows)
                return
            }
            val fallback = executeForText(listOf(shellPath, "-c", "cmd media_session volume --show"), timeoutSeconds = 5)
            if (fallback.isNullOrBlank()) {
                write("volume query failed\r\n")
            } else {
                writeWhitespaceTable(fallback)
            }
        }

        private fun queryBrightness() {
            val rows = mutableListOf<List<String>>()
            fun add(key: String, value: String?) {
                if (!value.isNullOrBlank()) rows += listOf(key, value.trim())
            }
            add("screen_brightness", executeForText(listOf(shellPath, "-c", "settings get system screen_brightness"))?.trim())
            add("screen_brightness_mode", executeForText(listOf(shellPath, "-c", "settings get system screen_brightness_mode"))?.trim()?.let { if (it == "1") "automatic" else "manual" })
            add("screen_auto_brightness_adj", executeForText(listOf(shellPath, "-c", "settings get system screen_auto_brightness_adj"))?.trim())
            add("brightness_file", readFirstExistingText(
                listOf(
                    "/sys/class/backlight/panel0-backlight/brightness",
                    "/sys/class/backlight/backlight/brightness",
                    "/sys/class/leds/lcd-backlight/brightness",
                )
            ))
            add("max_brightness_file", readFirstExistingText(
                listOf(
                    "/sys/class/backlight/panel0-backlight/max_brightness",
                    "/sys/class/backlight/backlight/max_brightness",
                    "/sys/class/leds/lcd-backlight/max_brightness",
                )
            ))
            if (rows.isEmpty()) {
                write("brightness query failed\r\n")
            } else {
                writeLinuxKeyValueRows(rows)
            }
        }

        private fun querySystemTime() {
            val now = System.currentTimeMillis()
            val timeZone = java.util.TimeZone.getDefault()
            val localTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                this.timeZone = timeZone
            }.format(Date(now))
            val automatic = executeForText(listOf(shellPath, "-c", "settings get global auto_time"), timeoutSeconds = 3)
                ?.trim()
                ?.let { if (it == "1") "enabled" else "disabled" }
                ?: "unknown"
            writeTableTitle("SYSTEM_TIME")
            writeLinuxKeyValueRows(
                listOf(
                    listOf("local_time", localTime),
                    listOf("timezone", timeZone.id),
                    listOf("epoch_ms", now.toString()),
                    listOf("automatic", automatic),
                )
            )
        }

        private fun setSystemTime(value: String) {
            val parsed = parseSystemTime(value)
            if (parsed == null) {
                write("usage: set-system-time <yyyy-MM-dd HH:mm:ss>\r\n")
                return
            }
            val commands = rootShellCommands("date -s '${parsed.display}'") +
                rootShellCommands("date ${parsed.toyboxFallback}")
            val result = executeFirstResult(commands)
            if (result.code != 0) {
                write("set-system-time: root/system permission required: ${result.message.ifBlank { "exit ${result.code}" }}\r\n")
                return
            }
            querySystemTime()
        }

        private fun getProperties(): Map<String, String> {
            val text = executeForText(listOf(shellPath, "-c", "getprop")) ?: return emptyMap()
            return normalizedLines(text).mapNotNull { line ->
                val match = GETPROP_REGEX.matchEntire(line) ?: return@mapNotNull null
                match.groupValues[1] to match.groupValues[2]
            }.toMap()
        }

        private fun parseMemTotalKb(): Long? {
            return File("/proc/meminfo").takeIf { it.isFile }?.useLines { lines ->
                lines.firstOrNull { it.startsWith("MemTotal:") }
                    ?.split(Regex("\\s+"))
                    ?.getOrNull(1)
                    ?.toLongOrNull()
            }
        }

        private fun parseWifiDumpRows(text: String): List<List<String>> {
            val wanted = listOf(
                "Wi-Fi is",
                "Wifi is",
                "mWifiInfo",
                "SSID",
                "BSSID",
                "Supplicant state",
                "RSSI",
                "Link speed",
                "Frequency",
                "IP address",
                "MAC address",
                "Network ID",
            )
            return normalizedLines(text)
                .filter { line -> wanted.any { key -> line.contains(key, ignoreCase = true) } }
                .take(40)
                .map { line ->
                    val index = keyValueSeparatorIndex(line)
                    if (index > 0) {
                        listOf(line.substring(0, index).trim(), line.substring(index + 1).trim())
                    } else {
                        val key = wanted.firstOrNull { line.contains(it, ignoreCase = true) } ?: "wifi"
                        listOf(key, line)
                    }
                }
                .toList()
        }

        private fun readUsbDeviceRows(): List<List<String>> {
            val root = File("/sys/bus/usb/devices")
            val devices = root.listFiles().orEmpty()
                .filter { device ->
                    device.isDirectory &&
                        File(device, "idVendor").isFile &&
                        File(device, "idProduct").isFile
                }
                .sortedWith(compareBy<File> { readSmallFile(File(it, "busnum")).toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { readSmallFile(File(it, "devnum")).toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it.name })
            return devices.map { device ->
                listOf(
                    readSmallFile(File(device, "busnum")).ifBlank { "-" },
                    readSmallFile(File(device, "devnum")).ifBlank { "-" },
                    readSmallFile(File(device, "idVendor")).ifBlank { "-" },
                    readSmallFile(File(device, "idProduct")).ifBlank { "-" },
                    readSmallFile(File(device, "manufacturer")).ifBlank { "-" },
                    readSmallFile(File(device, "product")).ifBlank { "-" },
                    readSmallFile(File(device, "serial")).ifBlank { "-" },
                    usbClassName(readSmallFile(File(device, "bDeviceClass"))),
                    readSmallFile(File(device, "speed")).ifBlank { "-" },
                    device.name,
                )
            }
        }

        private fun readSmallFile(file: File): String {
            return runCatching {
                if (file.isFile && file.canRead()) file.readText().trim() else ""
            }.getOrDefault("")
        }

        private fun usbClassName(value: String): String {
            val code = value.trim().lowercase(Locale.US)
            val name = when (code) {
                "00" -> "per-interface"
                "01" -> "audio"
                "02" -> "communication"
                "03" -> "hid"
                "05" -> "physical"
                "06" -> "image"
                "07" -> "printer"
                "08" -> "mass-storage"
                "09" -> "hub"
                "0a" -> "cdc-data"
                "0b" -> "smart-card"
                "0d" -> "content-security"
                "0e" -> "video"
                "0f" -> "personal-healthcare"
                "10" -> "audio-video"
                "11" -> "billboard"
                "dc" -> "diagnostic"
                "e0" -> "wireless"
                "ef" -> "misc"
                "fe" -> "application"
                "ff" -> "vendor"
                else -> ""
            }
            return when {
                code.isBlank() -> "-"
                name.isBlank() -> code
                else -> "$code/$name"
            }
        }

        private fun parseLsusbLine(line: String): List<String> {
            val match = LSUSB_REGEX.find(line)
            return if (match == null) {
                listOf("-", "-", "-", "-", line)
            } else {
                listOf(
                    match.groupValues[1],
                    match.groupValues[2],
                    match.groupValues[3],
                    match.groupValues[4],
                    match.groupValues[5].ifBlank { "-" },
                )
            }
        }

        private fun parseCameraRows(text: String): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            val seen = mutableSetOf<String>()
            normalizedLines(text).forEach { line ->
                val id = CAMERA_ID_REGEX.find(line)?.groupValues?.getOrNull(1)
                    ?: CAMERA_DEVICE_REGEX.find(line)?.groupValues?.getOrNull(1)
                if (!id.isNullOrBlank() && seen.add(id)) {
                    rows += listOf(
                        id,
                        cameraFieldNear(text, id, CAMERA_FACING_REGEX).ifBlank { "-" },
                        cameraFieldNear(text, id, CAMERA_ORIENTATION_REGEX).ifBlank { "-" },
                        line.take(120),
                    )
                }
            }
            return rows.sortedBy { it[0].toIntOrNull() ?: Int.MAX_VALUE }
        }

        private fun cameraFieldNear(text: String, id: String, regex: Regex): String {
            val lines = normalizedLines(text).toList()
            val index = lines.indexOfFirst { it.contains("Camera ID: $id", ignoreCase = true) || it.contains("Camera $id", ignoreCase = true) }
            if (index < 0) return ""
            return lines.drop(index).take(30).firstNotNullOfOrNull { line ->
                regex.find(line)?.groupValues?.getOrNull(1)
            }.orEmpty()
        }

        private fun parseVolumeRows(text: String): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            var stream: String? = null
            var min = "-"
            var current = "-"
            var max = "-"
            var muted = "-"
            fun flush() {
                val name = stream ?: return
                rows += listOf(name, min, current, max, muted)
                min = "-"
                current = "-"
                max = "-"
                muted = "-"
            }
            normalizedLines(text).forEach { line ->
                val streamMatch = VOLUME_STREAM_REGEX.find(line)
                if (streamMatch != null) {
                    flush()
                    stream = streamMatch.groupValues[1].ifBlank { streamMatch.groupValues[2] }.trim()
                    return@forEach
                }
                VOLUME_MIN_REGEX.find(line)?.groupValues?.getOrNull(1)?.let { min = it }
                VOLUME_MAX_REGEX.find(line)?.groupValues?.getOrNull(1)?.let { max = it }
                VOLUME_CURRENT_REGEX.find(line)?.groupValues?.getOrNull(1)?.let { current = it }
                VOLUME_MUTED_REGEX.find(line)?.groupValues?.getOrNull(1)?.let { muted = it }
            }
            flush()
            return rows.distinctBy { it[0] }.take(20)
        }

        private fun readFirstExistingText(paths: List<String>): String? {
            return paths.firstNotNullOfOrNull { path ->
                runCatching {
                    File(path).takeIf { it.isFile && it.canRead() }?.readText()?.trim()
                }.getOrNull()
            }
        }

        private fun copyWithDownloadProgress(
            connection: java.net.URLConnection,
            destination: File,
            totalBytes: Long?,
        ): Long {
            writeDownloadProgress(0L, totalBytes, final = false)
            val downloaded = connection.getInputStream().use { input ->
                destination.outputStream().use { output ->
                    copyInterruptibly(input, output, totalBytes) { copied, total ->
                        writeDownloadProgress(copied, total, final = false)
                    }
                }
            }
            writeDownloadProgress(downloaded, totalBytes, final = true)
            return downloaded
        }

        private class TransferInterruptedException : IOException()

        private fun copyInterruptibly(
            input: InputStream,
            output: OutputStream,
            totalBytes: Long?,
            onProgress: (copied: Long, totalBytes: Long?) -> Unit,
        ): Long {
            var copied = 0L
            var lastProgressAt = 0L
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
                if (consumeCtrlC()) throw TransferInterruptedException()
                val count = input.read(buffer)
                if (count == -1) break
                if (count <= 0) continue
                output.write(buffer, 0, count)
                copied += count
                val now = System.currentTimeMillis()
                if (now - lastProgressAt >= DOWNLOAD_PROGRESS_INTERVAL_MILLIS) {
                    onProgress(copied, totalBytes)
                    lastProgressAt = now
                }
            }
            return copied
        }

        private fun writeDownloadProgress(downloaded: Long, totalBytes: Long?, final: Boolean) {
            val progress = if (totalBytes != null && totalBytes > 0L) {
                val percent = ((downloaded * 100) / totalBytes).coerceIn(0, 100)
                "$percent%\t$downloaded/$totalBytes bytes"
            } else {
                "$downloaded bytes"
            }
            write("\rPROGRESS\t$progress")
            if (final) write("\r\n")
        }

        private fun writeCopyProgress(label: String, copied: Long, totalBytes: Long?, final: Boolean) {
            val progress = if (totalBytes != null && totalBytes > 0L) {
                val percent = ((copied * 100) / totalBytes).coerceIn(0, 100)
                "$percent%\t$copied/$totalBytes bytes"
            } else {
                "$copied bytes"
            }
            write("\r$label\t$progress")
            if (final) write("\r\n")
        }

        private fun resolveDownloadDestination(url: URL, destination: String): File {
            val target = resolvePath(destination)
            val isDirectoryTarget = destination.endsWith("/") ||
                destination.endsWith("\\") ||
                target.isDirectory
            if (!isDirectoryTarget) return target

            val fileName = url.path
                .substringAfterLast('/')
                .takeIf { it.isNotBlank() }
                ?: "download.bin"
            return File(target, fileName)
        }

        private fun rebootDevice() {
            executeFirstAvailable(
                listOf(
                    listOf(shellPath, "-c", "reboot"),
                ) + rootShellCommands("reboot"),
                "reboot failed: permission denied or command unavailable",
            )
        }

        private data class SqliteTarget(
            val db: File,
            val rest: List<String>,
        )

        private fun sqliteListDbs(path: String?) {
            val dir = path?.let(::resolvePath) ?: cwd
            if (!dir.isDirectory) {
                write("sqlite-dbs: ${dir.absolutePath}: not a directory\r\n")
                return
            }
            val rows = dir.listFiles()
                ?.filter { file -> file.isFile && file.extension.lowercase(Locale.US) in SQLITE_EXTENSIONS }
                ?.sortedBy { it.name }
                ?.map { file ->
                    listOf(file.name, file.length().toString(), DATE_FORMAT.get()!!.format(Date(file.lastModified())), file.absolutePath)
                }
                .orEmpty()
            if (rows.isEmpty()) {
                write("empty\r\n")
            } else {
                writeTable(listOf("NAME", "SIZE", "MODIFIED", "PATH"), rows)
            }
        }

        private fun sqliteCreateDb(path: String?) {
            val db = resolveRequiredPath(path, "sqlite-create-db") ?: return
            try {
                db.parentFile?.mkdirs()
                val createdByAndroid = runCatching {
                    SQLiteDatabase.openOrCreateDatabase(db.absolutePath, null).close()
                    true
                }.getOrDefault(false)
                if (!createdByAndroid && !db.exists()) {
                    db.createNewFile()
                }
                write("ok\r\n")
            } catch (e: Throwable) {
                write("sqlite-create-db failed: ${e.message ?: e.javaClass.simpleName}\r\n")
            }
        }

        private fun sqliteDeleteDb(path: String?) {
            val db = resolveRequiredPath(path, "sqlite-delete-db") ?: return
            val related = listOf(db, File("${db.absolutePath}-wal"), File("${db.absolutePath}-shm"), File("${db.absolutePath}-journal"))
            var failed = false
            related.forEach { file ->
                if (file.exists() && !file.delete()) failed = true
            }
            write(if (failed) "sqlite-delete-db failed\r\n" else "ok\r\n")
        }

        private fun sqliteRenameDb(oldPath: String?, newPath: String?) {
            val oldDb = resolveRequiredPath(oldPath, "sqlite-rename-db") ?: return
            val newDb = resolveRequiredPath(newPath, "sqlite-rename-db") ?: return
            if (!oldDb.exists()) {
                write("sqlite-rename-db: ${oldDb.absolutePath}: database not found\r\n")
                return
            }
            newDb.parentFile?.mkdirs()
            if (!oldDb.renameTo(newDb)) {
                write("sqlite-rename-db failed\r\n")
                return
            }
            renameSqliteSidecar(oldDb, newDb, "-wal")
            renameSqliteSidecar(oldDb, newDb, "-shm")
            renameSqliteSidecar(oldDb, newDb, "-journal")
            write("ok\r\n")
        }

        private fun renameSqliteSidecar(oldDb: File, newDb: File, suffix: String) {
            val oldFile = File("${oldDb.absolutePath}$suffix")
            if (!oldFile.exists()) return
            oldFile.renameTo(File("${newDb.absolutePath}$suffix"))
        }

        private fun sqliteCreateTable(args: List<String>) {
            val target = sqliteTarget(args, "sqlite-create-table") ?: return
            val createSql = target.rest.joinToString(" ").trim()
            if (createSql.startsWith("create table", ignoreCase = true)) {
                sqliteQuery(target.db, createSql)
                return
            }
            val table = target.rest.getOrNull(0)
            val columns = target.rest.drop(1).joinToString(" ").trim()
            if (table.isNullOrBlank() || columns.isBlank()) {
                write("usage: sqlite-create-table [dbPath] <table> <typedColumns> | sqlite-create-table [dbPath] <createSql>\r\n")
                return
            }
            if (!looksLikeTypedSqliteColumns(columns)) {
                write("sqlite-create-table: columns must include SQLite types or constraints, e.g. id INTEGER PRIMARY KEY, name TEXT\r\n")
                return
            }
            sqliteQuery(target.db, "CREATE TABLE IF NOT EXISTS ${quoteSqlIdentifier(table)} ($columns)")
        }

        private fun looksLikeTypedSqliteColumns(columns: String): Boolean {
            val normalized = columns.uppercase(Locale.US)
            return SQLITE_COLUMN_TYPE_OR_CONSTRAINTS.any { keyword ->
                Regex("""(^|[\s,(])${Regex.escape(keyword)}($|[\s,)])""").containsMatchIn(normalized)
            }
        }

        private fun sqliteDropTable(args: List<String>) {
            val target = sqliteTarget(args, "sqlite-drop-table") ?: return
            val table = target.rest.getOrNull(0)
            if (table.isNullOrBlank()) {
                write("usage: sqlite-drop-table [dbPath] <table>\r\n")
                return
            }
            sqliteQuery(target.db, "DROP TABLE IF EXISTS ${quoteSqlIdentifier(table)}")
        }

        private fun sqliteRenameTable(args: List<String>) {
            val target = sqliteTarget(args, "sqlite-rename-table") ?: return
            val oldName = target.rest.getOrNull(0)
            val newName = target.rest.getOrNull(1)
            if (oldName.isNullOrBlank() || newName.isNullOrBlank()) {
                write("usage: sqlite-rename-table [dbPath] <oldTable> <newTable>\r\n")
                return
            }
            sqliteQuery(target.db, "ALTER TABLE ${quoteSqlIdentifier(oldName)} RENAME TO ${quoteSqlIdentifier(newName)}")
        }

        private fun sqliteQueryTable(args: List<String>) {
            val target = sqliteTarget(args, "sqlite-table") ?: return
            val table = target.rest.getOrNull(0)
            val limit = target.rest.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 10_000) ?: 100
            if (table.isNullOrBlank()) {
                write("usage: sqlite-table [dbPath] <table> [limit]\r\n")
                return
            }
            sqliteQuery(target.db, "SELECT * FROM ${quoteSqlIdentifier(table)} LIMIT $limit")
        }

        private fun sqliteColumns(args: List<String>) {
            val target = sqliteTarget(args, "sqlite-columns") ?: return
            val table = target.rest.getOrNull(0)
            if (table.isNullOrBlank()) {
                write("usage: sqlite-columns [dbPath] <table>\r\n")
                return
            }
            sqliteQuery(target.db, "PRAGMA table_info(${quoteSqlString(table)})")
        }

        private fun sqliteAddColumn(args: List<String>) {
            val target = sqliteTarget(args, "sqlite-add-column") ?: return
            val table = target.rest.getOrNull(0)
            val columnDef = target.rest.drop(1).joinToString(" ").trim()
            if (table.isNullOrBlank() || columnDef.isBlank()) {
                write("usage: sqlite-add-column [dbPath] <table> <columnDef>\r\n")
                return
            }
            if (!looksLikeTypedSqliteColumns(columnDef)) {
                write("sqlite-add-column: columnDef must include SQLite type or constraint, e.g. age INTEGER DEFAULT 0\r\n")
                return
            }
            sqliteQuery(target.db, "ALTER TABLE ${quoteSqlIdentifier(table)} ADD COLUMN $columnDef")
        }

        private fun sqliteDropColumn(args: List<String>) {
            val target = sqliteTarget(args, "sqlite-drop-column") ?: return
            val table = target.rest.getOrNull(0)
            val column = target.rest.getOrNull(1)
            if (table.isNullOrBlank() || column.isNullOrBlank()) {
                write("usage: sqlite-drop-column [dbPath] <table> <column>\r\n")
                return
            }
            sqliteQuery(target.db, "ALTER TABLE ${quoteSqlIdentifier(table)} DROP COLUMN ${quoteSqlIdentifier(column)}")
        }

        private fun sqliteRenameColumn(args: List<String>) {
            val target = sqliteTarget(args, "sqlite-rename-column") ?: return
            val table = target.rest.getOrNull(0)
            val oldColumn = target.rest.getOrNull(1)
            val newColumn = target.rest.getOrNull(2)
            if (table.isNullOrBlank() || oldColumn.isNullOrBlank() || newColumn.isNullOrBlank()) {
                write("usage: sqlite-rename-column [dbPath] <table> <oldColumn> <newColumn>\r\n")
                return
            }
            sqliteQuery(
                target.db,
                "ALTER TABLE ${quoteSqlIdentifier(table)} RENAME COLUMN ${quoteSqlIdentifier(oldColumn)} TO ${quoteSqlIdentifier(newColumn)}"
            )
        }

        private fun sqliteModifyColumn(args: List<String>) {
            val target = sqliteTarget(args, "sqlite-modify-column") ?: return
            val table = target.rest.getOrNull(0)
            val oldColumn = target.rest.getOrNull(1)
            val newColumnDef = target.rest.drop(2).joinToString(" ").trim()
            if (table.isNullOrBlank() || oldColumn.isNullOrBlank() || newColumnDef.isBlank()) {
                write("usage: sqlite-modify-column [dbPath] <table> <oldColumn> <newColumnDef>\r\n")
                return
            }
            if (!looksLikeTypedSqliteColumns(newColumnDef)) {
                write("sqlite-modify-column: newColumnDef must include SQLite type or constraint, e.g. fullName TEXT NOT NULL\r\n")
                return
            }
            sqliteModifyColumnWithAndroid(target.db, table, oldColumn, newColumnDef)
        }

        private fun sqliteVersion(args: List<String>) {
            val db = args.firstOrNull()?.let(::resolvePath) ?: defaultSqliteDb() ?: return
            sqliteQuery(db, "PRAGMA user_version")
        }

        private fun sqliteSetVersion(args: List<String>) {
            if (args.isEmpty()) {
                write("usage: sqlite-set-version [dbPath] <version>\r\n")
                return
            }
            val firstPath = resolvePath(args.first())
            val hasDbPath = firstPath.exists() || firstPath.extension.lowercase(Locale.US) in SQLITE_EXTENSIONS
            val db = if (hasDbPath) firstPath else defaultSqliteDb() ?: return
            val versionText = if (hasDbPath) args.getOrNull(1) else args.firstOrNull()
            val version = versionText?.toIntOrNull()
            if (version == null || version < 0) {
                write("usage: sqlite-set-version [dbPath] <version>\r\n")
                return
            }
            sqliteQuery(db, "PRAGMA user_version = $version")
        }

        private fun sqliteModifyColumnWithAndroid(db: File, table: String, oldColumn: String, newColumnDef: String) {
            runCatching {
                SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
                    val columns = readSqliteColumns(database, table)
                    val oldIndex = columns.indexOfFirst { it.name == oldColumn }
                    if (oldIndex < 0) error("column not found: $oldColumn")
                    val newColumnName = newColumnDef.trim().substringBefore(' ').trim('"', '`', '[', ']')
                    if (newColumnName.isBlank()) error("new column name is blank")
                    val tempTable = "__tmp_${table}_${System.currentTimeMillis()}"
                    val newDefinitions = columns.mapIndexed { index, column ->
                        if (index == oldIndex) newColumnDef else column.definition
                    }
                    val sourceColumns = columns.joinToString(", ") { column -> quoteSqlIdentifier(column.name) }
                    val targetColumns = columns.mapIndexed { index, column ->
                        quoteSqlIdentifier(if (index == oldIndex) newColumnName else column.name)
                    }.joinToString(", ")
                    database.beginTransaction()
                    try {
                        database.execSQL("CREATE TABLE ${quoteSqlIdentifier(tempTable)} (${newDefinitions.joinToString(", ")})")
                        database.execSQL(
                            "INSERT INTO ${quoteSqlIdentifier(tempTable)} ($targetColumns) " +
                                "SELECT $sourceColumns FROM ${quoteSqlIdentifier(table)}"
                        )
                        database.execSQL("DROP TABLE ${quoteSqlIdentifier(table)}")
                        database.execSQL("ALTER TABLE ${quoteSqlIdentifier(tempTable)} RENAME TO ${quoteSqlIdentifier(table)}")
                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                }
                write("ok\r\n")
            }.onFailure { error ->
                write("sqlite-modify-column failed: ${error.message ?: error.javaClass.simpleName}\r\n")
            }
        }

        private data class SqliteColumnInfo(
            val name: String,
            val definition: String,
        )

        private fun readSqliteColumns(database: SQLiteDatabase, table: String): List<SqliteColumnInfo> {
            val columns = mutableListOf<SqliteColumnInfo>()
            database.rawQuery("PRAGMA table_info(${quoteSqlString(table)})", emptyArray()).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                val typeIndex = cursor.getColumnIndex("type")
                val notNullIndex = cursor.getColumnIndex("notnull")
                val defaultIndex = cursor.getColumnIndex("dflt_value")
                val pkIndex = cursor.getColumnIndex("pk")
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)
                    val type = cursor.getString(typeIndex).orEmpty()
                    val notNull = cursor.getInt(notNullIndex) == 1
                    val defaultValue = if (cursor.isNull(defaultIndex)) null else cursor.getString(defaultIndex)
                    val pk = cursor.getInt(pkIndex) > 0
                    val definition = buildString {
                        append(quoteSqlIdentifier(name))
                        if (type.isNotBlank()) append(" ").append(type)
                        if (pk) append(" PRIMARY KEY")
                        if (notNull) append(" NOT NULL")
                        if (defaultValue != null) append(" DEFAULT ").append(defaultValue)
                    }
                    columns += SqliteColumnInfo(name, definition)
                }
            }
            if (columns.isEmpty()) error("table not found or has no columns: $table")
            return columns
        }

        private fun sqliteTarget(args: List<String>, command: String): SqliteTarget? {
            if (args.isEmpty()) {
                write("usage: $command [dbPath] <args>\r\n")
                return null
            }
            val firstPath = resolvePath(args.first())
            val firstLooksLikeDb = firstPath.exists() || firstPath.extension.lowercase(Locale.US) in SQLITE_EXTENSIONS
            return if (firstLooksLikeDb) {
                SqliteTarget(firstPath, args.drop(1))
            } else {
                val db = defaultSqliteDb() ?: return null
                SqliteTarget(db, args)
            }
        }

        private fun quoteSqlIdentifier(value: String): String {
            return "\"${value.replace("\"", "\"\"")}\""
        }

        private fun quoteSqlString(value: String): String {
            return "'${value.replace("'", "''")}'"
        }

        private fun sqlite(args: List<String>) {
            if (args.isEmpty()) {
                val db = defaultSqliteDb() ?: return
                sqliteDb = db
                write(listOf("SQLITE", db.absolutePath, "exit=.exit|.quit").joinToString("\t") + "\r\n")
                return
            }

            val first = args.first()
            val firstPath = resolvePath(first)
            val sqlStartsAtFirstArg = looksLikeSql(first) || first.startsWith(".")
            val db = when {
                sqlStartsAtFirstArg -> defaultSqliteDb() ?: return
                else -> firstPath
            }
            val sql = if (sqlStartsAtFirstArg) args.joinToString(" ") else args.drop(1).joinToString(" ")

            if (sql.isBlank()) {
                sqliteDb = db
                write(listOf("SQLITE", db.absolutePath, "exit=.exit|.quit").joinToString("\t") + "\r\n")
                return
            }
            sqliteQuery(db, sql)
        }

        private fun handleSqliteLine(db: File, line: String) {
            when (line) {
                ".exit", ".quit", "exit", "quit" -> {
                    sqliteDb = null
                    write(listOf("SQLITE", "LEAVE").joinToString("\t") + "\r\n")
                    return
                }
                ".help" -> {
                    writeTable(
                        listOf("COMMAND", "USAGE"),
                        listOf(
                            listOf("SELECT", "select * from table;"),
                            listOf("INSERT", "insert into table(col) values(value);"),
                            listOf("UPDATE", "update table set col=value where id=1;"),
                            listOf("DELETE", "delete from table where id=1;"),
                            listOf(".tables", "list tables"),
                            listOf(".schema", "show schema"),
                            listOf(".exit", "leave sqlite mode"),
                        )
                    )
                    return
                }
            }
            if (line.isBlank()) return
            sqliteQuery(db, line)
        }

        private fun sqliteQuery(db: File, sql: String) {
            if (sql.isBlank()) {
                write("usage: sqlite [dbPath] [sql] | sqlite-tables [dbPath] | sqlite-schema [dbPath] [table]\r\n")
                return
            }
            if (!db.exists()) {
                write("sqlite: ${db.absolutePath}: database not found\r\n")
                return
            }
            val escapedDb = db.absolutePath.replace("'", "'\\''")
            val escapedSql = sql.replace("'", "'\\''")
            val shellResult = executeFirstText(
                listOf(
                    listOf(shellPath, "-c", "sqlite3 '$escapedDb' '$escapedSql'"),
                ) + rootShellCommands("sqlite3 '$escapedDb' '$escapedSql'")
            )
            if (shellResult != null) {
                writeWhitespaceTable(shellResult)
                return
            }
            executeAndroidSqlite(db, sql)
        }

        private fun defaultSqliteDb(): File? {
            val dbFiles = cwd.listFiles()
                ?.filter { file ->
                    file.isFile && file.extension.lowercase(Locale.US) in SQLITE_EXTENSIONS
                }
                ?.sortedBy { it.name }
                .orEmpty()
            return when (dbFiles.size) {
                0 -> {
                    write("sqlite: no database file in ${cwd.absolutePath}; usage: sqlite <dbPath> [sql]\r\n")
                    null
                }
                1 -> dbFiles.first()
                else -> {
                    write("sqlite: multiple database files, specify one\r\n")
                    writeColumns(dbFiles.map { it.name })
                    null
                }
            }
        }

        private fun looksLikeSql(value: String): Boolean {
            return value.trimStart().substringBefore(' ').lowercase(Locale.US) in SQLITE_SQL_PREFIXES
        }

        private fun executeAndroidSqlite(db: File, sql: String) {
            runCatching {
                SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
                    val trimmedSql = sql.trim()
                    when {
                        trimmedSql == ".tables" -> writeAndroidSqliteTables(database)
                        trimmedSql.startsWith(".schema", ignoreCase = true) -> {
                            writeAndroidSqliteSchema(database, trimmedSql.drop(".schema".length).trim())
                        }
                        isSelectLikeSql(trimmedSql) -> writeAndroidSqliteQuery(database, trimmedSql)
                        else -> {
                            database.execSQL(trimmedSql)
                            write("ok\r\n")
                        }
                    }
                }
            }.onFailure { error ->
                write("sqlite failed: ${error.message ?: "sqlite3 unavailable, permission denied, or invalid sql"}\r\n")
            }
        }

        private fun isSelectLikeSql(sql: String): Boolean {
            val prefix = sql.trimStart().substringBefore(' ').lowercase(Locale.US)
            return prefix in SQLITE_QUERY_PREFIXES
        }

        private fun writeAndroidSqliteTables(database: SQLiteDatabase) {
            database.rawQuery(
                "select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name",
                emptyArray(),
            ).use { cursor ->
                writeCursorTable(cursor)
            }
        }

        private fun writeAndroidSqliteSchema(database: SQLiteDatabase, table: String) {
            val selection = if (table.isBlank()) {
                "type in ('table','index','trigger','view')"
            } else {
                "name = ?"
            }
            val args = if (table.isBlank()) emptyArray() else arrayOf(table)
            database.rawQuery(
                "select type, name, sql from sqlite_master where $selection order by type, name",
                args,
            ).use { cursor ->
                writeCursorTable(cursor)
            }
        }

        private fun writeAndroidSqliteQuery(database: SQLiteDatabase, sql: String) {
            database.rawQuery(sql, emptyArray()).use { cursor ->
                writeCursorTable(cursor)
            }
        }

        private fun writeCursorTable(cursor: Cursor) {
            val headers = cursor.columnNames.toList().ifEmpty { listOf("RESULT") }
            val rows = mutableListOf<List<String>>()
            while (cursor.moveToNext()) {
                rows += headers.indices.map { index ->
                    if (cursor.isNull(index)) "NULL" else cursor.getString(index).orEmpty()
                }
            }
            if (rows.isEmpty()) {
                write("empty\r\n")
            } else {
                writeTable(headers, rows)
            }
        }

        private fun executeFirstAvailable(
            commands: List<List<String>>,
            failure: String,
            tableMode: Boolean = false,
        ) {
            for (command in commands) {
                val result = executeForText(command)
                if (result != null) {
                    if (tableMode) {
                        writeWhitespaceTable(result)
                    } else {
                        writeCommandResult(result)
                    }
                    return
                }
            }
            write("$failure\r\n")
        }

        private fun executeFirstText(commands: List<List<String>>): String? {
            return commandRunner.executeFirstText(commands)
        }

        private fun executeFirstText(commands: List<List<String>>, timeoutSeconds: Long): String? {
            return commandRunner.executeFirstText(commands, timeoutSeconds)
        }

        private fun executeForText(command: List<String>): String? {
            return commandRunner.executeForText(command)
        }

        private fun executeForText(command: List<String>, timeoutSeconds: Long): String? {
            return commandRunner.executeForText(command, timeoutSeconds)
        }

        private fun executeForResult(command: List<String>): CommandResult {
            return commandRunner.executeForResult(command)
        }

        private fun executeFirstResult(commands: List<List<String>>): CommandResult {
            return commandRunner.executeFirstResult(commands)
        }

        private fun shellEscape(value: String): String {
            return RemoteCommandRunner.shellEscape(value)
        }

        private fun rootShellCommands(command: String): List<List<String>> {
            return commandRunner.rootShellCommands(command)
        }

        private fun resolveRequiredPath(path: String?, command: String): File? {
            if (path.isNullOrBlank()) {
                write("usage: $command <path>\r\n")
                return null
            }
            return resolvePath(path)
        }

        private fun resolvePath(path: String): File {
            val file = File(path)
            return when {
                file.isAbsolute -> file
                else -> File(cwd, path)
            }.normalize()
        }

        private fun splitArgs(line: String): List<String> {
            val args = mutableListOf<String>()
            val current = StringBuilder()
            var quote: Char? = null
            for (char in line) {
                when {
                    quote != null && char == quote -> quote = null
                    quote == null && (char == '\'' || char == '"') -> quote = char
                    quote == null && char.isWhitespace() -> {
                        if (current.isNotEmpty()) {
                            args += current.toString()
                            current.clear()
                        }
                    }
                    else -> current.append(char)
                }
            }
            if (current.isNotEmpty()) args += current.toString()
            return args
        }

        private fun writeHelp(command: String? = null) {
            val rows = helpRows()
            val filtered = if (command.isNullOrBlank()) {
                rows
            } else {
                rows.filter { row ->
                    row[1].split(' ', '|').any { it == command } ||
                        row[1].startsWith("$command ") ||
                        row[0] == command
                }
            }
            if (filtered.isEmpty()) {
                write("help: unknown command: $command\r\n")
                return
            }
            writeLinuxHelp(filtered)
        }

        private fun writeLinuxHelp(rows: List<List<String>>) {
            write("Usage: help [command]\r\n\r\n")
            write("Commands:\r\n")
            rows.groupBy { it.getOrElse(0) { "other" } }.forEach { (group, groupRows) ->
                write("  $group:\r\n")
                val commandWidth = groupRows.maxOfOrNull { displayWidth(it.getOrElse(1) { "" }) }?.coerceAtMost(36) ?: 0
                groupRows.forEach { row ->
                    val command = row.getOrElse(1) { "" }
                    val usage = row.getOrElse(2) { "" }
                    val padded = command + " ".repeat((commandWidth - displayWidth(command)).coerceAtLeast(0))
                    write("    ${colorKey(padded)}  $usage\r\n")
                }
            }
        }

        private fun helpRows(): List<List<String>> {
            return listOf(
                listOf("help", "help [command]", "show all help or one command help; helpe is accepted as alias"),
                listOf("file", "pwd", "show current directory"),
                listOf("file", "cd <path>", "change directory"),
                listOf("file", "ls [path]", "list name, size, modified time, permissions"),
                listOf("file", "cat <path>", "read file"),
                listOf("file", "find-files [path]", "find by --name/--ext/--type/--max-depth/--size/--mtime-days"),
                listOf("file", "touch <path>", "create/update file"),
                listOf("file", "create-file <path> [content]", "create file, optionally write content; alias: mkfile"),
                listOf("file", "zip <zipPath> <file|dir>...", "create zip from files or directories; directories are recursive"),
                listOf("file", "unzip <zipPath> [destDir]", "extract zip; also supports unzip <zipPath> -d <destDir>"),
                listOf("file", "mkdir <path>", "create directory"),
                listOf("file", "cp <src> <dest>", "copy file"),
                listOf("file", "mv <src> <dest>", "move file"),
                listOf("file", "rm <path>", "delete file"),
                listOf("file", "rm -r <path>", "delete directory recursively"),
                listOf("network", "ip | ifconfig", "show network addresses"),
                listOf("network", "wifi-connect <ssid> [password] [open|wpa2|wpa3]", "connect wifi through Android cmd wifi"),
                listOf("network", "wifi-disconnect", "disconnect/disable wifi"),
                listOf("network", "wifi-set <enable|disable|status>", "set or query wifi state"),
                listOf("network", "lan-connect <iface> [dhcp|static <ip> <prefix> <gateway> [dns]]", "connect LAN interface"),
                listOf("network", "lan-disconnect <iface>", "disconnect LAN interface"),
                listOf("network", "lan-set <iface> [dhcp|static <ip> <prefix> <gateway> [dns]]", "configure LAN interface"),
                listOf("network", "ping <host> [count]", "ping host and show packet summary"),
                listOf("network", "wifi-info", "show wifi state, ssid, ip and dhcp info when visible"),
                listOf("monitor", "mem [package]", "show device/app memory"),
                listOf("monitor", "cpu [package]", "show CPU usage, temperature and per-core details; package keeps process view"),
                listOf("apps", "apps [package]", "list installed packages or show one app info with version"),
                listOf("apps", "running-apps", "list running apps with pid and foreground/background state"),
                listOf("apps", "htop [-n count] [--all]", "one-shot package process snapshot; --all includes native processes"),
                listOf("apps", "start-app <package> [activity]", "start app by launcher package or explicit activity"),
                listOf("apps", "kill-app <pid|package>", "kill by pid or force-stop by package"),
                listOf("logs", "logs [--tag TAG] [--package PACKAGE] [--level V|D|I|W|E|F] [--buffer all] [--since 10m] [--tail 500]", "stream root-first logcat; stop with q/stop/exit/quit/Ctrl-C"),
                listOf("apk", "install-apk <path>", "install apk"),
                listOf("apk", "uninstall-apk <package>", "uninstall app"),
                listOf("transfer", "download <url> <dest>", "download file"),
                listOf("transfer", "scp", "client copy: scp local user@host:/path; scp user@host:/path local"),
                listOf("device", "screenshot [path]", "capture screen to png using screencap"),
                listOf("device", "hardware | hw", "show hardware, build, cpu and memory summary"),
                listOf("device", "gpu", "show GPU renderer, frequency, governor and load"),
                listOf("device", "npu", "show RKNN NPU device, driver, frequency and load"),
                listOf("device", "usb | usb-info", "show USB device count and details"),
                listOf("device", "cameras | camera-info [cameraId]", "list cameras or show all properties for one camera ID"),
                listOf("device", "volume", "show Android audio stream volume levels"),
                listOf("device", "brightness", "show screen brightness and mode"),
                listOf("device", "system-time", "show local system time, timezone, epoch and automatic-time state"),
                listOf("device", "set-system-time <yyyy-MM-dd HH:mm:ss>", "set system time with root and show the updated value"),
                listOf("sqlite", "sqlite <dbPath>", "enter sqlite mode"),
                listOf("sqlite", "sqlite <dbPath> <sql>", "execute SELECT/INSERT/UPDATE/DELETE"),
                listOf("sqlite", "sqlite-dbs [dir]", "list database files"),
                listOf("sqlite", "sqlite-create-db <dbPath>", "create database"),
                listOf("sqlite", "sqlite-delete-db <dbPath>", "delete database plus wal/shm/journal"),
                listOf("sqlite", "sqlite-rename-db <oldPath> <newPath>", "rename database"),
                listOf("sqlite", "sqlite-tables [dbPath]", "list tables"),
                listOf("sqlite", "sqlite-schema [dbPath] [table]", "show schema"),
                listOf("sqlite", "sqlite-create-table [dbPath] <table> <typedColumns>", "<typedColumns>: id INTEGER PRIMARY KEY, name TEXT"),
                listOf("sqlite", "sqlite-create-table [dbPath] <createSql>", "<createSql>: CREATE TABLE Tb(id INTEGER PRIMARY KEY)"),
                listOf("sqlite", "sqlite-drop-table [dbPath] <table>", "drop table"),
                listOf("sqlite", "sqlite-rename-table [dbPath] <oldTable> <newTable>", "rename table"),
                listOf("sqlite", "sqlite-table [dbPath] <table> [limit]", "query table rows"),
                listOf("sqlite", "sqlite-columns [dbPath] <table>", "show table columns"),
                listOf("sqlite", "sqlite-add-column [dbPath] <table> <columnDef>", "add typed column"),
                listOf("sqlite", "sqlite-drop-column [dbPath] <table> <column>", "drop column; requires SQLite 3.35+"),
                listOf("sqlite", "sqlite-rename-column [dbPath] <table> <oldColumn> <newColumn>", "rename column"),
                listOf("sqlite", "sqlite-modify-column [dbPath] <table> <oldColumn> <newColumnDef>", "modify column by rebuilding table"),
                listOf("sqlite", "sqlite-version [dbPath]", "query PRAGMA user_version"),
                listOf("sqlite", "sqlite-set-version [dbPath] <version>", "set PRAGMA user_version"),
                listOf("device", "reboot", "reboot device"),
                listOf("shell", "<any other command>", "execute by $shellPath -c"),
            )
        }

        private fun writeTable(headers: List<String>, rows: List<List<String>>) {
            if (headers == listOf("KEY", "VALUE")) {
                writeLinuxKeyValueRows(rows)
                return
            }
            if (headers.firstOrNull() == "STATUS") {
                writeLinuxStatusRows(headers, rows)
                return
            }
            val columnCount = (listOf(headers) + rows).maxOfOrNull { it.size } ?: 0
            if (columnCount == 0) return

            val normalizedHeaders = normalizeRow(headers.map(::colorHeader), columnCount)
            val normalizedRows = rows.map { normalizeRow(colorRow(headers, it), columnCount) }
            val widths = (0 until columnCount).map { column ->
                (listOf(normalizedHeaders) + normalizedRows)
                    .maxOf { row -> displayWidth(row[column]) }
                    .let { width ->
                        if (column == columnCount - 1 || shouldKeepFullWidthColumn(headers.getOrNull(column))) {
                            width
                        } else {
                            width.coerceAtMost(TABLE_MAX_NON_LAST_COLUMN_WIDTH)
                        }
                    }
            }

            write(formatTableRow(normalizedHeaders, widths) + "\r\n")
            normalizedRows.forEach { row ->
                write(formatTableRow(row, widths) + "\r\n")
            }
        }

        private fun writeTableTitle(title: String) {
            write("${colorHeader(title.lowercase(Locale.US))}:\r\n")
        }

        private fun writeLinuxKeyValueRows(rows: List<List<String>>) {
            val keyWidth = rows.maxOfOrNull { displayWidth(it.getOrElse(0) { "" }.lowercase(Locale.US)) } ?: 0
            rows.forEach { row ->
                val key = row.getOrElse(0) { "" }.lowercase(Locale.US)
                val value = row.getOrElse(1) { "" }
                val padded = key + " ".repeat((keyWidth - displayWidth(key)).coerceAtLeast(0))
                write("${colorKey(padded)}: $value\r\n")
            }
        }

        private fun writeLinuxStatusRows(headers: List<String>, rows: List<List<String>>) {
            rows.forEach { row ->
                val status = row.getOrElse(0) { "" }
                val message = headers.drop(1).mapIndexedNotNull { index, header ->
                    val value = row.getOrNull(index + 1).orEmpty()
                    if (value.isBlank()) null else "${header.lowercase(Locale.US)}=$value"
                }.joinToString(" ")
                val coloredStatus = if (status.equals("ok", ignoreCase = true)) colorSuccess(status) else colorError(status)
                write(if (message.isBlank()) "$coloredStatus\r\n" else "$coloredStatus $message\r\n")
            }
        }

        private fun shouldKeepFullWidthColumn(header: String?): Boolean {
            return header in setOf("PACKAGE")
        }

        private fun writeColumns(values: List<String>) {
            if (values.isEmpty()) {
                return
            }
            val maxWidth = values.maxOf { displayWidth(it) }.coerceAtMost(TABLE_MAX_COLUMN_ITEM_WIDTH)
            val columnWidth = maxWidth + TABLE_COLUMN_GAP
            val columns = (TABLE_TARGET_WIDTH / columnWidth).coerceAtLeast(1)
            values.chunked(columns).forEach { row ->
                val line = row.mapIndexed { index, value ->
                    val cell = abbreviateCell(value, maxWidth)
                    if (index == row.lastIndex) {
                        cell
                    } else {
                        cell + " ".repeat((maxWidth - displayWidth(cell)).coerceAtLeast(0) + TABLE_COLUMN_GAP)
                    }
                }.joinToString("")
                write("$line\r\n")
            }
        }

        private fun colorRow(headers: List<String>, row: List<String>): List<String> {
            return row.mapIndexed { index, value ->
                when (headers.getOrNull(index)) {
                    "STATUS" -> if (value.equals("ok", ignoreCase = true)) colorSuccess(value) else colorError(value)
                    "STATE" -> when (value) {
                        "FOREGROUND" -> colorSuccess(value)
                        "PROCESS" -> colorMuted(value)
                        else -> colorMuted(value)
                    }
                    "PATH", "APK_PATH" -> colorMuted(value)
                    "PACKAGE" -> colorPackage(value)
                    "APP_NAME" -> colorAppName(value)
                    "KEY", "GROUP", "COMMAND" -> colorKey(value)
                    "PERM" -> colorPermissions(value)
                    "SIZE", "BYTES" -> colorSize(value)
                    "MODIFIED" -> colorMuted(value)
                    else -> value
                }
            }
        }

        private fun normalizeRow(row: List<String>, columnCount: Int): List<String> {
            return row.map { it.replace('\t', ' ').replace("\r", " ").replace("\n", " ") } +
                List(columnCount - row.size) { "" }
        }

        private fun formatTableRow(row: List<String>, widths: List<Int>): String {
            return row.mapIndexed { index, cell ->
                val value = if (index == widths.lastIndex) cell else abbreviateCell(cell, widths[index])
                if (index == widths.lastIndex) {
                    value
                } else {
                    value + " ".repeat((widths[index] - displayWidth(value)).coerceAtLeast(0) + TABLE_COLUMN_GAP)
                }
            }.joinToString("")
        }

        private fun abbreviateCell(value: String, maxWidth: Int): String {
            if (displayWidth(value) <= maxWidth) return value
            val plain = stripAnsi(value)
            if (maxWidth <= 1) return plain.take(maxWidth)
            return plain.take(maxWidth - 1) + "~"
        }

        private fun displayWidth(value: String): Int {
            var width = 0
            stripAnsi(value).forEach { char ->
                width += if (char.code > 0x7F) 2 else 1
            }
            return width
        }

        private fun stripAnsi(value: String): String {
            return ANSI_REGEX.replace(value, "")
        }

        private fun colorHeader(value: String) = color(value, ANSI_BOLD_CYAN)
        private fun colorDirectory(value: String) = color(value, ANSI_BLUE)
        private fun colorExecutable(value: String) = color(value, ANSI_GREEN)
        private fun colorHidden(value: String) = color(value, ANSI_DIM)
        private fun colorFile(value: String) = value
        private fun colorMuted(value: String) = color(value, ANSI_DIM)
        private fun colorSuccess(value: String) = color(value, ANSI_GREEN)
        private fun colorError(value: String) = color(value, ANSI_RED)
        private fun colorPackage(value: String) = color(value, ANSI_CYAN)
        private fun colorAppName(value: String) = color(value, ANSI_GREEN)
        private fun colorKey(value: String) = color(value, ANSI_YELLOW)
        private fun colorPermissions(value: String) = color(value, ANSI_MAGENTA)
        private fun colorSize(value: String) = color(value, ANSI_YELLOW)

        private fun color(value: String, ansi: String): String {
            if (value.isBlank()) return value
            return "$ansi$value$ANSI_RESET"
        }

        private fun writeKeyValueTable(text: String) {
            val rows = normalizedLines(text).mapNotNull { line ->
                val index = keyValueSeparatorIndex(line)
                if (index < 0) return@mapNotNull null
                val key = line.substring(0, index).trim()
                val value = line.substring(index + 1).trim()
                if (key.isBlank()) null else listOf(key, value)
            }.toList()
            if (rows.isEmpty()) {
                writeWhitespaceTable(text)
            } else {
                writeLinuxKeyValueRows(rows)
            }
        }

        private fun writeWhitespaceTable(
            text: String,
            fallbackHeader: List<String> = listOf("C1"),
        ) {
            val rows = normalizedLines(text).map { line ->
                line.split(Regex("\\s+")).filter { it.isNotBlank() }
            }.filter { it.isNotEmpty() }.toList()
            if (rows.isEmpty()) return

            val columnCount = rows.maxOf { it.size }.coerceAtLeast(fallbackHeader.size)
            val hasHeader = rows.size > 1 && rows.first().size == columnCount && columnCount > 1 && looksLikeHeader(rows.first())
            val headers = if (hasHeader) {
                rows.first()
            } else {
                (1..columnCount).map { index ->
                    fallbackHeader.getOrNull(index - 1) ?: "C$index"
                }
            }
            val dataRows = if (hasHeader) rows.drop(1) else rows
            writeTable(headers, dataRows.map { row -> row + List(columnCount - row.size) { "" } })
        }

        private fun normalizedLines(text: String): Sequence<String> {
            return text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        private fun looksLikeHeader(row: List<String>): Boolean {
            return row.isNotEmpty() &&
                row.count { cell -> cell.any { it.isLetter() } } >= row.size / 2 &&
                row.any { cell -> cell.any { it.isUpperCase() } || '_' in cell || '%' in cell }
        }

        private fun keyValueSeparatorIndex(line: String): Int {
            val colon = line.indexOf(':')
            val equals = line.indexOf('=')
            return when {
                colon < 0 -> equals
                equals < 0 -> colon
                else -> minOf(colon, equals)
            }
        }

        private fun singleLinePropertyRows(line: String): List<List<String>> {
            return Regex("""([A-Za-z0-9_.-]+)=([^=\s]+)""")
                .findAll(line)
                .map { match -> listOf(match.groupValues[1], match.groupValues[2]) }
                .toList()
        }

        private fun InputStream.readAllText(): String {
            val target = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = read(buffer)
                if (count == -1) break
                if (count > 0) {
                    target.write(buffer, 0, count)
                }
            }
            return target.toString(StandardCharsets.UTF_8.name())
        }

        private fun writeCommandResult(text: String) {
            val lines = normalizedLines(text).toList()
            if (lines.isEmpty()) return

            if (lines.size > 1) {
                val keyValueCount = lines.count { keyValueSeparatorIndex(it) > 0 }
                if (keyValueCount == lines.size) {
                    writeKeyValueTable(text)
                } else {
                    writeWhitespaceTable(text)
                }
                return
            }

            val line = lines.first()
            val propertyRows = singleLinePropertyRows(line)
            when {
                propertyRows.size > 1 -> writeTable(listOf("KEY", "VALUE"), propertyRows)
                keyValueSeparatorIndex(line) > 0 -> writeKeyValueTable(line)
                else -> write("$line\r\n")
            }
        }

        private fun writePrompt() {
            if (sqliteDb == null) {
                writeCurrentDirectoryNotification()
            }
            write(promptText())
        }

        private fun promptText(): String = sqliteDb?.let { db ->
            "$promptUser@$promptHost sqlite:${db.name}> "
        } ?: "$promptUser@$promptHost:${promptPath()}$ "

        private fun promptPath(): String {
            return cwd.absolutePath.ifBlank { "/" }
        }

        private fun writeCurrentDirectoryNotification() {
            write("\u001B]7;file://${promptHost}${osc7Path(cwd.absolutePath)}\u0007")
        }

        private fun osc7Path(path: String): String {
            return path.ifBlank { "/" }
                .split('/')
                .joinToString("/") { segment -> percentEncodeOsc7Segment(segment) }
                .ifBlank { "/" }
        }

        private fun percentEncodeOsc7Segment(segment: String): String {
            val bytes = segment.toByteArray(StandardCharsets.UTF_8)
            return buildString {
                bytes.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    val char = value.toChar()
                    if (char.isLetterOrDigit() || char in "-._~") {
                        append(char)
                    } else {
                        append('%')
                        append(value.toString(16).uppercase(Locale.US).padStart(2, '0'))
                    }
                }
            }
        }

        private fun write(text: String) {
            if (!safeWrite(text)) {
                running.set(false)
            }
        }

        private fun safeWrite(text: String): Boolean {
            return safeWriteBytes(text.toByteArray(StandardCharsets.UTF_8))
        }

        private fun safeWriteBytes(bytes: ByteArray): Boolean {
            return try {
                val target = output ?: return false
                target.write(bytes)
                target.flush()
                true
            } catch (_: Throwable) {
                running.set(false)
                false
            }
        }

        private data class Completion(
            val suffix: String = "",
            val choices: List<String> = emptyList(),
        )

        private fun completeLine(line: String): Completion {
            val tokenStart = line.indexOfLast { it.isWhitespace() } + 1
            val token = line.substring(tokenStart)
            val completingCommand = tokenStart == 0
            return if (completingCommand) {
                completeCommand(token)
            } else {
                completePath(token)
            }
        }

        private fun completeCommand(prefix: String): Completion {
            val matches = builtinCommands().filter { it.startsWith(prefix) }.sorted()
            return completionFromMatches(prefix, matches, appendSpaceOnExact = true)
        }

        private fun completePath(token: String): Completion {
            val separator = maxOf(token.lastIndexOf('/'), token.lastIndexOf('\\'))
            val dirPart = if (separator >= 0) token.substring(0, separator + 1) else ""
            val namePrefix = if (separator >= 0) token.substring(separator + 1) else token
            val dirFile = File(dirPart)
            val dir = when {
                dirPart.isBlank() -> cwd
                dirPart.startsWith("/") || dirFile.isAbsolute -> dirFile
                else -> File(cwd, dirPart)
            }.normalize()
            val matches = dir.listFiles()
                ?.filter { it.name.startsWith(namePrefix) }
                ?.sortedBy { it.name }
                ?.map { file -> dirPart + displayFileName(file) }
                .orEmpty()
            return completionFromMatches(token, matches, appendSpaceOnExact = true)
        }

        private fun completionFromMatches(
            prefix: String,
            matches: List<String>,
            appendSpaceOnExact: Boolean,
        ): Completion {
            if (matches.isEmpty()) return Completion()
            if (matches.size == 1) {
                val match = matches.first()
                val extra = if (appendSpaceOnExact && match == prefix && !match.endsWith("/")) " " else ""
                return Completion(suffix = match.removePrefix(prefix) + extra)
            }
            val common = commonPrefix(matches)
            if (common.length > prefix.length) {
                return Completion(suffix = common.substring(prefix.length))
            }
            return Completion(choices = matches)
        }

        private fun commonPrefix(values: List<String>): String {
            if (values.isEmpty()) return ""
            var prefix = values.first()
            values.drop(1).forEach { value ->
                while (!value.startsWith(prefix) && prefix.isNotEmpty()) {
                    prefix = prefix.dropLast(1)
                }
            }
            return prefix
        }

        private fun writeError(text: String) {
            try {
                val target = error ?: output ?: return
                target.write(text.toByteArray(StandardCharsets.UTF_8))
                target.flush()
            } catch (_: Throwable) {
                running.set(false)
            }
        }

        private interface LineReader {
            fun readLine(): String?
        }

        private sealed class InputAction {
            data class ReplaceLine(val value: String) : InputAction()
            data class MoveCursor(val delta: Int, val selecting: Boolean) : InputAction()
            data class PasteText(val value: String) : InputAction()
            object DeleteAfterCursor : InputAction()
        }

        private inner class EchoingLineReader(
            private val source: InputStream,
        ) : LineReader {
            override fun readLine(): String? {
                val line = mutableListOf<Byte>()
                var cursor = 0
                var selectionAnchor: Int? = null
                while (running.get()) {
                    val next = readInputByte(source)
                    if (next == -1) {
                        return if (line.isEmpty()) null else line.toByteArray().toString(StandardCharsets.UTF_8)
                    }

                    if (ignoreNextLf && next == '\n'.code) {
                        ignoreNextLf = false
                        continue
                    }
                    ignoreNextLf = false

                    when (next) {
                        '\r'.code -> {
                            ignoreNextLf = true
                            write("\r\n")
                            return line.toByteArray().toString(StandardCharsets.UTF_8)
                        }
                        '\n'.code -> {
                            write("\r\n")
                            return line.toByteArray().toString(StandardCharsets.UTF_8)
                        }
                        3 -> {
                            write("^C\r\n")
                            return "\u0003"
                        }
                        9 -> {
                            val currentLine = line.toByteArray().toString(StandardCharsets.UTF_8)
                            val completion = completeLine(currentLine)
                            if (completion.suffix.isNotEmpty()) {
                                deleteSelection(line, selectionAnchor, cursor)?.let { newCursor ->
                                    cursor = newCursor
                                    selectionAnchor = null
                                }
                                val bytes = completion.suffix.toByteArray(StandardCharsets.UTF_8)
                                bytes.forEach { byte ->
                                    line.add(cursor, byte)
                                    cursor++
                                }
                                redrawInputLine(line.asText(), cursor, selectionAnchor)
                            } else if (completion.choices.isNotEmpty()) {
                                write("\r\n")
                                writeColumns(completion.choices.map { colorMuted(it) })
                                redrawInputLine(currentLine, cursor, selectionAnchor)
                            }
                        }
                        27 -> {
                            when (val action = readEscapeAction(line.toByteArray().toString(StandardCharsets.UTF_8))) {
                                is InputAction.ReplaceLine -> {
                                    line.clear()
                                    action.value.toByteArray(StandardCharsets.UTF_8).forEach { line += it }
                                    cursor = line.size
                                    selectionAnchor = null
                                    redrawInputLine(line.asText(), cursor, selectionAnchor)
                                }
                                is InputAction.MoveCursor -> {
                                    val previousCursor = cursor
                                    cursor = (cursor + action.delta).coerceIn(0, line.size)
                                    if (action.selecting) {
                                        selectionAnchor = selectionAnchor ?: previousCursor
                                        if (selectionAnchor == cursor) selectionAnchor = null
                                        redrawInputLine(line.asText(), cursor, selectionAnchor)
                                    } else {
                                        selectionAnchor = null
                                        val moved = cursor - previousCursor
                                        when {
                                            moved < 0 -> write("\u001B[${-moved}D")
                                            moved > 0 -> write("\u001B[${moved}C")
                                        }
                                    }
                                }
                                is InputAction.PasteText -> {
                                    deleteSelection(line, selectionAnchor, cursor)?.let { newCursor ->
                                        cursor = newCursor
                                        selectionAnchor = null
                                    }
                                    action.value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
                                        line.add(cursor, byte)
                                        cursor++
                                    }
                                    redrawInputLine(line.asText(), cursor, selectionAnchor)
                                }
                                InputAction.DeleteAfterCursor -> {
                                    val selectedCursor = deleteSelection(line, selectionAnchor, cursor)
                                    if (selectedCursor != null) {
                                        cursor = selectedCursor
                                        selectionAnchor = null
                                        redrawInputLine(line.asText(), cursor, selectionAnchor)
                                    } else if (cursor < line.size) {
                                        line.removeAt(cursor)
                                        redrawInputLine(line.asText(), cursor, selectionAnchor)
                                    }
                                }
                                null -> Unit
                            }
                        }
                        8, 127 -> {
                            val selectedCursor = deleteSelection(line, selectionAnchor, cursor)
                            if (selectedCursor != null) {
                                cursor = selectedCursor
                                selectionAnchor = null
                                redrawInputLine(line.asText(), cursor, selectionAnchor)
                            } else if (cursor > 0) {
                                line.removeAt(cursor - 1)
                                cursor--
                                redrawInputLine(line.asText(), cursor, selectionAnchor)
                            }
                        }
                        else -> {
                            deleteSelection(line, selectionAnchor, cursor)?.let { newCursor ->
                                cursor = newCursor
                                selectionAnchor = null
                            }
                            line.add(cursor, next.toByte())
                            cursor++
                            if (cursor == line.size && selectionAnchor == null) {
                                safeWriteBytes(byteArrayOf(next.toByte()))
                            } else {
                                redrawInputLine(line.asText(), cursor, selectionAnchor)
                            }
                        }
                    }
                }
                return null
            }

            private fun readEscapeAction(currentLine: String): InputAction? {
                val second = source.read()
                if (second != '['.code) return null
                val sequence = StringBuilder()
                while (true) {
                    val next = source.read()
                    if (next == -1) return null
                    val char = next.toChar()
                    sequence.append(char)
                    if (char in 'A'..'Z' || char == '~') break
                }
                val value = sequence.toString()
                return when {
                    value == "200~" -> InputAction.PasteText(readBracketedPasteText())
                    value == "A" -> previousHistory(currentLine)?.let(InputAction::ReplaceLine)
                    value == "B" -> nextHistory()?.let(InputAction::ReplaceLine)
                    value == "D" -> InputAction.MoveCursor(delta = -1, selecting = false)
                    value == "C" -> InputAction.MoveCursor(delta = 1, selecting = false)
                    value == "3~" -> InputAction.DeleteAfterCursor
                    value.endsWith("D") && isShiftModified(value) -> InputAction.MoveCursor(delta = -1, selecting = true)
                    value.endsWith("C") && isShiftModified(value) -> InputAction.MoveCursor(delta = 1, selecting = true)
                    else -> null
                }
            }

            private fun readBracketedPasteText(): String {
                val bytes = mutableListOf<Byte>()
                val end = byteArrayOf(27, '['.code.toByte(), '2'.code.toByte(), '0'.code.toByte(), '1'.code.toByte(), '~'.code.toByte())
                while (true) {
                    val next = source.read()
                    if (next == -1) break
                    bytes += next.toByte()
                    if (bytes.endsWith(end)) {
                        repeat(end.size) {
                            bytes.removeAt(bytes.lastIndex)
                        }
                        break
                    }
                }
                return bytes.toByteArray().toString(StandardCharsets.UTF_8)
            }

            private fun List<Byte>.endsWith(suffix: ByteArray): Boolean {
                if (size < suffix.size) return false
                suffix.indices.forEach { index ->
                    if (this[size - suffix.size + index] != suffix[index]) return false
                }
                return true
            }

            private fun isShiftModified(value: String): Boolean {
                return value.contains(";2")
            }

            private fun previousHistory(currentLine: String): String? {
                if (history.isEmpty()) return null
                if (historyIndex == history.size && currentLine.isNotBlank() && history.lastOrNull() != currentLine) {
                    // Keep the current draft outside history; down arrow returns to blank like common shells.
                }
                historyIndex = (historyIndex - 1).coerceAtLeast(0)
                return history.getOrNull(historyIndex)
            }

            private fun nextHistory(): String? {
                if (history.isEmpty()) return null
                historyIndex = (historyIndex + 1).coerceAtMost(history.size)
                return if (historyIndex == history.size) "" else history[historyIndex]
            }

            private fun deleteSelection(line: MutableList<Byte>, anchor: Int?, cursor: Int): Int? {
                val start = minOf(anchor ?: return null, cursor)
                val end = maxOf(anchor, cursor)
                if (start == end) return null
                repeat(end - start) {
                    line.removeAt(start)
                }
                return start
            }

            private fun MutableList<Byte>.asText(): String {
                return toByteArray().toString(StandardCharsets.UTF_8)
            }

            private fun redrawInputLine(value: String, cursor: Int, selectionAnchor: Int?) {
                val rendered = buildString {
                    append("\r\u001B[2K")
                    append(promptText())
                    val anchor = selectionAnchor
                    if (anchor == null || anchor == cursor) {
                        append(value)
                    } else {
                        val start = minOf(anchor, cursor)
                        val end = maxOf(anchor, cursor)
                        append(value.substring(0, start))
                        append("\u001B[7m")
                        append(value.substring(start, end))
                        append(ANSI_RESET)
                        append(value.substring(end))
                    }
                    val cursorLeft = value.length - cursor
                    if (cursorLeft > 0) append("\u001B[${cursorLeft}D")
                }
                write(rendered)
            }

        }

        private fun File.normalize(): File {
            return try {
                canonicalFile
            } catch (_: IOException) {
                absoluteFile
            }
        }
    }

    companion object {
        const val DEFAULT_SHELL = "/system/bin/sh"
        private const val DEFAULT_PROMPT_USER = "android"
        private const val DEFAULT_PROMPT_HOST = "android"
        private const val COMMAND_TIMEOUT_SECONDS = 10L
        private const val HTOP_COMMAND_TIMEOUT_SECONDS = 2L
        private const val HTOP_DEFAULT_LIMIT = 30
        private const val CPU_SAMPLE_MILLIS = 200L
        private const val LOGCAT_PID_REFRESH_MILLIS = 1_000L
        private const val COMMAND_POLL_INTERVAL_MILLIS = 50L
        private const val HISTORY_LIMIT = 100
        private const val DOWNLOAD_TIMEOUT_MILLIS = 30_000
        private const val DOWNLOAD_BUFFER_SIZE = 8 * 1024
        private const val DOWNLOAD_PROGRESS_INTERVAL_MILLIS = 250L
        private const val TABLE_COLUMN_GAP = 2
        private const val TABLE_MAX_NON_LAST_COLUMN_WIDTH = 32
        private const val TABLE_MAX_COLUMN_ITEM_WIDTH = 28
        private const val TABLE_TARGET_WIDTH = 96
        private const val PIDOF_PACKAGE_CHUNK_SIZE = 80
        private const val ANSI_RESET = "\u001B[0m"
        private const val ANSI_BOLD_CYAN = "\u001B[1;36m"
        private const val ANSI_BLUE = "\u001B[34m"
        private const val ANSI_GREEN = "\u001B[32m"
        private const val ANSI_DIM = "\u001B[2m"
        private const val ANSI_RED = "\u001B[31m"
        private const val ANSI_CYAN = "\u001B[36m"
        private const val ANSI_YELLOW = "\u001B[33m"
        private const val ANSI_MAGENTA = "\u001B[35m"
        private const val BRACKETED_PASTE_ENABLE = "\u001B[?2004h"
        private const val BRACKETED_PASTE_DISABLE = "\u001B[?2004l"
        private val ANSI_REGEX = Regex("""\u001B\[[0-9;]*m""")
        private val LOGCAT_THREADTIME_REGEX = Regex("""^(\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+):\s?(.*)$""")
        private val PROCESS_MEMORY_REGEX = Regex("""^\s*([\d,]+)\s+K:\s+(.+?)(?:\s+\(pid\s+\d+.*)?$""")
        private val FOREGROUND_PACKAGE_REGEX = Regex("""\b([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+""")
        private val PACKAGE_NAME_REGEX = Regex("""\b[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+\b""")
        private val SERVICE_COMPONENT_REGEX = Regex("""\b([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+""")
        private val PID_REGEX = Regex("""\bpid=(\d+)\b|\bpid\s+(\d+)\b|\bPID\s+(\d+)\b|\b(\d+):[A-Za-z][A-Za-z0-9_]*(?:[.:][A-Za-z0-9_]+)*\/""")
        private val PROCESS_NAME_REGEX = Regex("""\b(?:processName|process|proc)=([A-Za-z][A-Za-z0-9_]*(?:[.:][A-Za-z0-9_]+)*)""")
        private val PROCESS_RECORD_REGEX = Regex("""\b(\d+):([A-Za-z][A-Za-z0-9_]*(?:[.:][A-Za-z0-9_]+)*)\/""")
        private val PING_PACKETS_REGEX = Regex("""(\d+)\s+packets transmitted,\s+(\d+)\s+(?:packets )?received,.*?(\d+(?:\.\d+)?%)\s+packet loss""")
        private val PING_RTT_REGEX = Regex("""(?:rtt|round-trip).*?=\s*([\d.]+)/([\d.]+)/([\d.]+)/([\d.]+)\s*ms""")
        private val GETPROP_REGEX = Regex("""^\[([^]]+)]\s*:\s*\[(.*)]$""")
        private val LSUSB_REGEX = Regex("""Bus\s+(\d+)\s+Device\s+(\d+):\s+ID\s+([0-9a-fA-F]{4}):([0-9a-fA-F]{4})\s*(.*)""")
        private val CAMERA_ID_REGEX = Regex("""(?i)\bCamera\s+ID\s*[:=]\s*"?([A-Za-z0-9_./-]+)"?""")
        private val CAMERA_DEVICE_REGEX = Regex("""(?i)\bcamera\s+device\s+([A-Za-z0-9_./-]+)""")
        private val CAMERA_FACING_REGEX = Regex("""(?i)\b(?:facing|lens_facing)\s*[:=]\s*([A-Z_0-9]+)""")
        private val CAMERA_ORIENTATION_REGEX = Regex("""(?i)\b(?:orientation|sensor_orientation)\s*[:=]\s*([0-9]+)""")
        private val VOLUME_STREAM_REGEX = Regex("""(?i)^\s*(?:- )?(?:stream|STREAM)_?([A-Z_]+)|^\s*Stream\s+([A-Za-z0-9_ -]+):""")
        private val VOLUME_MIN_REGEX = Regex("""(?i)\bmin(?:Index)?:?\s*([0-9]+)""")
        private val VOLUME_MAX_REGEX = Regex("""(?i)\bmax(?:Index)?:?\s*([0-9]+)""")
        private val VOLUME_CURRENT_REGEX = Regex("""(?i)\b(?:index|current|device[^:]*index):?\s*([0-9]+)""")
        private val VOLUME_MUTED_REGEX = Regex("""(?i)\bm(?:Is)?Muted:?\s*(true|false)""")
        private val SQLITE_EXTENSIONS = setOf("db", "sqlite", "sqlite3")
        private val SQLITE_SQL_PREFIXES = setOf(
            "select",
            "with",
            "insert",
            "update",
            "delete",
            "replace",
            "create",
            "drop",
            "alter",
            "pragma",
            "vacuum",
            "begin",
            "commit",
            "rollback",
        )
        private val SQLITE_QUERY_PREFIXES = setOf("select", "with", "pragma", "explain")
        private val SQLITE_COLUMN_TYPE_OR_CONSTRAINTS = setOf(
            "INTEGER",
            "INT",
            "TEXT",
            "REAL",
            "BLOB",
            "NUMERIC",
            "BOOLEAN",
            "DATETIME",
            "DATE",
            "PRIMARY",
            "KEY",
            "NOT",
            "NULL",
            "UNIQUE",
            "DEFAULT",
            "CHECK",
            "REFERENCES",
            "COLLATE",
            "GENERATED",
            "AUTOINCREMENT",
        )
        private val LOG_TAG_COLORS = listOf(
            "\u001B[36m",
            "\u001B[35m",
            "\u001B[34m",
            "\u001B[32m",
            "\u001B[33m",
            "\u001B[1;36m",
            "\u001B[1;35m",
            "\u001B[1;32m",
        )
        private val DATE_FORMAT = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        }
    }
}
