package io.rockchip.sshsftp.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class AcceleratorInfoParserTest {

    @Test
    fun prioritizesRknpuDebugAliasForLiveMetrics() {
        assertEquals(
            listOf("/d/rknpu/load", "/sys/kernel/debug/rknpu/load"),
            RKNN_LOAD_PATHS,
        )
        assertEquals(
            listOf("/d/rknpu/freq", "/sys/kernel/debug/rknpu/freq"),
            RKNN_FREQUENCY_PATHS,
        )
    }

    @Test
    fun parsesKeyValueRowsAndKeepsEqualsInsideValues() {
        val rows = parseAcceleratorKeyValueRows(
            """
                path=/sys/class/devfreq/fb000000.gpu
                governor=userspace
                load=37@800000000Hz detail=a=b
                ignored line
            """.trimIndent()
        )

        assertEquals(
            listOf(
                listOf("path", "/sys/class/devfreq/fb000000.gpu"),
                listOf("governor", "userspace"),
                listOf("load", "37@800000000Hz detail=a=b"),
            ),
            rows,
        )
    }

    @Test
    fun formatsSingleAndMultipleFrequenciesAsMegahertz() {
        assertEquals("800 MHz (800000000 Hz)", formatAcceleratorFrequency("800000000"))
        assertEquals(
            "200 MHz, 400 MHz, 1,000 MHz",
            formatAcceleratorFrequencyList("200000000 400000000 1000000000"),
        )
        assertEquals("unknown", formatAcceleratorFrequency("unknown"))
    }

    @Test
    fun parsesGpuUsageAcrossCommonKernelFormats() {
        assertEquals(37, parseAcceleratorUsagePercent("37%"))
        assertEquals(37, parseAcceleratorUsagePercent("37@800000000Hz"))
        assertEquals(37, parseAcceleratorUsagePercent("370"))
    }

    @Test
    fun averagesPerCoreNpuUsage() {
        assertEquals(
            20,
            parseAcceleratorUsagePercent("NPU load: Core0: 10%, Core1: 20%, Core2: 30%"),
        )
    }

    @Test
    fun appendsNormalizedUsageFieldFromAvailableLoadSource() {
        val rows = withAcceleratorUsage(
            listOf(
                listOf("cur_freq", "800000000"),
                listOf("utilisation", "42"),
            )
        )

        assertEquals(listOf("usage_percent", "42%"), rows.last())
    }

    @Test
    fun prefersRknpuCoreLoadOverDevfreqLoad() {
        val rows = withAcceleratorUsage(
            listOf(
                listOf("load", "100@1000000000Hz"),
                listOf("core_load", "Core0: 0%, Core1: 10%, Core2: 20%"),
            )
        )

        assertEquals(listOf("usage_percent", "10%"), rows.last())
    }
}
