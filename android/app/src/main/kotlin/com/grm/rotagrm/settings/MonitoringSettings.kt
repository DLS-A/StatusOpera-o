package com.grm.rotagrm.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

private val Context.monitoringDataStore by preferencesDataStore(name = "monitoring_settings")

data class MonitoringSettings(
    val refreshIntervalSeconds: Int = DEFAULT_REFRESH_SECONDS,
    val limitsMinutesByStatus: Map<String, Int> = mapOf(DEFAULT_PAUSE_STATUS to DEFAULT_ERGONOMIC_PAUSE_LIMIT_MINUTES),
    val alertsEnabledByStatus: Map<String, Boolean> = mapOf(
        DEFAULT_PAUSE_STATUS to true,
        CORRECTIVE_MAINTENANCE_STATUS to true,
        PREVENTIVE_MAINTENANCE_STATUS to true,
    ),
    val repeatAlertMinutes: Int = DEFAULT_REPEAT_ALERT_MINUTES,
    val alertSoundEnabled: Boolean = true,
    val acknowledgedAlertIds: Set<String> = emptySet(),
    val lastAlertNotificationAt: Map<String, Long> = emptyMap(),
)

const val DEFAULT_REFRESH_SECONDS = 60
const val DEFAULT_ERGONOMIC_PAUSE_LIMIT_MINUTES = 15
const val DEFAULT_REPEAT_ALERT_MINUTES = 5
const val DEFAULT_PAUSE_STATUS = "PAUSA ERGONOMICA"
const val CORRECTIVE_MAINTENANCE_STATUS = "EXEC MANUT CORRETIVA"
const val PREVENTIVE_MAINTENANCE_STATUS = "EXEC MANUT PREVENTIVA"
private const val LIMIT_PREFIX = "limit_minutes:"
private const val ALERT_PREFIX = "alert_enabled:"

class MonitoringSettingsRepository(private val context: Context) {
    val settings: Flow<MonitoringSettings> = context.monitoringDataStore.data.map { preferences ->
        MonitoringSettings(
            refreshIntervalSeconds = preferences[REFRESH_INTERVAL] ?: DEFAULT_REFRESH_SECONDS,
            limitsMinutesByStatus = mapOf(DEFAULT_PAUSE_STATUS to DEFAULT_ERGONOMIC_PAUSE_LIMIT_MINUTES) +
                preferences.intValuesWithPrefix(LIMIT_PREFIX),
            alertsEnabledByStatus = mapOf(
                DEFAULT_PAUSE_STATUS to true,
                CORRECTIVE_MAINTENANCE_STATUS to true,
                PREVENTIVE_MAINTENANCE_STATUS to true,
            ) +
                preferences.booleanValuesWithPrefix(ALERT_PREFIX),
            repeatAlertMinutes = preferences[REPEAT_MINUTES] ?: DEFAULT_REPEAT_ALERT_MINUTES,
            alertSoundEnabled = preferences[SOUND_ENABLED] ?: true,
            acknowledgedAlertIds = preferences[ACKNOWLEDGED_ALERT_IDS] ?: emptySet(),
            lastAlertNotificationAt = preferences.longValuesWithPrefix(NOTIFIED_PREFIX),
        )
    }

    suspend fun setRefreshInterval(seconds: Int) {
        require(seconds in 30..3600) { "O intervalo permitido é de 30 a 3600 segundos." }
        context.monitoringDataStore.edit { it[REFRESH_INTERVAL] = seconds }
    }

    suspend fun setStatusLimit(status: String, minutes: Int?) {
        require(status.isNotBlank())
        require(minutes == null || minutes in 1..1440)
        val key = intPreferencesKey(LIMIT_PREFIX + status)
        context.monitoringDataStore.edit { preferences ->
            if (minutes == null) preferences.remove(key) else preferences[key] = minutes
        }
    }

    suspend fun setAlertEnabled(status: String, enabled: Boolean) {
        require(status.isNotBlank())
        context.monitoringDataStore.edit { it[booleanPreferencesKey(ALERT_PREFIX + status)] = enabled }
    }

    suspend fun setRepeatAlertMinutes(minutes: Int) {
        require(minutes in 1..1440)
        context.monitoringDataStore.edit { it[REPEAT_MINUTES] = minutes }
    }

    suspend fun setAlertSoundEnabled(enabled: Boolean) {
        context.monitoringDataStore.edit { it[SOUND_ENABLED] = enabled }
    }

    suspend fun setAlertAcknowledged(id: String, acknowledged: Boolean) {
        require(id.isNotBlank())
        val storageId = alertStorageId(id)
        context.monitoringDataStore.edit { preferences ->
            val ids = preferences[ACKNOWLEDGED_ALERT_IDS].orEmpty().toMutableSet()
            if (acknowledged) ids += storageId else ids -= storageId
            preferences[ACKNOWLEDGED_ALERT_IDS] = ids
        }
    }

    suspend fun recordAlertNotification(id: String, epochMillis: Long) {
        require(id.isNotBlank())
        context.monitoringDataStore.edit {
            it[longPreferencesKey(NOTIFIED_PREFIX + alertStorageId(id))] = epochMillis
        }
    }

    suspend fun clearAlertTrackingExcept(activeIds: Set<String>) {
        val activeStorageIds = activeIds.mapTo(mutableSetOf(), ::alertStorageId)
        context.monitoringDataStore.edit { preferences ->
            val acknowledged = preferences[ACKNOWLEDGED_ALERT_IDS].orEmpty()
            val stillAcknowledged = acknowledged.intersect(activeStorageIds)
            if (acknowledged != stillAcknowledged) preferences[ACKNOWLEDGED_ALERT_IDS] = stillAcknowledged
            preferences.asMap().keys
                .filter { it.name.startsWith(NOTIFIED_PREFIX) && it.name.removePrefix(NOTIFIED_PREFIX) !in activeStorageIds }
                .forEach { preferences.remove(it) }
        }
    }

    suspend fun clearAlertTracking() {
        context.monitoringDataStore.edit { preferences ->
            preferences[ACKNOWLEDGED_ALERT_IDS] = emptySet()
            preferences.asMap().keys
                .filter { it.name.startsWith(NOTIFIED_PREFIX) }
                .forEach { preferences.remove(it) }
        }
    }

    suspend fun restoreDefaults() {
        context.monitoringDataStore.edit { preferences ->
            preferences.clear()
            preferences[REFRESH_INTERVAL] = DEFAULT_REFRESH_SECONDS
            preferences[REPEAT_MINUTES] = DEFAULT_REPEAT_ALERT_MINUTES
            preferences[SOUND_ENABLED] = true
            preferences[ACKNOWLEDGED_ALERT_IDS] = emptySet()
            preferences[intPreferencesKey(LIMIT_PREFIX + DEFAULT_PAUSE_STATUS)] =
                DEFAULT_ERGONOMIC_PAUSE_LIMIT_MINUTES
            preferences[booleanPreferencesKey(ALERT_PREFIX + DEFAULT_PAUSE_STATUS)] = true
            preferences[booleanPreferencesKey(ALERT_PREFIX + CORRECTIVE_MAINTENANCE_STATUS)] = true
            preferences[booleanPreferencesKey(ALERT_PREFIX + PREVENTIVE_MAINTENANCE_STATUS)] = true
        }
    }

    private fun Preferences.intValuesWithPrefix(prefix: String): Map<String, Int> =
        asMap().mapNotNull { (key, value) ->
            if (key.name.startsWith(prefix) && value is Int) key.name.removePrefix(prefix) to value else null
        }.toMap()

    private fun Preferences.booleanValuesWithPrefix(prefix: String): Map<String, Boolean> =
        asMap().mapNotNull { (key, value) ->
            if (key.name.startsWith(prefix) && value is Boolean) key.name.removePrefix(prefix) to value else null
        }.toMap()

    private fun Preferences.longValuesWithPrefix(prefix: String): Map<String, Long> =
        asMap().mapNotNull { (key, value) ->
            if (key.name.startsWith(prefix) && value is Long) key.name.removePrefix(prefix) to value else null
        }.toMap()

    private companion object {
        val REFRESH_INTERVAL = intPreferencesKey("refresh_interval_seconds")
        val REPEAT_MINUTES = intPreferencesKey("repeat_alert_minutes")
        val SOUND_ENABLED = booleanPreferencesKey("alert_sound_enabled")
        val ACKNOWLEDGED_ALERT_IDS = stringSetPreferencesKey("acknowledged_alert_ids")
        const val NOTIFIED_PREFIX = "last_alert_notification_at:"
    }
}

internal fun alertStorageId(id: String): String = MessageDigest.getInstance("SHA-256")
    .digest(id.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
