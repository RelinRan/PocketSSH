package io.pocketssh.server

import io.pocketssh.server.service.SshState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityStateTest {
    @Test
    fun `recovers service when persisted state says it should be running`() {
        assertTrue(MainActivity.shouldRecoverService(SshState.STARTING))
        assertTrue(MainActivity.shouldRecoverService(SshState.RUNNING))
        assertFalse(MainActivity.shouldRecoverService(SshState.STOPPED))
        assertFalse(MainActivity.shouldRecoverService(SshState.ERROR))
    }
}
