package io.rockchip.sshsftp.ssh

import io.rockchip.sshsftp.config.SshConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SshServerManagerTest {
    @Test
    fun `password matching is exact`() {
        val config = SshConfig("127.0.0.1", 2222, "android", "secret", true)
        assertTrue(SshServerManager.credentialsMatch(config, "android", "secret"))
        assertFalse(SshServerManager.credentialsMatch(config, "Android", "secret"))
        assertFalse(SshServerManager.credentialsMatch(config, "android", "Secret"))
    }

    @Test
    fun `start and stop are idempotent`() {
        val manager = SshServerManager(Files.createTempDirectory("pocketssh-key"))
        val config = SshConfig("127.0.0.1", 0.coerceAtLeast(1), "android", "secret", true)
        manager.stop()
        manager.stop()
        assertFalse(manager.isRunning())
    }

    @Test
    fun `supplies android user home when JVM property is absent`() {
        val original = System.getProperty("user.home")
        val directory = Files.createTempDirectory("pocketssh-home")
        try {
            System.clearProperty("user.home")
            SshServerManager.ensureUserHome(directory)
            assertTrue(System.getProperty("user.home").isNotBlank())
        } finally {
            if (original == null) System.clearProperty("user.home") else System.setProperty("user.home", original)
        }
    }
}
