package io.rockchip.sshsftp.config

data class SshConfig(
    val bindAddress: String,
    val port: Int,
    val username: String,
    val password: String,
    val enabled: Boolean,
) {
    init {
        require(bindAddress.isNotBlank()) { "bindAddress must not be blank" }
        require(port in 1..65535) { "port must be in 1..65535" }
        require(username.isNotBlank()) { "username must not be blank" }
        require(password.isNotEmpty()) { "password must not be empty" }
    }
}
