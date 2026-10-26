package com.grm.rotagrm

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.grm.rotagrm.notifications.OperationalNotifications
import com.grm.rotagrm.settings.MonitoringSettingsRepository
import com.grm.rotagrm.ui.OperationalScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: OperationalViewModel by viewModels()
    private lateinit var settings: MonitoringSettingsRepository
    private var notificationsAllowed by mutableStateOf(false)

    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        lifecycleScope.launch {
            val intent = result.data
            if (intent == null) viewModel.loginCancelled() else viewModel.completeAuthorization(intent)
        }
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = granted || Build.VERSION.SDK_INT < 33
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = MonitoringSettingsRepository(this)
        notificationsAllowed = OperationalNotifications.hasPermission(this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    OperationalScreen(
                        state = state,
                        notificationsAllowed = notificationsAllowed,
                        onLogin = ::startLogin,
                        onRefresh = { lifecycleScope.launch { viewModel.refresh() } },
                        onSignOut = viewModel::signOut,
                        onRequestNotifications = ::requestNotifications,
                        onAcknowledge = viewModel::acknowledgeAlert,
                        onRefreshInterval = { viewModel.updateRefreshInterval(it) },
                        onStatusLimit = { status, minutes -> viewModel.updateStatusLimit(status, minutes) },
                        onStatusAlerts = { status, enabled -> viewModel.updateStatusAlerts(status, enabled) },
                        onRepeatMinutes = { viewModel.updateRepeatMinutes(it) },
                        onSound = { viewModel.updateAlertSound(it) },
                        onRestoreSettings = viewModel::restoreSettings,
                    )
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    viewModel.refresh()
                    delay(settings.settings.first().refreshIntervalSeconds * 1000L)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationsAllowed = OperationalNotifications.hasPermission(this)
    }

    private fun startLogin() {
        lifecycleScope.launch {
            runCatching { viewModel.authorizationIntent() }
                .onSuccess(loginLauncher::launch)
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationsAllowed = true
        }
    }
}
