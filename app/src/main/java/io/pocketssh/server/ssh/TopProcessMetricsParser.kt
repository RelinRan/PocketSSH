package io.pocketssh.server.ssh

import java.util.Locale
import kotlin.math.roundToLong

internal data class TopProcessMetrics(
    val priority: String?,
    val nice: String?,
    val virtKb: Long?,
    val rssKb: Long?,
    val sharedKb: Long?,
    val state: String?,
    val cpuPercent: String?,
    val memPercent: String?,
    val timePlus: String?,
)

internal fun parseTopProcessMetrics(text: String): Map<String, TopProcessMetrics> {
    val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    var header: List<String>? = null
    val rows = linkedMapOf<String, TopProcessMetrics>()

    lines.forEach { line ->
        val columns = line.split(Regex("\\s+")).filter(String::isNotBlank)
        if (header == null) {
            val expanded = expandTopHeader(columns)
            val names = expanded.map(::normalizeTopHeader)
            if (names.contains("PID") && names.any { it in TOP_METRIC_HEADERS }) {
                header = names
            }
            return@forEach
        }

        val names = header ?: return@forEach
        val pidIndex = names.indexOf("PID")
        val pid = columns.getOrNull(pidIndex)?.takeIf { it.all(Char::isDigit) } ?: return@forEach
        fun value(vararg aliases: String): String? {
            val index = aliases.firstNotNullOfOrNull { alias ->
                names.indexOf(alias).takeIf { it >= 0 }
            } ?: return null
            return columns.getOrNull(index)?.takeIf { it != "-" }
        }

        rows[pid] = TopProcessMetrics(
            priority = value("PR", "PRI"),
            nice = value("NI"),
            virtKb = value("VIRT")?.let(::parseTopMemoryKb),
            rssKb = value("RES")?.let(::parseTopMemoryKb),
            sharedKb = value("SHR")?.let(::parseTopMemoryKb),
            state = value("S", "STATE"),
            cpuPercent = value("CPU")?.trimEnd('%'),
            memPercent = value("MEM")?.trimEnd('%'),
            timePlus = value("TIME+", "TIME"),
        )
    }
    return rows
}

private fun expandTopHeader(columns: List<String>): List<String> {
    return columns.flatMap { token ->
        val match = COMBINED_TOP_HEADER.matchEntire(token)
        if (match == null) listOf(token) else listOf(match.groupValues[1], match.groupValues[2])
    }
}

private fun normalizeTopHeader(value: String): String {
    val upper = value.trim().uppercase(Locale.US)
    return when (upper.trim('%', '[', ']')) {
        "CPU" -> "CPU"
        "MEM" -> "MEM"
        else -> upper.trim('[', ']')
    }
}

private fun parseTopMemoryKb(value: String): Long? {
    val normalized = value.trim().replace(",", "").uppercase(Locale.US)
    val match = TOP_MEMORY_REGEX.matchEntire(normalized) ?: return null
    val amount = match.groupValues[1].toDoubleOrNull() ?: return null
    val multiplier = when (match.groupValues[2]) {
        "", "K", "KB" -> 1.0
        "M", "MB" -> 1024.0
        "G", "GB" -> 1024.0 * 1024.0
        "T", "TB" -> 1024.0 * 1024.0 * 1024.0
        else -> return null
    }
    return (amount * multiplier).roundToLong()
}

private val COMBINED_TOP_HEADER = Regex("^([A-Za-z]+)\\[(%?[A-Za-z]+)]$")
private val TOP_MEMORY_REGEX = Regex("^([0-9]+(?:\\.[0-9]+)?)([KMGT]B?)?$")
private val TOP_METRIC_HEADERS = setOf("PR", "PRI", "NI", "VIRT", "RES", "SHR", "S", "CPU", "MEM", "TIME", "TIME+")
