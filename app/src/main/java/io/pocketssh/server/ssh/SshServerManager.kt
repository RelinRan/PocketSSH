package io.pocketssh.server.ssh

import io.pocketssh.server.config.SshConfig
import android.os.Environment
import org.apache.sshd.scp.server.ScpCommandFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.Closeable
import java.nio.file.Path

internal class SshServerManager(
    private val keyDirectory: Path,
    private val commandResolvers: AndroidCommandResolvers? = null,
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
                )
            } ?: AndroidInteractiveShellFactory(promptUser = config.username)
            commandFactory = ScpCommandFactory.Builder()
                .withDelegate(CommandFactory { _, command ->
                    ProcessCommand(listOf("/system/bin/sh", "-c", command))
                })
                .build()
            subsystemFactories = listOf(
                SftpSubsystemFactory.Builder()
                    .withFileSystemAccessor(SftpPathAliasAccessor(sharedStoragePath(), keyDirectory.resolve("sftp-shadow")))
                    .build()
            )
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
    }
}
