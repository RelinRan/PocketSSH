package io.pocketssh.server.ssh

import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProcessCommandTest {
    @Test
    fun `forwards output error and exit status`() {
        val commandLine = if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("cmd.exe", "/c", "echo output & echo error 1>&2 & exit /b 7")
        } else {
            listOf("/bin/sh", "-c", "echo output; echo error >&2; exit 7")
        }
        val command = ProcessCommand(commandLine)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exited = CountDownLatch(1)
        var exitCode = -1
        command.setInputStream(ByteArrayInputStream(ByteArray(0)))
        command.setOutputStream(stdout)
        command.setErrorStream(stderr)
        command.setExitCallback(object : ExitCallback {
            override fun onExit(exitValue: Int, exitMessage: String?, closeImmediately: Boolean) {
                exitCode = exitValue
                exited.countDown()
            }
        })

        command.start(ChannelSession(), ChannelSession().environment)

        assertTrue(exited.await(5, TimeUnit.SECONDS))
        assertEquals(7, exitCode)
        assertTrue(stdout.toString().contains("output"))
        assertTrue(stderr.toString().contains("error"))
    }
}
