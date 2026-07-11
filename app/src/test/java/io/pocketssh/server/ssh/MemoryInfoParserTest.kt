package io.pocketssh.server.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryInfoParserTest {
    @Test
    fun calculatesUsageFromTotalAndAvailableMemory() {
        val memInfo = """
            MemTotal:       1000000 kB
            MemFree:         100000 kB
            MemAvailable:    250000 kB
        """.trimIndent()

        assertEquals(75.0, calculateMemoryUsagePercent(memInfo)!!, 0.01)
    }

    @Test
    fun returnsNullWhenRequiredValuesAreMissingOrInvalid() {
        assertNull(calculateMemoryUsagePercent("MemTotal: 0 kB\nMemAvailable: 0 kB"))
        assertNull(calculateMemoryUsagePercent("MemTotal: 1000 kB"))
    }
}
