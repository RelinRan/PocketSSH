package io.rockchip.sshsftp.app.service

enum class SshCommand { START, STOP }
enum class SshState(val label: String) { STARTING("STARTING"), RUNNING("ONLINE"), STOPPED("OFFLINE"), ERROR("ERROR") }

object SshServiceContract {
    const val ACTION_START = "io.rockchip.sshsftp.action.START"
    const val ACTION_STOP = "io.rockchip.sshsftp.action.STOP"
    const val ACTION_STATE = "io.rockchip.sshsftp.action.STATE"
    const val EXTRA_STATE = "state"
    const val EXTRA_ERROR = "error"
    const val PREFERENCES = "ssh_config"
    const val STATE_PREFERENCES = "ssh_state"

    fun commandFor(action: String?): SshCommand = if (action == ACTION_STOP) SshCommand.STOP else SshCommand.START
}
