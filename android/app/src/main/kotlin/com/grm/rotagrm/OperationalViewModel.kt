package com.grm.rotagrm

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grm.rotagrm.auth.OidcAuthManager
import com.grm.rotagrm.data.HttpOperationalRepository
import com.grm.rotagrm.data.EncryptedOperationalStore
import com.grm.rotagrm.data.SessionAccessException
import com.grm.rotagrm.data.SessionIdentity
import com.grm.rotagrm.domain.model.AlertEvaluator
import com.grm.rotagrm.domain.model.OperationalAlert
import com.grm.rotagrm.domain.model.NewMaintenanceTracker
import com.grm.rotagrm.domain.model.OperationalSnapshot
import com.grm.rotagrm.notifications.OperationalNotifications
import com.grm.rotagrm.notifications.PushDeliveryStore
import com.grm.rotagrm.notifications.PushRegistrationWorker
import com.google.firebase.messaging.FirebaseMessaging
import com.grm.rotagrm.settings.MonitoringSettings
import com.grm.rotagrm.settings.MonitoringSettingsRepository
import com.grm.rotagrm.settings.alertStorageId
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import java.time.Instant

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
    private val auth = OidcAuthManager.getInstance(application)
    private val repository = HttpOperationalRepository(auth)
    private val cache = EncryptedOperationalStore(application)
    private val pushDelivery = PushDeliveryStore(application)
    private val refreshMutex = Mutex()
    private var generation = 0L
    private var identity: SessionIdentity? = null
    private var refreshJob: Job? = null
    private var lastPushPreferences: MonitoringSettings? = null
    private val settingsRepository = MonitoringSettingsRepository(application)
    private val lastNotificationAt = mutableMapOf<String, Long>()
    private val acknowledgedAlerts = mutableSetOf<String>()
    private val maintenanceTracker = NewMaintenanceTracker()
    private val _state = MutableStateFlow(
        OperationalUiState(configured = auth.isConfigured),
    )
    val state: StateFlow<OperationalUiState> = _state.asStateFlow()

    init {
        OperationalNotifications.createChannels(application)
        viewModelScope.launch {
            auth.sessionAvailable.collect { available ->
                if (!available && identity != null) clearLocalSession("Sessão encerrada. Entre novamente.")
            }
        }
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
                val deliverySettings = settings.copy(acknowledgedAlertIds = emptySet(), lastAlertNotificationAt = emptyMap(),
                    refreshIntervalSeconds = 60, repeatAlertMinutes = 5)
                if (identity != null && deliverySettings != lastPushPreferences) {
                    lastPushPreferences = deliverySettings
                    PushRegistrationWorker.enqueue(application)
                }
            }
        }
        if (auth.hasStoredSession) viewModelScope.launch { establishSession() }
    }

    suspend fun authorizationIntent(): Intent = try {
        _state.value = _state.value.copy(loading = true)
        auth.authorizationIntent()
    } catch (error: CancellationException) {
        _state.value = _state.value.copy(loading = false)
        throw error
    } catch (_: Exception) {
        _state.value = _state.value.copy(loading = false, message = "Não foi possível iniciar o login. Consulte o administrador.")
        throw IllegalStateException("OIDC authorization unavailable.")
    }

    suspend fun completeAuthorization(resultIntent: Intent) {
        val version = generation
        try {
            auth.completeAuthorization(resultIntent)
            if (generation != version) return
            establishSession()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            clearLocalSession("Login não concluído. Consulte o administrador se o erro persistir.")
        }
    }

    private suspend fun establishSession() {
        val version = generation
        _state.value = _state.value.copy(loading = true)
        try {
            val verified = try { repository.session()
            } catch (error: SessionAccessException) { throw error
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                cache.identity()?.takeIf { auth.canReadOffline(it) }
                    ?: throw IllegalStateException("Não foi possível validar a sessão.")
            }
            if (generation != version) return
            identity = verified
            cache.setIdentity(verified)
            pushDelivery.deviceId(verified)
            val stored = cache.snapshot(verified.userId)?.let {
                runCatching { HttpOperationalRepository.decodeSnapshot(it).copy(stale = true, sourceAvailable = false) }.getOrNull()
            }
            _state.value = _state.value.copy(signedIn = true, snapshot = stored, loading = false,
                message = if (stored != null) "Exibindo último snapshot salvo; verificando atualização." else "Sessão validada.")
            PushRegistrationWorker.enqueue(getApplication())
            refresh()
        } catch (error: CancellationException) { throw error
        } catch (error: SessionAccessException) {
            if (generation == version) clearLocalSession(if (error.denied) "Usuário sem autorização." else "Sessão encerrada. Entre novamente.")
        } catch (_: Exception) {
            if (generation == version) clearLocalSession("Não foi possível validar a sessão. Entre novamente quando a conexão retornar.")
        } finally {
            if (generation == version) _state.value = _state.value.copy(loading = false)
        }
    }

    fun loginCancelled() {
        _state.value = _state.value.copy(loading = false, message = "Login cancelado ou não concluído.")
    }

    suspend fun refresh() {
        val old = _state.value
        val currentIdentity = identity ?: return
        if (!old.signedIn || old.loading || !refreshMutex.tryLock()) return
        val version = generation
        _state.value = old.copy(loading = true)
        try {
            // Cancel only this request on logout, never the Activity's polling
            // parent. Awaiting callers still cancel the request in finally.
            val request = viewModelScope.async { repository.fetchSnapshot() }
            refreshJob = request
            val result = request.await()
            if (_state.value.signedIn && generation == version && identity == currentIdentity) {
                val saved = runCatching { cache.saveSnapshot(currentIdentity, result.cacheJson) }.isSuccess
                if (!result.stale) maintenanceTracker.update(result.maintenanceAlerts)
                _state.value = _state.value.copy(snapshot = result, apiAvailable = true,
                    message = if (!saved) "Dados recebidos; não foi possível salvar o cache local."
                        else if (result.stale) "Servidor conectado; dados GRM desatualizados." else "Dados atualizados.")
                evaluateAlerts(result, _state.value.settings)
            }
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (generation == version) throw error
        } catch (error: SessionAccessException) {
            if (generation == version) clearLocalSession(if (error.denied) "Usuário sem autorização." else "Sessão expirada. Entre novamente.")
        } catch (_: Exception) {
            if (generation == version) {
                if (!auth.canReadOffline(currentIdentity)) clearLocalSession("Sessão não validada. Entre novamente quando a conexão retornar.")
                else _state.value = _state.value.copy(apiAvailable = false,
                    snapshot = _state.value.snapshot?.copy(stale = true, sourceAvailable = false),
                    message = "Conexão indisponível; exibindo último snapshot válido, sem novos alertas.")
            }
        } finally {
            refreshJob?.cancel()
            refreshJob = null
            refreshMutex.unlock()
            if (generation == version) _state.value = _state.value.copy(loading = false)
        }
    }

    fun signOut() {
        if (!_state.value.signedIn) return
        generation++
        refreshJob?.cancel()
        PushRegistrationWorker.cancel(getApplication())
        cache.clear()
        identity = null
        OperationalNotifications.cancelAll(getApplication())
        _state.value = _state.value.copy(signedIn = false, snapshot = null, alerts = emptyList(), loading = true, apiAvailable = false)
        viewModelScope.launch {
            val remote = try { repository.logout() } catch (_: Exception) { false }
            clearLocalSession(if (remote) "Sessão encerrada no aparelho e no servidor."
                else "Sessão local encerrada. Encerramento remoto não confirmado; avise o administrador.")
            if (BuildConfig.FCM_ENABLED) runCatching { FirebaseMessaging.getInstance().deleteToken() }
        }
    }

    private suspend fun clearLocalSession(message: String) {
        generation++
        identity = null
        cache.clear()
        PushRegistrationWorker.cancel(getApplication())
        pushDelivery.clear()
        lastNotificationAt.clear(); acknowledgedAlerts.clear(); maintenanceTracker.reset()
        OperationalNotifications.cancelAll(getApplication())
        _state.value = _state.value.copy(signedIn = false, snapshot = null, loading = false, apiAvailable = false,
            alerts = emptyList(), acknowledgedAlertIds = emptySet(), message = message)
        auth.signOut()
        settingsRepository.clearAlertTracking()
    }

    fun acknowledgeAlert(id: String) {
        acknowledgedAlerts += alertStorageId(id)
        pushDelivery.acknowledge(alertStorageId(id))
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

    fun updateAlertVisual(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setAlertVisualEnabled(enabled)
    }

    fun restoreSettings() = viewModelScope.launch {
        settingsRepository.restoreDefaults()
    }

    private fun evaluateAlerts(snapshot: OperationalSnapshot, settings: MonitoringSettings) {
        val updated = runCatching { Instant.parse(snapshot.updatedAt).toEpochMilli() }.getOrDefault(0L)
        if (snapshot.stale || !_state.value.apiAvailable || System.currentTimeMillis() - updated > maxOf(120_000L, snapshot.refreshIntervalSeconds * 2000L)) return
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
            val previous = lastNotificationAt[storageId] ?: pushDelivery.lastAt(storageId)
            val canRepeat = previous == null ||
                (!alert.maintenanceEvent && now - previous >= settings.repeatAlertMinutes * 60_000L)
            if (canRepeat && settings.alertVisualEnabled && OperationalNotifications.post(getApplication(), alert, settings.alertSoundEnabled,
                    if (alert.maintenanceEvent) null else settings.repeatAlertMinutes * 60_000L)) {
                lastNotificationAt[storageId] = now
                viewModelScope.launch { settingsRepository.recordAlertNotification(alert.id, now) }
            }
        }
        _state.value = _state.value.copy(
            alerts = alerts,
            acknowledgedAlertIds = alerts.filter { alertStorageId(it.id) in acknowledgedAlerts }.mapTo(mutableSetOf()) { it.id },
        )
    }

}
