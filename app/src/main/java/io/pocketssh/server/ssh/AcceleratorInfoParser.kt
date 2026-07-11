package io.pocketssh.server.ssh

import java.util.Locale
import kotlin.math.roundToInt

internal val RKNN_LOAD_PATHS = listOf(
    "/d/rknpu/load",
    "/sys/kernel/debug/rknpu/load",
)

internal val RKNN_FREQUENCY_PATHS = listOf(
    "/d/rknpu/freq",
    "/sys/kernel/debug/rknpu/freq",
)

internal fun parseAcceleratorKeyValueRows(text: String): List<List<String>> {
    return text.lineSequence().mapNotNull { rawLine ->
        val line = rawLine.trim()
        val separator = line.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        val key = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim()
        if (key.isBlank() || value.isBlank()) null else listOf(key, value)
    }.toList()
}

internal fun formatAcceleratorFrequency(value: String): String {
    val hz = value.trim().toLongOrNull() ?: return value.trim()
    val mhz = hz / 1_000_000L
    return String.format(Locale.US, "%,d MHz (%d Hz)", mhz, hz)
}

internal fun formatAcceleratorFrequencyList(value: String): String {
    val frequencies = value.trim().split(Regex("\\s+")).mapNotNull(String::toLongOrNull)
    if (frequencies.isEmpty()) return value.trim()
    return frequencies.joinToString(", ") { hz ->
        String.format(Locale.US, "%,d MHz", hz / 1_000_000L)
    }
}

internal fun formatAcceleratorRows(rows: List<List<String>>): List<List<String>> {
    return rows.map { row ->
        val key = row.getOrNull(0).orEmpty()
        val value = row.getOrNull(1).orEmpty()
        val formatted = when (key) {
            "cur_freq", "min_freq", "max_freq", "debug_freq" -> formatAcceleratorFrequency(value)
            "available_frequencies" -> formatAcceleratorFrequencyList(value)
            else -> value
        }
        listOf(key, formatted)
    }
}

internal fun parseAcceleratorUsagePercent(value: String): Int? {
    val percentages = PERCENT_VALUE_REGEX.findAll(value)
        .mapNotNull { match -> match.groupValues[1].toFloatOrNull() }
        .toList()
    if (percentages.isNotEmpty()) {
        return percentages.average().roundToInt().coerceIn(0, 100)
    }
    val number = NUMBER_VALUE_REGEX.find(value)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: return null
    return when {
        number in 0f..100f -> number.roundToInt()
        number in 0f..1000f -> (number / 10f).roundToInt().coerceIn(0, 100)
        else -> null
    }
}

internal fun withAcceleratorUsage(rows: List<List<String>>): List<List<String>> {
    if (rows.any { it.firstOrNull() == "usage_percent" }) return rows
    val values = rows.associate { row -> row.getOrElse(0) { "" } to row.getOrElse(1) { "" } }
    val usage = ACCELERATOR_USAGE_KEYS.firstNotNullOfOrNull { key ->
        values[key]?.let(::parseAcceleratorUsagePercent)
    } ?: return rows
    return rows + listOf(listOf("usage_percent", "$usage%"))
}

private val PERCENT_VALUE_REGEX = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*%")
private val NUMBER_VALUE_REGEX = Regex("([0-9]+(?:\\.[0-9]+)?)")
private val ACCELERATOR_USAGE_KEYS = listOf(
    "core_load",
    "utilisation",
    "utilization",
    "gpu_busy_percentage",
    "cur_load",
    "busy",
    "usage",
    "load",
)
