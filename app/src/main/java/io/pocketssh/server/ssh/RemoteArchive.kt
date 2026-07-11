package io.pocketssh.server.ssh

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal class RemoteArchive(
    private val isInterrupted: () -> Boolean,
) {
    data class Result(
        val files: Int = 0,
        val dirs: Int = 0,
        val bytes: Long = 0L,
    )

    class Interrupted : IOException()

    fun zip(zip: File, sources: List<File>): Result {
        zip.parentFile?.mkdirs()
        val zipCanonical = zip.canonicalFile
        var files = 0
        var dirs = 0
        var bytes = 0L
        ZipOutputStream(zip.outputStream().buffered()).use { output ->
            val added = mutableSetOf<String>()
            sources.forEach { source ->
                val root = source.canonicalFile
                val baseName = root.name.ifBlank { "root" }
                val result = addZipSource(
                    output = output,
                    source = root,
                    entryName = baseName,
                    zipFile = zipCanonical,
                    added = added,
                )
                files += result.files
                dirs += result.dirs
                bytes += result.bytes
            }
        }
        return Result(files = files, dirs = dirs, bytes = bytes)
    }

    fun unzip(zip: File, dest: File): Result {
        dest.mkdirs()
        if (!dest.isDirectory) {
            throw IOException("${dest.path}: not a directory")
        }
        val destRoot = dest.canonicalFile
        var files = 0
        var dirs = 0
        var bytes = 0L
        ZipFile(zip).use { zipFile ->
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                throwIfInterrupted()
                val entry = entries.nextElement()
                val target = File(destRoot, entry.name).canonicalFile
                if (!isInsideDirectory(destRoot, target)) {
                    throw IOException("blocked unsafe zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    if (target.mkdirs() || target.isDirectory) dirs++
                    continue
                }
                target.parentFile?.mkdirs()
                zipFile.getInputStream(entry).use { input ->
                    target.outputStream().use { output ->
                        bytes += copyInterruptibly(input, output)
                    }
                }
                entry.time.takeIf { it > 0L }?.let(target::setLastModified)
                files++
            }
        }
        return Result(files = files, dirs = dirs, bytes = bytes)
    }

    private fun addZipSource(
        output: ZipOutputStream,
        source: File,
        entryName: String,
        zipFile: File,
        added: MutableSet<String>,
    ): Result {
        throwIfInterrupted()
        if (source.canonicalFile == zipFile) return Result()
        val normalizedEntryName = entryName.replace('\\', '/').trimStart('/')
        if (normalizedEntryName.isBlank() || normalizedEntryName.contains("../")) {
            throw IOException("unsafe zip entry: $entryName")
        }
        if (source.isDirectory) {
            var files = 0
            var dirs = 0
            var bytes = 0L
            val dirEntryName = normalizedEntryName.trimEnd('/') + "/"
            if (added.add(dirEntryName)) {
                val entry = ZipEntry(dirEntryName)
                source.lastModified().takeIf { it > 0L }?.let { entry.time = it }
                output.putNextEntry(entry)
                output.closeEntry()
                dirs++
            }
            source.listFiles().orEmpty()
                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.US) })
                .forEach { child ->
                    val childStats = addZipSource(output, child, dirEntryName + child.name, zipFile, added)
                    files += childStats.files
                    dirs += childStats.dirs
                    bytes += childStats.bytes
                }
            return Result(files = files, dirs = dirs, bytes = bytes)
        }
        if (!source.isFile) return Result()
        if (!added.add(normalizedEntryName)) return Result()
        val entry = ZipEntry(normalizedEntryName)
        source.lastModified().takeIf { it > 0L }?.let { entry.time = it }
        output.putNextEntry(entry)
        val copied = source.inputStream().use { input -> copyInterruptibly(input, output) }
        output.closeEntry()
        return Result(files = 1, bytes = copied)
    }

    private fun copyInterruptibly(input: InputStream, output: OutputStream): Long {
        var copied = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            throwIfInterrupted()
            val count = input.read(buffer)
            if (count == -1) break
            if (count <= 0) continue
            output.write(buffer, 0, count)
            copied += count
        }
        return copied
    }

    private fun throwIfInterrupted() {
        if (isInterrupted()) throw Interrupted()
    }

    private fun isInsideDirectory(root: File, target: File): Boolean {
        val rootPath = root.canonicalPath.trimEnd(File.separatorChar)
        val targetPath = target.canonicalPath
        return targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
    }

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
    }
}
