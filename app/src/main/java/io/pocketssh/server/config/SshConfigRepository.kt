package io.pocketssh.server.config

import java.io.File
import java.io.Reader
import java.util.Properties

class SshConfigRepository(private val defaults: SshConfig) {
    fun load(file: File): SshConfig = if (file.isFile) file.reader().use(::load) else defaults

    fun load(reader: Reader): SshConfig {
        val properties = Properties().apply { load(reader) }
        return SshConfig(
            bindAddress = properties.getProperty("bindAddress", defaults.bindAddress),
            port = properties.getProperty("port")?.toIntOrNull()
                ?: if (properties.containsKey("port")) throw IllegalArgumentException("port must be an integer") else defaults.port,
            username = properties.getProperty("username", defaults.username),
            password = properties.getProperty("password", defaults.password),
            enabled = properties.getProperty("enabled")?.let {
                when (it.lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> throw IllegalArgumentException("enabled must be true or false")
                }
            } ?: defaults.enabled,
        )
    }

    fun toValues(config: SshConfig): Map<String, Any> = mapOf(
        "bindAddress" to config.bindAddress,
        "port" to config.port,
        "username" to config.username,
        "password" to config.password,
        "enabled" to config.enabled,
    )

    fun fromValues(values: Map<String, *>): SshConfig = SshConfig(
        bindAddress = values["bindAddress"] as? String ?: defaults.bindAddress,
        port = when (val value = values["port"]) {
            is Int -> value
            is String -> value.toIntOrNull() ?: throw IllegalArgumentException("port must be an integer")
            else -> defaults.port
        },
        username = values["username"] as? String ?: defaults.username,
        password = values["password"] as? String ?: defaults.password,
        enabled = values["enabled"] as? Boolean ?: defaults.enabled,
    )
}
