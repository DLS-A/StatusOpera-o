package com.grm.rotagrm

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grm.rotagrm.auth.OidcAuthManager
import com.grm.rotagrm.data.HttpOperationalRepository
import com.grm.rotagrm.domain.model.AlertEvaluator
import com.grm.rotagrm.domain.model.OperationalAlert
import com.grm.rotagrm.domain.model.NewMaintenanceTracker
import com.grm.rotagrm.domain.model.OperationalSnapshot
import com.grm.rotagrm.notifications.OperationalNotifications
import com.grm.rotagrm.settings.MonitoringSettings
import com.grm.rotagrm.settings.MonitoringSettingsRepository
import com.grm.rotagrm.settings.alertStorageId
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class OperationalUiState(
    val configured: Boolean = false,
    val signedIn: Boolean = false,
    val loading: Boolean = false,
    val apiAvailable: Boolean = false,
    val message: String = "",
    val snapshot: OperationalSnapshot? = null,
    val alerts: List<OperationalAlert> = emptyList(),
    val acknowledgedAlertIds: Set<String> = emptySet(),
    val settings: MonitoringSettings = MonitoringSettings(),
)

class OperationalViewModel(application: Application) : AndroidViewModel(application) {
    private val auth = OidcAuthManager(application)
    private val repository = HttpOperationalRepository(auth)
    private val settingsRepository = MonitoringSettingsRepository(application)
    private val lastNotificationAt = mutableMapOf<String, Long>()
    private val acknowledgedAlerts = mutableSetOf<String>()
    private val maintenanceTracker = NewMaintenanceTracker()
    private val _state = MutableStateFlow(
        OperationalUiState(configured = auth.isConfigured, signedIn = auth.hasStoredSession),
    )
    val state: StateFlow<OperationalUiState> = _state.asStateFlow()

    init {
        OperationalNotifications.createChannels(application)
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                lastNotificationAt.clear()
                lastNotificationAt.putAll(settings.lastAlertNotificationAt)
                acknowledgedAlerts.clear()
                acknowledgedAlerts.addAll(settings.acknowledgedAlertIds)
                _state.value = _state.value.copy(
                    settings = settings,
                    acknowledgedAlertIds = _state.value.alerts
                        .filter { alertStorageId(it.id) in acknowledgedAlerts }
                        .mapTo(mutableSetOf()) { it.id },
                )
                _state.value.snapshot?.let { evaluateAlerts(it, settings) }
            }
        }
    }

    suspend fun authorizationIntent(): Intent = try {
        auth.authorizationIntent()
    } catch (_: Exception) {
        _state.value = _state.value.copy(message = "Não foi possível iniciar o login OIDC.")
        throw IllegalStateException("OIDC authorization unavailable.")
    }

    suspend fun completeAuthorization(resultIntent: Intent) {
        try {
            auth.completeAuthorization(resultIntent)
            _state.value = _state.value.copy(signedIn = true, message = "Sessão iniciada.")
            refresh()
        } catch (_: Exception) {
            _state.value = _state.value.copy(
                signedIn = false,
                message = "Login cancelado ou não concluído. Confira a configuração OIDC.",
            )
        }
    }

    fun loginCancelled() {
        _state.value = _state.value.copy(message = "Login cancelado ou nao concluido.")
    }

    suspend fun refresh() {
        val old = _state.value
        if (!old.signedIn || old.loading) return
        _state.value = old.copy(loading = true)
        try {
            val result = repository.fetchSnapshot()
            if (_state.value.signedIn) {
                maintenanceTracker.update(result.maintenanceAlerts)
                _state.value = _state.value.copy(snapshot = result, apiAvailable = true, message = "Dados atualizados.")
                evaluateAlerts(result, _state.value.settings)
            }
        } catch (_: Exception) {
            _state.value = _state.value.copy(
                apiAvailable = false,
                message = "Falha ao atualizar. Verifique conexão, sessão e disponibilidade do backend.",
            )
        } finally {
            _state.value = _state.value.copy(loading = false)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            auth.signOut()
            _state.value.alerts.forEach { OperationalNotifications.cancel(getApplication(), it.id) }
            lastNotificationAt.clear()
            acknowledgedAlerts.clear()
            maintenanceTracker.reset()
            settingsRepository.clearAlertTracking()
            _state.value = _state.value.copy(apiAvailable = false, alerts = emptyList(), acknowledgedAlertIds = emptySet())
            _state.value = _state.value.copy(signedIn = false, snapshot = null, message = "Sessão encerrada.")
        }
    }

    fun acknowledgeAlert(id: String) {
        acknowledgedAlerts += alertStorageId(id)
        OperationalNotifications.cancel(getApplication(), id)
        _state.value = _state.value.copy(acknowledgedAlertIds = _state.value.acknowledgedAlertIds + id)
        viewModelScope.launch { settingsRepository.setAlertAcknowledged(id, acknowledged = true) }
    }

    fun updateRefreshInterval(seconds: Int) = viewModelScope.launch {
        settingsRepository.setRefreshInterval(seconds)
    }

    fun updateStatusLimit(status: String, minutes: Int) = viewModelScope.launch {
        settingsRepository.setStatusLimit(status, minutes)
    }

    fun updateStatusAlerts(status: String, enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setAlertEnabled(status, enabled)
    }

    fun updateRepeatMinutes(minutes: Int) = viewModelScope.launch {
        settingsRepository.setRepeatAlertMinutes(minutes)
    }

    fun updateAlertSound(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setAlertSoundEnabled(enabled)
    }

    fun restoreSettings() = viewModelScope.launch {
        settingsRepository.restoreDefaults()
    }

    private fun evaluateAlerts(snapshot: OperationalSnapshot, settings: MonitoringSettings) {
        val now = System.currentTimeMillis()
        val thresholdAlerts = AlertEvaluator.activeAlerts(snapshot, settings, now)
        val maintenanceAlerts = maintenanceTracker.activeEvents
            .filter { settings.alertsEnabledByStatus[it.reasonName] ?: true }
            .map { record ->
                OperationalAlert(
                    id = "maintenance:${record.id}",
                    equipmentTag = record.equipmentTag,
                    status = record.reasonName,
                    elapsedSeconds = record.startEpochMillis?.let { ((now - it) / 1000L).coerceAtLeast(0L) } ?: 0L,
                    limitSeconds = 0,
                    exceededSeconds = 0,
                    maintenanceEvent = true,
                )
            }
        val alerts = thresholdAlerts + maintenanceAlerts
        val activeStorageIds = alerts.mapTo(mutableSetOf()) { alertStorageId(it.id) }
        val staleIds = (lastNotificationAt.keys + acknowledgedAlerts).filter { it !in activeStorageIds }.toSet()
        staleIds.forEach { storageId ->
            lastNotificationAt.remove(storageId)
            acknowledgedAlerts.remove(storageId)
            OperationalNotifications.cancelByStorageId(getApplication(), storageId)
        }
        if (staleIds.isNotEmpty()) viewModelScope.launch { settingsRepository.clearAlertTrackingExcept(alerts.mapTo(mutableSetOf()) { it.id }) }
        alerts.forEach { alert ->
            val storageId = alertStorageId(alert.id)
            if (storageId in acknowledgedAlerts) return@forEach
            val previous = lastNotificationAt[storageId]
            val canRepeat = previous == null ||
                (!alert.maintenanceEvent && now - previous >= settings.repeatAlertMinutes * 60_000L)
            if (canRepeat && OperationalNotifications.post(getApplication(), alert, settings.alertSoundEnabled)) {
                lastNotificationAt[storageId] = now
                viewModelScope.launch { settingsRepository.recordAlertNotification(alert.id, now) }
            }
        }
        _state.value = _state.value.copy(
            alerts = alerts,
            acknowledgedAlertIds = alerts.filter { alertStorageId(it.id) in acknowledgedAlerts }.mapTo(mutableSetOf()) { it.id },
        )
    }

    override fun onCleared() {
        auth.dispose()
        super.onCleared()
    }
}
