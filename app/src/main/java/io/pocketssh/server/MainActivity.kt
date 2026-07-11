package io.pocketssh.server

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.pocketssh.server.config.SshConfig
import io.pocketssh.server.config.SshConfigRepository
import io.pocketssh.server.network.LanAddressSelector
import io.pocketssh.server.service.SshServerService
import io.pocketssh.server.service.SshServiceContract
import io.pocketssh.server.service.SshState

class MainActivity : AppCompatActivity() {
    private lateinit var username: TextInputEditText
    private lateinit var password: TextInputEditText
    private lateinit var port: TextInputEditText
    private lateinit var usernameLayout: TextInputLayout
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var portLayout: TextInputLayout
    private lateinit var status: TextView
    private lateinit var endpoint: TextView
    private lateinit var error: TextView
    private lateinit var power: MaterialButton
    private val defaults by lazy { SshConfig(BuildConfig.SSH_BIND_ADDRESS, BuildConfig.SSH_PORT, BuildConfig.SSH_USERNAME, BuildConfig.SSH_PASSWORD, true) }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = renderState(
            intent?.getStringExtra(SshServiceContract.EXTRA_STATE),
            intent?.getStringExtra(SshServiceContract.EXTRA_ERROR).orEmpty(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        username = findViewById(R.id.usernameInput)
        password = findViewById(R.id.passwordInput)
        port = findViewById(R.id.portInput)
        usernameLayout = findViewById(R.id.usernameLayout)
        passwordLayout = findViewById(R.id.passwordLayout)
        portLayout = findViewById(R.id.portLayout)
        status = findViewById(R.id.statusText)
        endpoint = findViewById(R.id.endpointText)
        error = findViewById(R.id.errorText)
        power = findViewById(R.id.powerButton)
        loadConfig()
        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener { saveAndApply() }
        power.setOnClickListener { toggleService() }
        requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, stateReceiver, IntentFilter(SshServiceContract.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshState()
        if (shouldRecoverService(currentState())) startServiceCommand()
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        super.onStop()
    }

    private fun storage() = createDeviceProtectedStorageContext()
    private fun repository() = SshConfigRepository(defaults)
    private fun preferences() = storage().getSharedPreferences(SshServiceContract.PREFERENCES, MODE_PRIVATE)

    private fun loadConfig() {
        val config = repository().fromValues(preferences().all)
        username.setText(config.username)
        password.setText(config.password)
        port.setText(config.port.toString())
        endpoint.text = endpointText(config.port)
    }

    private fun saveAndApply() {
        usernameLayout.error = null; passwordLayout.error = null; portLayout.error = null
        val user = username.text?.toString().orEmpty().trim()
        val pass = password.text?.toString().orEmpty()
        val portValue = port.text?.toString()?.toIntOrNull()
        if (user.isBlank()) usernameLayout.error = getString(R.string.error_username_required)
        if (pass.isEmpty()) passwordLayout.error = getString(R.string.error_password_required)
        if (portValue !in 1..65535) portLayout.error = getString(R.string.error_port_range)
        if (usernameLayout.error != null || passwordLayout.error != null || portLayout.error != null) return
        val config = SshConfig(BuildConfig.SSH_BIND_ADDRESS, portValue!!, user, pass, true)
        val editor = preferences().edit().clear()
        repository().toValues(config).forEach { (key, value) ->
            when (value) { is String -> editor.putString(key, value); is Int -> editor.putInt(key, value); is Boolean -> editor.putBoolean(key, value) }
        }
        editor.apply()
        stopServiceCommand()
        startServiceCommand()
        endpoint.text = endpointText(config.port)
    }

    private fun toggleService() {
        val running = currentState() == SshState.RUNNING
        if (running) stopServiceCommand() else startServiceCommand()
    }

    private fun startServiceCommand() = ContextCompat.startForegroundService(this, Intent(this, SshServerService::class.java).setAction(SshServiceContract.ACTION_START))
    private fun stopServiceCommand() = startService(Intent(this, SshServerService::class.java).setAction(SshServiceContract.ACTION_STOP))

    private fun currentState(): SshState = runCatching {
        SshState.valueOf(storage().getSharedPreferences(SshServiceContract.STATE_PREFERENCES, MODE_PRIVATE)
            .getString(SshServiceContract.EXTRA_STATE, SshState.STOPPED.name)!!)
    }.getOrDefault(SshState.STOPPED)

    private fun refreshState() {
        val prefs = storage().getSharedPreferences(SshServiceContract.STATE_PREFERENCES, MODE_PRIVATE)
        renderState(prefs.getString(SshServiceContract.EXTRA_STATE, SshState.STOPPED.name), prefs.getString(SshServiceContract.EXTRA_ERROR, "").orEmpty())
    }

    private fun renderState(value: String?, message: String) {
        val state = runCatching { SshState.valueOf(value ?: "") }.getOrDefault(SshState.STOPPED)
        status.text = stateLabel(state)
        status.setTextColor(ContextCompat.getColor(this, when (state) { SshState.RUNNING -> R.color.ssh_accent; SshState.ERROR -> R.color.ssh_error; else -> R.color.ssh_offline }))
        power.text = getString(if (state == SshState.RUNNING || state == SshState.STARTING) R.string.button_stop_service else R.string.button_start_service)
        error.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        error.text = message
    }

    private fun endpointText(port: Int): String {
        val address = LanAddressSelector.current() ?: getString(R.string.endpoint_unavailable)
        return "$address:$port"
    }

    private fun stateLabel(state: SshState): String {
        return getString(when (state) {
            SshState.STARTING -> R.string.state_starting
            SshState.RUNNING -> R.string.state_online
            SshState.STOPPED -> R.string.state_offline
            SshState.ERROR -> R.string.state_error
        })
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    companion object {
        internal fun shouldRecoverService(state: SshState): Boolean = state == SshState.STARTING || state == SshState.RUNNING
    }
}
