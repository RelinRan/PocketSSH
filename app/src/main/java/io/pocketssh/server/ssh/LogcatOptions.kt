package io.pocketssh.server.ssh

internal data class LogcatOptions(
    val buffer: String = "all",
    val since: String? = null,
    val tail: Int? = null,
    val tag: String? = null,
    val level: String = "V",
    val packageName: String? = null,
) {
    fun command(): List<String> = buildList {
        addAll(listOf("logcat", "-b", buffer, "-v", "threadtime"))
        since?.let { addAll(listOf("-T", it)) }
        tail?.let { addAll(listOf("-T", it.toString())) }
        if (tag != null) addAll(listOf("$tag:$level", "*:S")) else add("*:$level")
    }
}

internal fun parseLogcatOptions(args: List<String>): LogcatOptions {
    fun value(longName: String, shortName: String): String? {
        val index = args.indexOfFirst { it == longName || it == shortName }
        if (index >= 0) return args.getOrNull(index + 1)
        return args.firstOrNull { it.startsWith("$longName=") }?.substringAfter('=')
    }
    val buffer = value("--buffer", "-b") ?: "all"
    require(buffer in LOGCAT_BUFFERS) { "unsupported buffer: $buffer" }
    val level = (value("--level", "-l") ?: "V").uppercase()
    require(level in LOGCAT_LEVELS) { "unsupported level: $level" }
    val tailText = value("--tail", "-n")
    val tail = tailText?.toIntOrNull()?.takeIf { it > 0 }
    require(tailText == null || tail != null) { "tail must be a positive integer" }
    val since = value("--since", "-s")
    require(since == null || tail == null) { "--since and --tail cannot be used together" }
    return LogcatOptions(
        buffer = buffer,
        since = since,
        tail = tail,
        tag = value("--tag", "-t"),
        level = level,
        packageName = value("--package", "-p"),
    )
}

private val LOGCAT_BUFFERS = setOf("all", "main", "system", "crash", "events", "radio", "kernel")
private val LOGCAT_LEVELS = setOf("V", "D", "I", "W", "E", "F")
