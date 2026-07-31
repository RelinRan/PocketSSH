package io.rockchip.sshsftp.ssh

import java.util.Locale

internal data class CpuUsage(
    val usagePercent: Double,
    val userPercent: Double,
    val systemPercent: Double,
    val idlePercent: Double,
)

private data class CpuTicks(val user: Long, val system: Long, val idle: Long, val total: Long)

internal fun calculateCpuUsage(before: String, after: String): Map<String, CpuUsage> {
    val first = parseCpuTicks(before)
    val second = parseCpuTicks(after)
    return second.mapNotNull { (name, end) ->
        val start = first[name] ?: return@mapNotNull null
        val total = end.total - start.total
        if (total <= 0) return@mapNotNull null
        val user = end.user - start.user
        val system = end.system - start.system
        val idle = end.idle - start.idle
        name to CpuUsage(
            usagePercent = (total - idle) * 100.0 / total,
            userPercent = user * 100.0 / total,
            systemPercent = system * 100.0 / total,
            idlePercent = idle * 100.0 / total,
        )
    }.toMap()
}

internal fun formatCpuTemperature(value: String): String? {
    val raw = value.trim().toDoubleOrNull() ?: return null
    val celsius = if (raw > 1000.0) raw / 1000.0 else raw
    return String.format(Locale.US, "%.1f C", celsius)
}

internal fun parseCpuIndexList(value: String): Set<Int> = value
    .trim()
    .split(Regex("[\\s,]+"))
    .flatMap { token ->
        val range = token.split('-', limit = 2).mapNotNull(String::toIntOrNull)
        when (range.size) {
            1 -> listOf(range[0])
            2 -> (range[0]..range[1]).toList()
            else -> emptyList()
        }
    }
    .toSet()

private fun parseCpuTicks(text: String): Map<String, CpuTicks> = text.lineSequence().mapNotNull { line ->
    val fields = line.trim().split(Regex("\\s+"))
    val name = fields.firstOrNull()?.takeIf { it == "cpu" || it.matches(Regex("cpu\\d+")) }
        ?: return@mapNotNull null
    val values = fields.drop(1).map { it.toLongOrNull() ?: 0L }
    if (values.size < 4) return@mapNotNull null
    val user = values[0] + values.getOrElse(1) { 0L }
    val system = values.getOrElse(2) { 0L } + values.getOrElse(5) { 0L } + values.getOrElse(6) { 0L }
    val idle = values[3] + values.getOrElse(4) { 0L }
    name to CpuTicks(user, system, idle, values.sum())
}.toMap()
