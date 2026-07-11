package io.pocketssh.server.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.pocketssh.server.service.SshServerService
import io.pocketssh.server.service.SshServiceContract

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (BootActions.isSupported(intent.action)) {
            ContextCompat.startForegroundService(context, Intent(context, SshServerService::class.java).setAction(SshServiceContract.ACTION_START))
        }
    }
}
