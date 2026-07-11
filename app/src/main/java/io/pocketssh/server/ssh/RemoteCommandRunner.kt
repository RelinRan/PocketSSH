package io.pocketssh.server.ssh

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal typealias CommandResult = RemoteCommandRunner.Result

internal class RemoteCommandInterrupted : RuntimeException()

internal class RemoteCommandRunner(
    private val shellPath: String,
    private val cwdProvider: () -> File,
    private val cancellationRequested: () -> Boolean = { false },
) {
    data class Result(
        val code: Int,
        val message: String,
    )

    fun executeFirstText(commands: List<List<String>>): String? {
        return executeFirstText(commands, COMMAND_TIMEOUT_SECONDS)
    }

    fun executeFirstText(commands: List<List<String>>, timeoutSeconds: Long): String? {
        for (command in commands) {
            val result = executeForText(command, timeoutSeconds)
            if (result != null) return result
        }
        return null
    }

    fun executeForText(command: List<String>): String? {
        return executeForText(command, COMMAND_TIMEOUT_SECONDS)
    }

    fun executeForText(command: List<String>, timeoutSeconds: Long): String? {
        return try {
            val process = ProcessBuilder(command)
                .directory(cwdProvider())
                .redirectErrorStream(true)
                .start()
            if (!waitForProcess(process, timeoutSeconds)) return null
            val result = process.inputStream.readAllText()
            val exit = process.exitValue()
            if (exit == 0) result else null
        } catch (e: RemoteCommandInterrupted) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    fun executeForResult(command: List<String>): Result {
        return try {
            val process = ProcessBuilder(command)
                .directory(cwdProvider())
                .redirectErrorStream(true)
                .start()
            if (!waitForProcess(process, COMMAND_TIMEOUT_SECONDS)) return Result(124, "command timeout")
            Result(process.exitValue(), process.inputStream.readAllText().trim())
        } catch (e: RemoteCommandInterrupted) {
            throw e
        } catch (e: Throwable) {
            Result(1, e.message ?: e.javaClass.simpleName)
        }
    }

    fun executeFirstResult(commands: List<List<String>>): Result {
        var last = Result(1, "no command executed")
        commands.forEach { command ->
            val result = executeForResult(command)
            if (result.code == 0) return result
            last = result
        }
        return last
    }

    fun rootShellCommands(command: String): List<List<String>> {
        return listOf(
            listOf("su", "0", shellPath, "-c", command),
            listOf("su", "root", shellPath, "-c", command),
            listOf("su", shellPath, "-c", command),
        )
    }

    private fun waitForProcess(process: Process, timeoutSeconds: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (process.isAlive) {
            if (cancellationRequested()) {
                destroyProcessTree(process)
                throw RemoteCommandInterrupted()
            }
            if (System.nanoTime() >= deadline) {
                destroyProcessTree(process)
                process.waitFor(1, TimeUnit.SECONDS)
                return false
            }
            process.waitFor(PROCESS_POLL_MILLIS, TimeUnit.MILLISECONDS)
        }
        return true
    }

    private fun destroyProcessTree(process: Process) {
        process.destroy()
        if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
        }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }

    companion object {
        const val COMMAND_TIMEOUT_SECONDS = 10L
        private const val PROCESS_POLL_MILLIS = 50L

        fun shellEscape(value: String): String {
            return "'${value.replace("'", "'\\''")}'"
        }
    }
}

private fun InputStream.readAllText(): String {
    return bufferedReader().use { it.readText() }
}
