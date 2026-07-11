package io.pocketssh.server.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopProcessMetricsParserTest {

    @Test
    fun parsesToyboxTopMetricsUsingDynamicHeader() {
        val text = """
            Tasks: 3 total, 1 running, 2 sleeping
              PID USER         PR  NI VIRT  RES  SHR S[%CPU] %MEM     TIME+ ARGS
             8458 u0_a108      10  10 16.1G 647M 261M R 44.0  4.1   2:34.00 com.anbao.handhygiene.multi
             1141 u0_a81       20   0 2.5G 120M  44M S  1.5  0.8   0:05.20 android.ext.services
        """.trimIndent()

        val rows = parseTopProcessMetrics(text)

        assertEquals("10", rows.getValue("8458").priority)
        assertEquals("10", rows.getValue("8458").nice)
        assertEquals(16_882_074L, rows.getValue("8458").virtKb)
        assertEquals(662_528L, rows.getValue("8458").rssKb)
        assertEquals(267_264L, rows.getValue("8458").sharedKb)
        assertEquals("R", rows.getValue("8458").state)
        assertEquals("44.0", rows.getValue("8458").cpuPercent)
        assertEquals("4.1", rows.getValue("8458").memPercent)
        assertEquals("2:34.00", rows.getValue("8458").timePlus)
    }

    @Test
    fun parsesAlternatePriorityAndPercentHeadersAndMemoryUnits() {
        val text = """
            PID USER PRI NI VIRT RES SHR S CPU% MEM% TIME COMMAND
            100 root  20  0 1024K 2M 1.5M S 7% 0.5% 00:03 system_server
            200 root  15 -5 1T 512 0 R 2.0 0.1 01:02 vendor.service
        """.trimIndent()

        val rows = parseTopProcessMetrics(text)

        assertEquals(1024L, rows.getValue("100").virtKb)
        assertEquals(2048L, rows.getValue("100").rssKb)
        assertEquals(1536L, rows.getValue("100").sharedKb)
        assertEquals("7", rows.getValue("100").cpuPercent)
        assertEquals("0.5", rows.getValue("100").memPercent)
        assertEquals(1_073_741_824L, rows.getValue("200").virtKb)
        assertEquals(512L, rows.getValue("200").rssKb)
    }

    @Test
    fun returnsEmptyMapWhenTopHeaderCannotBeRecognized() {
        assertTrue(parseTopProcessMetrics("top: permission denied").isEmpty())
        assertTrue(parseTopProcessMetrics("PID USER COMMAND\n1 root init").isEmpty())
    }
}
