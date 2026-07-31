package io.rockchip.sshsftp.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCompatibilityFallbacksTest {
    @Test
    fun unavailableAcceleratorStillReturnsUsefulRows() {
        val rows = unavailableAcceleratorRows("NPU/APU", "MediaTek")

        assertEquals(listOf("type", "NPU/APU"), rows[0])
        assertEquals(listOf("vendor", "MediaTek"), rows[1])
        assertTrue(rows.any { it.first() == "status" })
    }
}
