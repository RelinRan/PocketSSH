package io.pocketssh.server

import io.pocketssh.server.service.SshState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityStateTest {
    @Test
    fun `requests all files access on Android 11 and newer when missing`() {
        assertFalse(MainActivity.shouldRequestAllFilesAccess(29, false))
        assertTrue(MainActivity.shouldRequestAllFilesAccess(30, false))
        assertTrue(MainActivity.shouldRequestAllFilesAccess(33, false))
        assertFalse(MainActivity.shouldRequestAllFilesAccess(33, true))
    }

    @Test
    fun `recovers service when persisted state says it should be running`() {
        assertTrue(MainActivity.shouldRecoverService(SshState.STARTING))
        assertTrue(MainActivity.shouldRecoverService(SshState.RUNNING))
        assertFalse(MainActivity.shouldRecoverService(SshState.STOPPED))
        assertFalse(MainActivity.shouldRecoverService(SshState.ERROR))
    }
}
