package com.grm.rotagrm.domain.model

data class OperationalSnapshot(
    val updatedAt: String,
    val equipment: List<EquipmentStatus>,
    val pauses: List<OperationalRecord>,
    val maintenance: List<OperationalRecord>,
    val maintenanceAlerts: List<OperationalRecord> = emptyList(),
    val stale: Boolean = false,
    val sourceAvailable: Boolean = true,
    val persistenceAvailable: Boolean = true,
    val lastAttemptAt: String? = null,
    val refreshIntervalSeconds: Int = 60,
    val receivedAtMillis: Long = System.currentTimeMillis(),
    val cacheJson: String = "",
)

data class EquipmentStatus(
    val equipmentTag: String,
    val current: OperationalRecord?,
    val operationalState: String = "unknown",
)

data class OperationalRecord(
    val id: String,
    val equipmentTag: String,
    val reasonName: String,
    val startEpochMillis: Long?,
    val durationSeconds: Long,
    val operator: String,
    val location: String,
    val equipmentType: String,
    val kpiCategory: String,
    val durationFromReport: Boolean = false,
    val startConfirmed: Boolean = false,
) {
    fun elapsedSeconds(snapshot: OperationalSnapshot?, now: Long): Long {
        if (durationFromReport) {
            val updated = snapshot?.updatedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            val extra = if (snapshot == null || snapshot.stale || !snapshot.sourceAvailable || updated == null) 0L
                else ((now - updated) / 1000L).coerceAtLeast(0L)
            return durationSeconds.coerceAtLeast(0L) + extra
        }
        return startEpochMillis?.let { ((now - it) / 1000L).coerceAtLeast(0L) } ?: durationSeconds.coerceAtLeast(0L)
    }
}
