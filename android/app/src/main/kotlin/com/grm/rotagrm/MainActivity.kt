package com.grm.rotagrm

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
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
import com.grm.rotagrm.notifications.OperationalMonitoringService
import com.grm.rotagrm.notifications.PushRegistrationWorker
import com.grm.rotagrm.settings.MonitoringSettingsRepository
import com.grm.rotagrm.domain.model.RefreshPolicy
import com.grm.rotagrm.ui.OperationalScreen
import com.grm.rotagrm.ui.StatusOperacionalTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: OperationalViewModel get() = (application as OperationalApplication).monitor
    private lateinit var settings: MonitoringSettingsRepository
    private var notificationsAllowed by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = granted || Build.VERSION.SDK_INT < 33
        PushRegistrationWorker.enqueue(this)
        if (notificationsAllowed) OperationalMonitoringService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = MonitoringSettingsRepository(this)
        notificationsAllowed = OperationalNotifications.hasPermission(this)
        setContent {
            StatusOperacionalTheme {
                Surface(Modifier.fillMaxSize()) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val monitoringStatus by OperationalMonitoringService.monitoringStatus.collectAsStateWithLifecycle()
                    val monitoringRunning by OperationalMonitoringService.isRunning.collectAsStateWithLifecycle()
                    OperationalScreen(
                        state = state,
                        notificationsAllowed = notificationsAllowed,
                        onRefresh = { lifecycleScope.launch { viewModel.refresh() } },
                        onRequestNotifications = ::requestNotifications,
                        monitoringStatus = monitoringStatus,
                        monitoringRunning = monitoringRunning,
                        onStartMonitoring = { OperationalMonitoringService.start(this, explicit = true) },
                        onPauseMonitoring = { OperationalMonitoringService.pause(this) },
                        onNotificationSettings = { OperationalNotifications.openSoundSettings(this) },
                        onTestSound = { OperationalNotifications.testSound(this) },
                        onRefreshInterval = { viewModel.updateRefreshInterval(it) },
                        onStatusLimit = { status, minutes -> viewModel.updateStatusLimit(status, minutes) },
                        onStatusAlerts = { status, enabled -> viewModel.updateStatusAlerts(status, enabled) },
                        onRepeatMinutes = { viewModel.updateRepeatMinutes(it) },
                        onSound = { viewModel.updateAlertSound(it) },
                        onVisual = { viewModel.updateAlertVisual(it) },
                        onRestoreSettings = viewModel::restoreSettings,
                    )
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // Reopening the screen must not wait for a sleeping service loop.
                var firstPass = true
                var failures = 0
                while (true) {
                    val started = SystemClock.elapsedRealtime()
                    if (firstPass || !OperationalMonitoringService.isRunning.value) viewModel.refresh()
                    firstPass = false
                    val state = viewModel.state.value
                    val fresh = state.apiAvailable && state.snapshot?.isFresh(System.currentTimeMillis()) == true
                    failures = if (fresh) 0 else failures + 1
                    delay(RefreshPolicy.delayMillis(settings.settings.first().refreshIntervalSeconds,
                        SystemClock.elapsedRealtime() - started, fresh, failures))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationsAllowed = OperationalNotifications.hasPermission(this)
        PushRegistrationWorker.enqueue(this)
        if (notificationsAllowed) OperationalMonitoringService.start(this)
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationsAllowed = true
        }
    }
}
