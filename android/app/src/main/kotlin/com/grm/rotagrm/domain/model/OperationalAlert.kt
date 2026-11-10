package com.grm.rotagrm.domain.model

import com.grm.rotagrm.settings.DEFAULT_PAUSE_STATUS
import com.grm.rotagrm.settings.MonitoringSettings
import com.grm.rotagrm.settings.configurableStatus
import com.grm.rotagrm.settings.canonicalSettings

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
        if (snapshot.stale || !snapshot.sourceAvailable || !snapshot.persistenceAvailable) return emptyList()
        val current = snapshot.equipment.mapNotNull { it.current }
        val byTag = current.associateBy { it.equipmentTag }
        val records = (current + snapshot.pauses.filter { byTag[it.equipmentTag]?.id?.let { id -> id == it.id } ?: true })
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
        val limits = canonicalSettings(settings.limitsMinutesByStatus)
        val enabled = canonicalSettings(settings.alertsEnabledByStatus)
        return records.mapNotNull { record ->
            val matchedStatus = configurableStatus(record.reasonName) ?: return@mapNotNull null
            val alertsEnabled = enabled[matchedStatus] ?: false
            val limitMinutes = limits[matchedStatus] ?: return@mapNotNull null
            if (!alertsEnabled || limitMinutes <= 0) return@mapNotNull null

            val elapsed = record.elapsedSeconds(snapshot, nowEpochMillis)
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
        return "PAUSA" in upper && "ERGONOM" in java.text.Normalizer.normalize(upper, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    }
}

/** A raised alert is cleared by a changed pointing, never by acknowledgment.
 * Offline/stale snapshots cannot establish closure. */
fun reconcileActiveAlerts(previous: List<OperationalAlert>, evaluated: List<OperationalAlert>, snapshot: OperationalSnapshot, now: Long): List<OperationalAlert> {
    if (snapshot.stale || !snapshot.sourceAvailable || !snapshot.persistenceAvailable) return previous
    val current = snapshot.equipment.mapNotNull { it.current }
    val byTag = current.associateBy { it.equipmentTag }
    val extras = (snapshot.pauses + snapshot.maintenance).filter { record -> byTag[record.equipmentTag]?.id?.let { it == record.id } ?: true }
    val records = (extras + current)
        .associateBy { it.id }
    val retained = previous.mapNotNull { alert ->
        val id = alert.id.removePrefix("maintenance:")
        val record = records[id] ?: return@mapNotNull null
        if (record.equipmentTag != alert.equipmentTag || record.reasonName != alert.status) return@mapNotNull null
        val elapsed = record.elapsedSeconds(snapshot, now)
        alert.copy(elapsedSeconds = elapsed, exceededSeconds = (elapsed - alert.limitSeconds).coerceAtLeast(0L))
    }
    return (evaluated + retained).distinctBy { it.id }
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
