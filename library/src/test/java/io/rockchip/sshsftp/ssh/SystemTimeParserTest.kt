package io.rockchip.sshsftp.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class SystemTimeParserTest {
    @Test
    fun parsesStrictSystemTimeAndBuildsToyboxFallbackValue() {
        val parsed = parseSystemTime("2026-07-11 15:30:45", TimeZone.getTimeZone("Asia/Shanghai"))

        assertEquals("2026-07-11 15:30:45", parsed?.display)
        assertEquals("071115302026.45", parsed?.toyboxFallback)
    }

    @Test
    fun rejectsInvalidCalendarDatesAndFormats() {
        assertNull(parseSystemTime("2026-02-30 10:00:00", TimeZone.getTimeZone("Asia/Shanghai")))
        assertNull(parseSystemTime("2026/07/11 10:00:00", TimeZone.getTimeZone("Asia/Shanghai")))
    }
}
