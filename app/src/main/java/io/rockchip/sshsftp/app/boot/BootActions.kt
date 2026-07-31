package io.rockchip.sshsftp.app.boot

import android.content.Intent

object BootActions {
    fun isSupported(action: String?): Boolean =
        action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED
}
