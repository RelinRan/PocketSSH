package io.pocketssh.server.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SshServiceContractTest {
    @Test fun `maps explicit actions to commands`() {
        assertEquals(SshCommand.START, SshServiceContract.commandFor(SshServiceContract.ACTION_START))
        assertEquals(SshCommand.STOP, SshServiceContract.commandFor(SshServiceContract.ACTION_STOP))
        assertEquals(SshCommand.START, SshServiceContract.commandFor(null))
    }

    @Test fun `state labels are stable`() {
        assertEquals("ONLINE", SshState.RUNNING.label)
        assertEquals("OFFLINE", SshState.STOPPED.label)
        assertEquals("ERROR", SshState.ERROR.label)
    }
}
