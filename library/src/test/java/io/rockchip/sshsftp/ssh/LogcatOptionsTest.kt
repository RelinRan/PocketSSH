package io.rockchip.sshsftp.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class LogcatOptionsTest {
    @Test
    fun defaultsToAllBuffersAndVerboseLevel() {
        val options = parseLogcatOptions(listOf("logs"))

        assertEquals(listOf("logcat", "-b", "all", "-v", "threadtime", "*:V"), options.command())
    }

    @Test
    fun buildsBufferTailTagAndLevelArguments() {
        val options = parseLogcatOptions(
            listOf("logs", "--buffer", "crash", "--tail", "500", "--tag", "Camera", "--level", "W")
        )

        assertEquals(
            listOf("logcat", "-b", "crash", "-v", "threadtime", "-T", "500", "Camera:W", "*:S"),
            options.command(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSinceAndTailTogether() {
        parseLogcatOptions(listOf("logs", "--since", "10m", "--tail", "500"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownBuffer() {
        parseLogcatOptions(listOf("logs", "--buffer", "invalid"))
    }
}
