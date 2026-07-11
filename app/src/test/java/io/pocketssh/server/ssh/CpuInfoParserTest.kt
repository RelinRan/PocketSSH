package io.pocketssh.server.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuInfoParserTest {
    @Test
    fun calculatesOverallAndPerCoreUsageFromProcStatSnapshots() {
        val before = """
            cpu  100 0 50 850 0 0 0 0
            cpu0 50 0 25 425 0 0 0 0
            cpu1 50 0 25 425 0 0 0 0
        """.trimIndent()
        val after = """
            cpu  120 0 70 910 0 0 0 0
            cpu0 60 0 35 455 0 0 0 0
            cpu1 60 0 35 455 0 0 0 0
        """.trimIndent()

        val usage = calculateCpuUsage(before, after)

        assertEquals(40.0, usage.getValue("cpu").usagePercent, 0.01)
        assertEquals(40.0, usage.getValue("cpu0").usagePercent, 0.01)
        assertEquals(20.0, usage.getValue("cpu").userPercent, 0.01)
        assertEquals(20.0, usage.getValue("cpu").systemPercent, 0.01)
        assertEquals(60.0, usage.getValue("cpu").idlePercent, 0.01)
    }

    @Test
    fun formatsThermalMillidegreesAsCelsius() {
        assertEquals("52.0 C", formatCpuTemperature("52000"))
        assertEquals("52.0 C", formatCpuTemperature("52"))
    }

    @Test
    fun parsesCpufreqPolicyCpuListsAndRanges() {
        assertEquals(setOf(0, 1, 2, 3), parseCpuIndexList("0 1 2 3"))
        assertEquals(setOf(0, 1, 2, 3, 6), parseCpuIndexList("0-3,6"))
    }
}
