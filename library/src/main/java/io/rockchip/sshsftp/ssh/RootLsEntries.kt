package io.rockchip.sshsftp.ssh

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

internal data class RootLsEntry(
    val type: Type,
    val permissions: String,
    val links: Long = 1L,
    val owner: String = "root",
    val group: String = "root",
    val size: Long,
    val modifiedEpochSeconds: Long,
    val blocks: Long = 0L,
    val name: String,
) {
    enum class Type { DIRECTORY, EXECUTABLE, SYMLINK, FILE }
}

internal fun parseRootLsEntries(text: String): List<RootLsEntry> = text
    .lineSequence()
    .mapNotNull { line ->
        val fields = line.split('\t', limit = 9)
        if (fields.size != 5 && fields.size != 9) return@mapNotNull null
        val type = when (fields[0]) {
            "d" -> RootLsEntry.Type.DIRECTORY
            "x" -> RootLsEntry.Type.EXECUTABLE
            "l" -> RootLsEntry.Type.SYMLINK
            "f" -> RootLsEntry.Type.FILE
            else -> return@mapNotNull null
        }
        RootLsEntry(
            type = type,
            permissions = fields[1],
            links = if (fields.size == 9) fields[2].toLongOrNull() ?: 1L else 1L,
            owner = if (fields.size == 9) fields[3] else "root",
            group = if (fields.size == 9) fields[4] else "root",
            size = fields[if (fields.size == 9) 5 else 2].toLongOrNull() ?: return@mapNotNull null,
            modifiedEpochSeconds = fields[if (fields.size == 9) 6 else 3].toLongOrNull() ?: return@mapNotNull null,
            blocks = fields.getOrNull(7)?.toLongOrNull() ?: 0L,
            name = fields[if (fields.size == 9) 8 else 4],
        )
    }
    .toList()

internal fun parseRootLsLongEntries(text: String): List<RootLsEntry> = text
    .lineSequence()
    .mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"), limit = 9)
        if (fields.size < 8 || fields[0].length < 10) return@mapNotNull null
        val type = when (fields[0].first()) {
            'd' -> RootLsEntry.Type.DIRECTORY
            'l' -> RootLsEntry.Type.SYMLINK
            '-' -> if ('x' in fields[0]) RootLsEntry.Type.EXECUTABLE else RootLsEntry.Type.FILE
            else -> return@mapNotNull null
        }
        RootLsEntry(
            type = type,
            permissions = fields[0],
            links = fields[1].toLongOrNull() ?: 1L,
            owner = fields[2],
            group = fields[3],
            size = fields[4].toLongOrNull() ?: return@mapNotNull null,
            modifiedEpochSeconds = if (fields.size >= 9) {
                parseRootLsModifiedEpochSeconds(fields[5], fields[6], fields[7])
            } else {
                parseRootLsIsoModifiedEpochSeconds(fields[5], fields[6])
            } ?: return@mapNotNull null,
            name = fields[if (fields.size >= 9) 8 else 7],
        )
    }
    .toList()

private fun parseRootLsModifiedEpochSeconds(month: String, day: String, timeOrYear: String): Long? {
    val pattern: String
    val value: String
    if (':' in timeOrYear) {
        pattern = "MMM dd HH:mm yyyy"
        value = "$month $day $timeOrYear ${Calendar.getInstance().get(Calendar.YEAR)}"
    } else {
        pattern = "MMM dd yyyy"
        value = "$month $day $timeOrYear"
    }
    return runCatching {
        SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
            .parse(value)
            ?.time
            ?.div(1000L)
    }.getOrNull()
}

private fun parseRootLsIsoModifiedEpochSeconds(date: String, time: String): Long? = runCatching {
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }
        .parse("$date $time")
        ?.time
        ?.div(1000L)
}.getOrNull()
