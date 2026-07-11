package io.pocketssh.server.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.StringReader

class SshConfigRepositoryTest {
    private val defaults = SshConfig("0.0.0.0", 2222, "android", "secret", true)

    @Test
    fun `properties override individual defaults`() {
        val config = SshConfigRepository(defaults).load(
            StringReader("port=2022\nusername=operator\nenabled=false")
        )

        assertEquals("0.0.0.0", config.bindAddress)
        assertEquals(2022, config.port)
        assertEquals("operator", config.username)
        assertEquals("secret", config.password)
        assertFalse(config.enabled)
    }

    @Test
    fun `invalid values are rejected`() {
        listOf("bindAddress= ", "port=0", "port=65536", "username= ", "password=").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                SshConfigRepository(defaults).load(StringReader(value))
            }
        }
    }

    @Test
    fun `stored values round trip and missing values use defaults`() {
        val repository = SshConfigRepository(defaults)
        val saved = repository.toValues(SshConfig("0.0.0.0", 2200, "ops", "new-secret", true))

        assertEquals(2200, repository.fromValues(saved).port)
        assertEquals("ops", repository.fromValues(saved).username)
        assertEquals("secret", repository.fromValues(emptyMap<String, Any>()).password)
    }
}
