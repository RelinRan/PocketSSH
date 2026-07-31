package io.rockchip.sshsftp.app.boot

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootActionsTest {
    @Test
    fun `accepts only supported boot actions`() {
        assertTrue(BootActions.isSupported(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(BootActions.isSupported(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertFalse(BootActions.isSupported(Intent.ACTION_BATTERY_CHANGED))
        assertFalse(BootActions.isSupported(null))
    }
}
