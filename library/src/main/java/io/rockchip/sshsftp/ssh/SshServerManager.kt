package io.rockchip.sshsftp.ssh

import io.rockchip.sshsftp.config.SshConfig
import android.os.Environment
import android.util.Log
import org.apache.sshd.scp.server.ScpCommandFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.sftp.server.SftpSubsystem
import org.apache.sshd.sftp.server.SftpFileSystemAccessor
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.Environment as SshEnvironment
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class SshServerManager(
    private val keyDirectory: Path,
    private val commandResolvers: AndroidCommandResolvers? = null,
    private val rootAccess: Boolean = false,
) : Closeable {
    private var server: SshServer? = null

    @Synchronized
    fun start(config: SshConfig) {
        if (isRunning()) return
        keyDirectory.toFile().mkdirs()
        ensureUserHome(keyDirectory)
        val sshd = SshServer.setUpDefaultServer().apply {
            host = config.bindAddress
            port = config.port
            keyPairProvider = SimpleGeneratorHostKeyProvider(keyDirectory.resolve("host-key.ser"))
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { username, password, _ ->
                credentialsMatch(config, username, password)
            }
            shellFactory = commandResolvers?.let { resolvers ->
                AndroidInteractiveShellFactory(
                    promptUser = config.username,
                    appInfoResolver = resolvers.appInfoResolver,
                    appListResolver = resolvers.appListResolver,
                    appLaunchActivityResolver = resolvers.appLaunchActivityResolver,
                    appStartResolver = resolvers.appStartResolver,
                    runningAppResolver = resolvers.runningAppResolver,
                    cameraResolver = resolvers.cameraResolver,
                    volumeResolver = resolvers.volumeResolver,
                    initialDirectory = sharedStoragePath().toFile(),
                )
            } ?: AndroidInteractiveShellFactory(promptUser = config.username, initialDirectory = sharedStoragePath().toFile())
            commandFactory = ScpCommandFactory.Builder()
                .withDelegate(CommandFactory { _, command ->
                    ProcessCommand(listOf("/system/bin/sh", "-c", command))
                })
                .build()
            subsystemFactories = listOf(LoggingSftpSubsystemFactory(
                SftpPathAliasAccessor(
                    sharedStorage = sharedStoragePath(),
                    shadowRoot = keyDirectory.resolve("sftp-shadow"),
                    rootAccess = rootAccess,
                )
            ))
        }
        try {
            sshd.start()
            server = sshd
        } catch (error: Throwable) {
            runCatching { sshd.stop(true) }
            throw error
        }
    }

    @Synchronized
    fun stop() {
        val current = server ?: return
        server = null
        current.stop(true)
    }

    fun isRunning(): Boolean = server?.isOpen == true

    override fun close() = stop()

    companion object {
        internal fun credentialsMatch(config: SshConfig, username: String?, password: String?): Boolean =
            username == config.username && password == config.password

        internal fun ensureUserHome(directory: Path) {
            if (!System.getProperty("user.home").isNullOrBlank()) return
            directory.toFile().mkdirs()
            System.setProperty("user.home", directory.toAbsolutePath().toString())
        }

        internal fun sharedStoragePath(): Path = Environment.getExternalStorageDirectory().toPath()

        fun detectRootAccess(timeoutMs: Long = 2000L): Boolean {
            val process = runCatching {
                ProcessBuilder("su", "0", "id")
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull() ?: return false
            return try {
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    false
                } else {
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    process.exitValue() == 0 && output.contains("uid=0")
                }
            } finally {
                process.destroy()
            }
        }
    }
}

private class LoggingSftpSubsystemFactory(accessor: SftpFileSystemAccessor) : SftpSubsystemFactory() {
    init {
        fileSystemAccessor = accessor
    }

    override fun createSubsystem(channel: ChannelSession): Command = try {
        Log.i(TAG, "Creating SFTP subsystem")
        object : SftpSubsystem(channel, this) {
            override fun start(channel: ChannelSession, env: SshEnvironment) = try {
                Log.i(TAG, "Starting SFTP subsystem")
                super.start(channel, env)
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to start SFTP subsystem", error)
                throw error
            }

            override fun run() = try {
                Log.i(TAG, "Running SFTP subsystem")
                super.run()
            } catch (error: Throwable) {
                Log.e(TAG, "SFTP subsystem terminated", error)
                throw error
            }
        }
    } catch (error: Throwable) {
        Log.e(TAG, "Failed to create SFTP subsystem", error)
        throw error
    }

    companion object {
        private const val TAG = "rockchip-ssh-sftp-SFTP"
    }
}
