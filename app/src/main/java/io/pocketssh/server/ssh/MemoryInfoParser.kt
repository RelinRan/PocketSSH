package io.pocketssh.server.ssh

internal fun calculateMemoryUsagePercent(memInfo: String): Double? {
    val values = memInfo.lineSequence().mapNotNull { line ->
        val key = line.substringBefore(':', "").trim()
        val value = line.substringAfter(':', "").trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
        if (key.isBlank() || value == null) null else key to value
    }.toMap()
    val total = values["MemTotal"] ?: return null
    val available = values["MemAvailable"] ?: return null
    if (total <= 0L) return null
    return ((total - available).coerceIn(0L, total) * 100.0) / total
}
