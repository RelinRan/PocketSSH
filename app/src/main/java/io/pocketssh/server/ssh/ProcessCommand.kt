package io.pocketssh.server.ssh

import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ProcessCommand(private val commandLine: List<String>) : Command {
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var error: OutputStream? = null
    private var callback: ExitCallback? = null
    private var process: Process? = null
    private val executor = Executors.newCachedThreadPool()
    private val closed = AtomicBoolean(false)

    override fun setInputStream(input: InputStream) { this.input = input }
    override fun setOutputStream(output: OutputStream) { this.output = output }
    override fun setErrorStream(error: OutputStream) { this.error = error }
    override fun setExitCallback(callback: ExitCallback) { this.callback = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        val started = ProcessBuilder(commandLine).start().also { process = it }
        executor.execute { input?.use { source -> runCatching { started.outputStream.use(source::copyTo) } } }
        executor.execute { runCatching { started.inputStream.use { it.copyTo(output ?: OutputStream.nullOutputStream()) } } }
        executor.execute { runCatching { started.errorStream.use { it.copyTo(error ?: OutputStream.nullOutputStream()) } } }
        executor.execute {
            try {
                val status = started.waitFor()
                callback?.onExit(status)
            } catch (_: InterruptedException) {
                if (!closed.get()) {
                    Thread.currentThread().interrupt()
                }
            } finally {
                executor.shutdown()
            }
        }
    }

    override fun destroy(channel: ChannelSession) {
        closed.set(true)
        process?.destroyForcibly()
        executor.shutdown()
    }
}
