package io.pocketssh.server.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidCommandResolversTest {
    @Test
    fun `activity component spec resolves common input forms`() {
        assertEquals("com.example/.MainActivity", AndroidCommandResolvers.activityComponentSpec("com.example", ".MainActivity"))
        assertEquals("com.example/.MainActivity", AndroidCommandResolvers.activityComponentSpec("com.example", "/.MainActivity"))
        assertEquals("com.example/com.example.MainActivity", AndroidCommandResolvers.activityComponentSpec("com.example", "MainActivity"))
        assertEquals("com.other/.Entry", AndroidCommandResolvers.activityComponentSpec("com.example", "com.other/.Entry"))
        assertNull(AndroidCommandResolvers.activityComponentSpec("com.example", ""))
    }
}
