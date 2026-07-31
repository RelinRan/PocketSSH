package io.rockchip.sshsftp.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.rockchip.sshsftp.BuildConfig
import io.rockchip.sshsftp.R
import io.rockchip.sshsftp.config.SshConfig
import io.rockchip.sshsftp.app.config.SshConfigRepository
import io.rockchip.sshsftp.ssh.AndroidCommandResolvers
import io.rockchip.sshsftp.ssh.SshServerManager
import java.io.File

internal fun notificationSmallIcon(): Int = R.drawable.ic_notification_ssh

class SshServerService : Service() {
    private var manager: SshServerManager? = null

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.ssh_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(notificationSmallIcon())
            .setContentTitle(getString(R.string.ssh_notification_title))
            .setContentText(getString(R.string.ssh_notification_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (SshServiceContract.commandFor(intent?.action) == SshCommand.STOP) {
            manager?.close()
            manager = null
            publishState(SshState.STOPPED)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (manager?.isRunning() == true) return START_STICKY
        try {
            publishState(SshState.STARTING)
            val storage = createDeviceProtectedStorageContext()
            val defaults = SshConfig(BuildConfig.SSH_BIND_ADDRESS, BuildConfig.SSH_PORT, BuildConfig.SSH_USERNAME, BuildConfig.SSH_PASSWORD, BuildConfig.SSH_ENABLED)
            val preferences = storage.getSharedPreferences(SshServiceContract.PREFERENCES, MODE_PRIVATE)
            val config = SshConfigRepository(defaults).fromValues(preferences.all)
            if (!config.enabled) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            val rootAccess = SshServerManager.detectRootAccess()
            Log.i(TAG, "SSH root access available=$rootAccess")
            manager = SshServerManager(
                keyDirectory = File(storage.filesDir, "ssh").toPath(),
                commandResolvers = AndroidCommandResolvers(this),
                rootAccess = rootAccess,
            ).also { it.start(config) }
            Log.i(TAG, "SSH server listening on ${config.bindAddress}:${config.port}")
            publishState(SshState.RUNNING)
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to start SSH server", error)
            manager?.close()
            manager = null
            publishState(SshState.ERROR, error.message ?: error.javaClass.simpleName)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        manager?.close()
        manager = null
        publishState(SshState.STOPPED)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun publishState(state: SshState, error: String = "") {
        createDeviceProtectedStorageContext().getSharedPreferences(SshServiceContract.STATE_PREFERENCES, MODE_PRIVATE)
            .edit().putString(SshServiceContract.EXTRA_STATE, state.name)
            .putString(SshServiceContract.EXTRA_ERROR, error).apply()
        sendBroadcast(Intent(SshServiceContract.ACTION_STATE).setPackage(packageName)
            .putExtra(SshServiceContract.EXTRA_STATE, state.name)
            .putExtra(SshServiceContract.EXTRA_ERROR, error))
    }

    companion object {
        private const val TAG = "rockchip-ssh-sftp"
        private const val CHANNEL_ID = "ssh_server"
        private const val NOTIFICATION_ID = 2222
    }
}
