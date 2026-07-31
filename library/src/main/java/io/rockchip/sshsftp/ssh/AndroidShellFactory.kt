package io.rockchip.sshsftp.ssh

import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.shell.ShellFactory

class AndroidShellFactory(private val shellPath: String = "/system/bin/sh") : ShellFactory {
    override fun createShell(channel: ChannelSession): Command = ProcessCommand(listOf(shellPath, "-i"))
}
