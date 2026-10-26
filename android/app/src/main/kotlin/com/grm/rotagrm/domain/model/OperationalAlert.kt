package com.grm.rotagrm.domain.model

import com.grm.rotagrm.settings.DEFAULT_PAUSE_STATUS
import com.grm.rotagrm.settings.MonitoringSettings

data class OperationalAlert(
    val id: String,
    val equipmentTag: String,
    val status: String,
    val elapsedSeconds: Long,
    val limitSeconds: Long,
    val exceededSeconds: Long,
    val maintenanceEvent: Boolean = false,
)

object AlertEvaluator {
    fun activeAlerts(snapshot: OperationalSnapshot, settings: MonitoringSettings, nowEpochMillis: Long): List<OperationalAlert> {
        val records = snapshot.equipment.mapNotNull { it.current }
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
        return records.mapNotNull { record ->
            val matchedStatus = settings.limitsMinutesByStatus.keys.firstOrNull { it == record.reasonName }
                ?: DEFAULT_PAUSE_STATUS.takeIf {
                    isErgonomicPause(record.reasonName) && settings.limitsMinutesByStatus.containsKey(DEFAULT_PAUSE_STATUS)
                }
                ?: return@mapNotNull null
            val alertsEnabled = settings.alertsEnabledByStatus[record.reasonName]
                ?: settings.alertsEnabledByStatus[matchedStatus]
                ?: false
            val limitMinutes = settings.limitsMinutesByStatus[matchedStatus] ?: return@mapNotNull null
            if (!alertsEnabled || limitMinutes <= 0) return@mapNotNull null

            val elapsed = record.startEpochMillis?.let { start ->
                ((nowEpochMillis - start) / 1000L).coerceAtLeast(0L)
            } ?: record.durationSeconds.coerceAtLeast(0L)
            val limitSeconds = limitMinutes * 60L
            if (elapsed <= limitSeconds) return@mapNotNull null
            OperationalAlert(
                id = record.id,
                equipmentTag = record.equipmentTag,
                status = record.reasonName,
                elapsedSeconds = elapsed,
                limitSeconds = limitSeconds,
                exceededSeconds = elapsed - limitSeconds,
            )
        }
    }

    private fun isErgonomicPause(status: String): Boolean {
        val upper = status.uppercase()
        return "PAUSA" in upper || "ERGONOM" in upper
    }
}

/** Emits only records first observed after the initial snapshot, and drops closed records. */
class NewMaintenanceTracker {
    private var observedIds: Set<String>? = null
    private var activeRecords: Map<String, OperationalRecord> = emptyMap()

    val activeEvents: List<OperationalRecord>
        get() = activeRecords.values.toList()

    fun update(records: List<OperationalRecord>): List<OperationalRecord> {
        val current = records.filter { it.id.isNotBlank() }.associateBy { it.id }
        val previousIds = observedIds
        activeRecords = activeRecords.filterKeys { it in current.keys }
        val newlyObserved = if (previousIds == null) emptyList() else current
            .filterKeys { it !in previousIds }
            .values
            .toList()
        newlyObserved.forEach { activeRecords = activeRecords + (it.id to it) }
        observedIds = current.keys
        return newlyObserved
    }

    fun reset() {
        observedIds = null
        activeRecords = emptyMap()
    }
}
