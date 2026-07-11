package io.pocketssh.server.ssh

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class ParsedSystemTime(
    val epochMillis: Long,
    val display: String,
    val toyboxFallback: String,
)

internal fun parseSystemTime(value: String, timeZone: TimeZone = TimeZone.getDefault()): ParsedSystemTime? {
    val input = value.trim()
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        isLenient = false
        this.timeZone = timeZone
    }
    val date = runCatching { format.parse(input) }.getOrNull() ?: return null
    if (format.format(date) != input) return null
    val fallback = SimpleDateFormat("MMddHHmmyyyy.ss", Locale.US).apply { this.timeZone = timeZone }.format(date)
    return ParsedSystemTime(date.time, input, fallback)
}
