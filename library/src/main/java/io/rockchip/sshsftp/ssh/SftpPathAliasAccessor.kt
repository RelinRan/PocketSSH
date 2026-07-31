package io.rockchip.sshsftp.ssh

import org.apache.sshd.sftp.server.SftpFileSystemAccessor
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.DirectoryHandle
import org.apache.sshd.sftp.server.SftpSubsystemProxy
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.channels.Channel
import java.nio.channels.SeekableByteChannel
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.LinkOption
import java.nio.file.Files
import java.nio.file.DirectoryStream
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.FileAttribute
import java.util.NavigableMap
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import android.util.Log

internal fun resolveSftpAlias(path: Path, sharedStorage: Path): Path {
    val normalized = path.normalize()
    val canonicalShared = sharedStorage.normalize()
    if (normalized.startsWith(canonicalShared)) return normalized
    val text = normalized.toString().replace('\\', '/')
    val alias = when {
        text == "/sdcard" || text == "sdcard" -> ""
        text.startsWith("/sdcard/") -> text.removePrefix("/sdcard/")
        text.startsWith("sdcard/") -> text.removePrefix("sdcard/")
        else -> return normalized
    }
    return if (alias.isEmpty()) canonicalShared else canonicalShared.resolve(alias).normalize()
}

internal fun resolveRemoteSftpPath(
    rootDir: Path,
    remotePath: String,
    sharedStorage: Path,
    shadowRoot: Path? = null,
    rootAccess: Boolean = false,
): Path {
    val normalizedRemote = remotePath.replace('\\', '/')
    val remote = if (normalizedRemote.startsWith('/')) normalizedRemote else "/$normalizedRemote"
    return resolveSharedRemotePath(remote, sharedStorage, shadowRoot, rootAccess)
}

internal fun resolveSharedRemotePath(
    remotePath: String,
    sharedStorage: Path,
    shadowRoot: Path? = null,
    rootAccess: Boolean = false,
): Path {
    val remote = if (remotePath.startsWith('/')) remotePath else "/$remotePath"
    val normalizedSegments = remote.split('/').filter { it.isNotBlank() && it != "." }
        .fold(mutableListOf<String>()) { parts, segment ->
            if (segment == "..") {
                if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            } else {
                parts.add(segment)
            }
            parts
    }
    val normalizedLogical = "/" + normalizedSegments.joinToString("/")
    val shadowPrefix = shadowRoot?.normalize()?.toString()?.replace('\\', '/')
    if (shadowPrefix != null && remote.startsWith(shadowPrefix)) {
        return Paths.get(remote).normalize()
    }
    if (rootAccess) {
        if (normalizedLogical == "/") return Paths.get("/").normalize()
        if (normalizedLogical == "/storage") return sharedStorage.normalize()
        rootBackedRemotePath(remote)?.let { restricted ->
            return shadowRoot?.resolve("root/${restricted.removePrefix("/")}")?.normalize()
                ?: Paths.get(restricted).normalize()
        }
        if (remote == "/sdcard" || remote.startsWith("/sdcard/")) {
            return resolveStorageRelative(remote.removePrefix("/sdcard"), sharedStorage, rootAccess)
        }
        if (remote == "/storage/emulated/0" || remote.startsWith("/storage/emulated/0/")) {
            return resolveStorageRelative(remote.removePrefix("/storage/emulated/0"), sharedStorage, rootAccess)
        }
        if (remote == "/storage/self/primary" || remote.startsWith("/storage/self/primary/")) {
            return resolveStorageRelative(remote.removePrefix("/storage/self/primary"), sharedStorage, rootAccess)
        }
        return Paths.get(normalizedLogical).normalize()
    }
    if (normalizedLogical == "/") return sharedStorage.normalize()
    rootBackedRemotePath(remote)?.let { restricted ->
        return shadowRoot?.resolve("root/${restricted.removePrefix("/")}")?.normalize()
            ?: sharedStorage.resolve(restricted.removePrefix("/storage/emulated/0").removePrefix("/")).normalize()
    }
    if (remote == "/sdcard" || remote.startsWith("/sdcard/")) {
        return resolveStorageRelative(remote.removePrefix("/sdcard"), sharedStorage, rootAccess)
    }
    if (remote == "/storage/emulated/0" || remote.startsWith("/storage/emulated/0/")) {
        if (shadowRoot != null && remote == "/storage/emulated/0/Android") {
            return shadowRoot.resolve("root/storage/emulated/0/Android").normalize()
        }
        return resolveStorageRelative(remote.removePrefix("/storage/emulated/0"), sharedStorage, rootAccess)
    }
    if (remote == "/storage/self/primary" || remote.startsWith("/storage/self/primary/")) {
        return resolveStorageRelative(remote.removePrefix("/storage/self/primary"), sharedStorage, rootAccess)
    }
    if (shadowRoot != null && (remote == "/storage" || remote == "/storage/emulated" || remote == "/storage/self")) {
        return shadowRoot.resolve(remote.removePrefix("/")).normalize()
    }
    return sharedStorage.resolve(normalizedLogical.removePrefix("/")).normalize()
}

internal fun resolveShellPath(
    currentDirectory: Path,
    requestedPath: String,
    sharedStorage: Path,
    rootAccess: Boolean,
): Path {
    val normalized = requestedPath.replace('\\', '/')
    if (Regex("^[A-Za-z]:/.*").matches(normalized)) return Paths.get(normalized).normalize()
    val relativeSegments = normalized.split('/').filter { it.isNotBlank() && it != "." }
    if (!normalized.startsWith("/") &&
        currentDirectory.normalize() == sharedStorage.normalize() &&
        relativeSegments.firstOrNull() == ".."
    ) {
        return if (rootAccess) Paths.get("/").normalize() else sharedStorage.normalize()
    }
    val absolute = when {
        normalized.isBlank() -> if (rootAccess) "/" else "/storage/emulated/0"
        normalized.startsWith("/") -> normalized
        else -> currentDirectory.resolve(normalized).normalize().toString().replace('\\', '/')
    }
    return if (rootAccess) {
        resolveSharedRemotePath(absolute, sharedStorage, rootAccess = true)
    } else {
        resolveSharedRemotePath(absolute, sharedStorage, rootAccess = false)
    }
}

private fun resolveStorageRelative(suffix: String, sharedStorage: Path, rootAccess: Boolean): Path {
    val parts = suffix.split('/').filter { it.isNotBlank() && it != "." }
    if (parts.isNotEmpty() && parts.first() == "..") {
        return if (rootAccess) Paths.get("/").normalize() else sharedStorage.normalize()
    }
    val cleaned = parts.fold(mutableListOf<String>()) { result, segment ->
        if (segment == "..") {
            if (result.isNotEmpty()) result.removeAt(result.lastIndex)
        } else {
            result.add(segment)
        }
        result
    }
    return cleaned.fold(sharedStorage.normalize()) { path, segment -> path.resolve(segment) }.normalize()
}

internal fun sftpSdcardDirectoryAttributes(timestamp: Long): Map<String, Any> {
    val time = FileTime.fromMillis(timestamp)
    return linkedMapOf(
        "lastModifiedTime" to time,
        "lastAccessTime" to time,
        "creationTime" to time,
        "size" to 0L,
        "isRegularFile" to false,
        "isDirectory" to true,
        "isSymbolicLink" to false,
        "isOther" to false,
        "fileKey" to "sdcard-alias",
    )
}

internal fun normalizeSftpSymbolicLinkAttributes(
    linkAttributes: NavigableMap<String, Any>,
    targetAttributes: Map<String, *>?,
): NavigableMap<String, Any> {
    if (linkAttributes["isSymbolicLink"] != true || targetAttributes == null) return TreeMap(linkAttributes)
    return TreeMap<String, Any>(linkAttributes).apply {
        targetAttributes.forEach { (key, value) -> if (value != null) put(key, value) }
        put("isSymbolicLink", false)
        put("isDirectory", targetAttributes["isDirectory"] == true)
        put("isRegularFile", targetAttributes["isRegularFile"] == true)
        put("isOther", targetAttributes["isOther"] == true)
    }
}

internal fun ensureSftpShadowTree(shadowRoot: Path) {
    listOf(
        shadowRoot.resolve("storage"),
        shadowRoot.resolve("storage/emulated"),
        shadowRoot.resolve("storage/emulated/0"),
        shadowRoot.resolve("storage/self"),
        shadowRoot.resolve("storage/self/primary"),
        shadowRoot.resolve("root"),
        shadowRoot.resolve("root/data"),
        shadowRoot.resolve("root/storage/emulated/0/Android"),
        shadowRoot.resolve("root/storage/emulated/0/Android/data"),
        shadowRoot.resolve("root/storage/emulated/0/Android/obb"),
        shadowRoot.resolve("virtual-root"),
    ).forEach { path -> Files.createDirectories(path) }
    ensureDirectoryLink(shadowRoot.resolve("virtual-root/sdcard"), shadowRoot.resolve("storage/emulated/0"))
    ensureDirectoryLink(shadowRoot.resolve("virtual-root/storage"), shadowRoot.resolve("storage"))
}

private fun ensureDirectoryLink(link: Path, target: Path) {
    if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) return
    runCatching { Files.createSymbolicLink(link, target) }
        .getOrElse { Files.createDirectories(link) }
}

internal fun rootBackedRemotePath(remote: String): String? {
    val path = remote.replace('\\', '/').removeSuffix("/")
    val canonical = when {
        path == "/data" || path.startsWith("/data/") -> path
        path == "/sdcard/Android/data" || path.startsWith("/sdcard/Android/data/") -> "/storage/emulated/0${path.removePrefix("/sdcard")}"
        path == "/sdcard/Android/obb" || path.startsWith("/sdcard/Android/obb/") -> "/storage/emulated/0${path.removePrefix("/sdcard")}"
        path == "/storage/self/primary/Android/data" || path.startsWith("/storage/self/primary/Android/data/") -> "/storage/emulated/0${path.removePrefix("/storage/self/primary")}"
        path == "/storage/self/primary/Android/obb" || path.startsWith("/storage/self/primary/Android/obb/") -> "/storage/emulated/0${path.removePrefix("/storage/self/primary")}"
        else -> path
    }
    return when {
        canonical == "/data" || canonical.startsWith("/data/") -> canonical
        canonical == "/storage/emulated/0/Android" || canonical.startsWith("/storage/emulated/0/Android/") -> canonical
        canonical == "/storage/emulated/0/Android/data" || canonical.startsWith("/storage/emulated/0/Android/data/") -> canonical
        canonical == "/storage/emulated/0/Android/obb" || canonical.startsWith("/storage/emulated/0/Android/obb/") -> canonical
        else -> null
    }
}

internal fun isDeniedSftpPath(remotePath: String, rootAccess: Boolean = false): Boolean {
    if (rootAccess) return false
    val raw = remotePath.replace('\\', '/').let { if (it.startsWith('/')) it else "/$it" }
    val path = raw.trimEnd('/').ifEmpty { "/" }
    val canonical = when {
        path == "/sdcard/Android" || path.startsWith("/sdcard/Android/") ->
            "/storage/emulated/0${path.removePrefix("/sdcard")}" 
        path == "/storage/self/primary/Android" || path.startsWith("/storage/self/primary/Android/") ->
            "/storage/emulated/0${path.removePrefix("/storage/self/primary")}" 
        else -> path
    }
    return canonical == "/data" || canonical.startsWith("/data/") ||
        canonical == "/storage/emulated/0/Android" || canonical.startsWith("/storage/emulated/0/Android/")
}

internal fun resolveRootBackedSftpPath(path: Path, shadowRoot: Path): String? {
    val normalized = path.normalize()
    val root = shadowRoot.resolve("root").normalize()
    if (normalized.startsWith(root)) {
        val suffix = root.relativize(normalized).toString().replace('\\', '/')
        return "/$suffix"
    }
    return rootBackedRemotePath(normalized.toString().replace('\\', '/'))
}

internal fun logicalSftpPath(path: Path, shadowRoot: Path): Path {
    val normalized = path.normalize()
    val shadow = shadowRoot.normalize()
    if (!normalized.startsWith(shadow)) return normalized
    val relative = shadow.relativize(normalized).toString().replace('\\', '/')
    return when {
        relative == "root" -> Paths.get("/")
        relative.startsWith("root/") -> Paths.get("/${relative.removePrefix("root/")}")
        relative == "storage" -> Paths.get("/storage")
        relative.startsWith("storage/") -> Paths.get("/${relative}")
        relative == "virtual-root" -> Paths.get("/")
        relative.startsWith("virtual-root/") -> Paths.get("/${relative.removePrefix("virtual-root/")}")
        else -> normalized
    }
}

internal class SftpPathAliasAccessor(
    private val sharedStorage: Path,
    private val shadowRoot: Path,
    private val rootAccess: Boolean = false,
) : SftpFileSystemAccessor {
    private val rootFiles = ConcurrentHashMap<Path, RootFileTransfer>()
    private val tag = "rockchip-ssh-sftp-SFTP"

    init {
        ensureSftpShadowTree(shadowRoot)
    }

    override fun resolveLocalFilePath(
        subsystem: SftpSubsystemProxy,
        rootDir: Path,
        remotePath: String,
    ): Path {
        if (isDeniedSftpPath(remotePath, rootAccess)) {
            Log.w(tag, "deny protected SFTP path remote=$remotePath")
            throw AccessDeniedException(remotePath, null, "Protected Android directory")
        }
        val resolved = resolveRemoteSftpPath(rootDir, remotePath, sharedStorage, shadowRoot, rootAccess)
        Log.i(tag, "resolveLocalFilePath remote=$remotePath -> ${resolved}")
        return resolved
    }

    override fun readFileAttributes(
        subsystem: SftpSubsystemProxy,
        file: Path,
        view: String,
        vararg linkOptions: LinkOption,
    ): Map<String, *> {
        resolveRootBackedSftpPath(file, shadowRoot)?.let {
            Log.i(tag, "readFileAttributes root-backed file=$file root=$it")
            return rootStat(it).attributes
        }
        if (isSdcardAliasPath(file)) return sftpSdcardDirectoryAttributes(System.currentTimeMillis())
        Log.i(tag, "readFileAttributes default file=$file")
        return SftpFileSystemAccessor.DEFAULT.readFileAttributes(subsystem, file, view, *linkOptions)
    }

    override fun openDirectory(
        subsystem: SftpSubsystemProxy,
        dir: DirectoryHandle,
        file: Path,
        handle: String,
        vararg linkOptions: LinkOption,
    ): DirectoryStream<Path> {
        val rootPath = resolveRootBackedSftpPath(file, shadowRoot)
        if (rootPath != null) {
            Log.i(tag, "openDirectory root-backed file=$file root=$rootPath")
            val children = rootList(rootPath).map { child ->
                val entry = file.resolve(child).normalize()
                materializeRootEntry(entry, "$rootPath/$child")
                entry
            }
            return StaticDirectoryStream(children)
        }
        Log.i(tag, "openDirectory default file=$file")
        return SftpFileSystemAccessor.DEFAULT.openDirectory(subsystem, dir, file, handle, *linkOptions)
    }

    override fun openFile(
        subsystem: SftpSubsystemProxy,
        fileHandle: FileHandle,
        file: Path,
        handle: String,
        options: Set<OpenOption>,
        vararg attrs: FileAttribute<*>,
    ): SeekableByteChannel {
        val rootPath = resolveRootBackedSftpPath(file, shadowRoot)
            ?: return SftpFileSystemAccessor.DEFAULT.openFile(subsystem, fileHandle, file, handle, options, *attrs)
        Log.i(tag, "openFile root-backed file=$file root=$rootPath")
        val temp = Files.createTempFile(shadowRoot.resolve("root-tmp").also { Files.createDirectories(it) }, "sftp-root-", ".tmp")
        val writable = options.any { it == StandardOpenOption.WRITE || it == StandardOpenOption.APPEND || it == StandardOpenOption.CREATE || it == StandardOpenOption.CREATE_NEW || it == StandardOpenOption.TRUNCATE_EXISTING }
        if (!options.contains(StandardOpenOption.TRUNCATE_EXISTING)) {
            rootCopyToTemp(rootPath, temp)
        }
        rootFiles[file.normalize()] = RootFileTransfer(rootPath, temp, writable)
        return SftpFileSystemAccessor.DEFAULT.openFile(subsystem, fileHandle, temp, handle, options, *attrs)
    }

    override fun closeFile(
        subsystem: SftpSubsystemProxy,
        fileHandle: FileHandle,
        file: Path,
        handle: String,
        channel: Channel,
        options: Set<OpenOption>,
    ) {
        val transfer = rootFiles.remove(file.normalize())
        if (transfer == null) {
            SftpFileSystemAccessor.DEFAULT.closeFile(subsystem, fileHandle, file, handle, channel, options)
            return
        }
        try {
            SftpFileSystemAccessor.DEFAULT.closeFile(subsystem, fileHandle, transfer.temp, handle, channel, options)
            if (transfer.writable) rootCopyFromTemp(transfer.temp, transfer.rootPath)
        } finally {
            runCatching { Files.deleteIfExists(transfer.temp) }
        }
    }

    override fun createDirectory(subsystem: SftpSubsystemProxy, path: Path) {
        resolveRootBackedSftpPath(path, shadowRoot)?.let { rootMkdir(it); return }
        SftpFileSystemAccessor.DEFAULT.createDirectory(subsystem, path)
    }

    override fun removeFile(subsystem: SftpSubsystemProxy, path: Path, isDirectory: Boolean) {
        resolveRootBackedSftpPath(path, shadowRoot)?.let { rootRemove(it, isDirectory); return }
        SftpFileSystemAccessor.DEFAULT.removeFile(subsystem, path, isDirectory)
    }

    override fun resolveReportedFileAttributes(
        subsystem: SftpSubsystemProxy,
        file: Path,
        flags: Int,
        attrs: NavigableMap<String, Any>,
        vararg linkOptions: LinkOption,
    ): NavigableMap<String, Any> {
        if (isSdcardAliasPath(file)) {
            return TreeMap<String, Any>(attrs).apply {
                putAll(sftpSdcardDirectoryAttributes(System.currentTimeMillis()))
            }
        }
        if (!Files.isSymbolicLink(file)) return attrs
        val targetAttributes = runCatching {
            SftpFileSystemAccessor.DEFAULT.readFileAttributes(subsystem, file, "basic:*")
        }.getOrNull()
        return normalizeSftpSymbolicLinkAttributes(attrs, targetAttributes)
    }

    private fun isSdcardAliasPath(file: Path): Boolean {
        val value = file.normalize().toString().replace('\\', '/')
        return value == "/sdcard" || value == "sdcard"
    }

    private fun rootList(path: String): List<String> {
        val result = rootShell("/system/bin/ls -A1 ${shellQuote(path)}")
        if (result.code != 0) throw IOException(result.stderr.ifBlank { "ls failed: $path" })
        return result.stdout.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() }.toList()
    }

    private fun materializeRootEntry(entry: Path, rootPath: String) {
        if (Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) return
        entry.parent?.let { Files.createDirectories(it) }
        val attributes = rootStat(rootPath).attributes
        if (attributes["isDirectory"] == true) {
            Files.createDirectories(entry)
        } else {
            Files.createFile(entry)
        }
    }

    private fun rootStat(path: String): RootStat {
        val result = rootShell("/system/bin/stat -c '%F:%s:%Y:%n' ${shellQuote(path)}")
        if (result.code != 0) throw FileNotFoundException(result.stderr.ifBlank { path })
        val line = result.stdout.lineSequence().firstOrNull().orEmpty()
        val parts = line.split(':', limit = 4)
        if (parts.size < 4) throw IOException("invalid stat output: $line")
        val type = parts[0]
        val size = parts[1].toLongOrNull() ?: 0L
        val modified = (parts[2].toLongOrNull() ?: 0L) * 1000L
        val attrs = linkedMapOf<String, Any>(
            "lastModifiedTime" to FileTime.fromMillis(modified),
            "lastAccessTime" to FileTime.fromMillis(modified),
            "creationTime" to FileTime.fromMillis(modified),
            "size" to size,
            "isRegularFile" to (type == "regular file" || type == "regular empty file"),
            "isDirectory" to (type == "directory"),
            "isSymbolicLink" to (type == "symbolic link"),
            "isOther" to (type != "directory" && type != "regular file" && type != "regular empty file" && type != "symbolic link"),
            "fileKey" to "root-backed:$path",
        )
        return RootStat(attrs)
    }

    private fun rootCopyToTemp(rootPath: String, temp: Path) {
        val result = rootShell("/system/bin/cat ${shellQuote(rootPath)} > ${shellQuote(temp.toString())}")
        if (result.code != 0) {
            if (result.stderr.contains("No such file", ignoreCase = true)) return
            throw IOException(result.stderr.ifBlank { "read failed: $rootPath" })
        }
    }

    private fun rootCopyFromTemp(temp: Path, rootPath: String) {
        val parent = rootPath.substringBeforeLast('/', missingDelimiterValue = "/")
        val result = rootShell("/system/bin/mkdir -p ${shellQuote(parent)} && /system/bin/cat ${shellQuote(temp.toString())} > ${shellQuote(rootPath)}")
        if (result.code != 0) throw IOException(result.stderr.ifBlank { "write failed: $rootPath" })
    }

    private fun rootMkdir(rootPath: String) {
        val result = rootShell("/system/bin/mkdir -p ${shellQuote(rootPath)}")
        if (result.code != 0) throw IOException(result.stderr.ifBlank { "mkdir failed: $rootPath" })
    }

    private fun rootRemove(rootPath: String, isDirectory: Boolean) {
        val command = if (isDirectory) "/system/bin/rmdir ${shellQuote(rootPath)}" else "/system/bin/rm -f ${shellQuote(rootPath)}"
        val result = rootShell(command)
        if (result.code != 0) throw IOException(result.stderr.ifBlank { "remove failed: $rootPath" })
    }

    private fun rootShell(command: String): RootShellResult {
        val process = ProcessBuilder("su", "0", "/system/bin/sh", "-c", command).start()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val outThread = Thread { process.inputStream.copyTo(stdout) }.also { it.start() }
        val errThread = Thread { process.errorStream.copyTo(stderr) }.also { it.start() }
        val code = process.waitFor()
        outThread.join()
        errThread.join()
        return RootShellResult(code, stdout.toString(Charsets.UTF_8.name()), stderr.toString(Charsets.UTF_8.name()))
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private data class RootShellResult(val code: Int, val stdout: String, val stderr: String)
    private data class RootStat(val attributes: Map<String, Any>)
    private data class RootFileTransfer(val rootPath: String, val temp: Path, val writable: Boolean)

    private class StaticDirectoryStream(private val paths: List<Path>) : DirectoryStream<Path> {
        override fun iterator(): MutableIterator<Path> = paths.toMutableList().iterator()
        override fun close() = Unit
    }

}
