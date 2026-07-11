package io.pocketssh.server.ssh

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class RemoteCommandRunnerTest {
    @Test
    fun interruptsRunningProcessWhenCancellationIsRequested() {
        val checks = AtomicInteger()
        val runner = RemoteCommandRunner(
            shellPath = if (File("/bin/sh").exists()) "/bin/sh" else "powershell.exe",
            cwdProvider = { File(".") },
            cancellationRequested = { checks.incrementAndGet() > 2 },
        )
        val command = if (File("/bin/sh").exists()) {
            listOf("sleep", "5")
        } else {
            listOf("powershell.exe", "-Command", "Start-Sleep -Seconds 5")
        }
        val started = System.currentTimeMillis()

        var interrupted = false
        try {
            runner.executeForText(command)
        } catch (_: RemoteCommandInterrupted) {
            interrupted = true
        }

        assertTrue(interrupted)
        assertTrue(System.currentTimeMillis() - started < 3_000)
    }
}
