package io.pocketssh.server.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanAddressSelectorTest {
    @Test fun `selects first usable ipv4 address`() {
        assertEquals("192.168.1.8", LanAddressSelector.select(listOf("127.0.0.1", "fe80::1", "192.168.1.8")))
    }

    @Test fun `returns null without usable ipv4 address`() {
        assertNull(LanAddressSelector.select(listOf("127.0.0.1", "::1", "fe80::1")))
    }
}
