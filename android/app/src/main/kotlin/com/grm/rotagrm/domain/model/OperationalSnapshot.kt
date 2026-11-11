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
    val serverTimeEpochMillis: Long? = null,
    val receivedAtMonotonicNanos: Long = System.nanoTime(),
) {
    fun serverNowMillis(localNow: Long, monotonicNowNanos: Long = System.nanoTime()): Long = serverTimeEpochMillis?.let {
        it + ((monotonicNowNanos - receivedAtMonotonicNanos) / 1_000_000L).coerceAtLeast(0L)
    } ?: localNow

    fun isFresh(localNow: Long, monotonicNowNanos: Long = System.nanoTime()): Boolean {
        val updated = runCatching { java.time.Instant.parse(updatedAt).toEpochMilli() }.getOrNull() ?: return false
        val age = serverNowMillis(localNow, monotonicNowNanos) - updated
        return !stale && sourceAvailable && persistenceAvailable && age >= -30_000L &&
            age <= maxOf(120_000L, refreshIntervalSeconds * 2000L)
    }
}

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
    val durationMeasuredAtEpochMillis: Long? = null,
) {
    fun elapsedSeconds(snapshot: OperationalSnapshot?, now: Long, monotonicNowNanos: Long = System.nanoTime()): Long {
        if (durationFromReport) {
            val updated = snapshot?.updatedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            val serverNow = snapshot?.serverNowMillis(now, monotonicNowNanos) ?: now
            val extra = if (snapshot == null || !snapshot.isFresh(now, monotonicNowNanos) || updated == null) 0L
                else ((serverNow - (durationMeasuredAtEpochMillis ?: updated)) / 1000L).coerceAtLeast(0L)
            return durationSeconds.coerceAtLeast(0L) + extra
        }
        val serverNow = snapshot?.serverNowMillis(now, monotonicNowNanos) ?: now
        return startEpochMillis?.let { ((serverNow - it) / 1000L).coerceAtLeast(0L) } ?: durationSeconds.coerceAtLeast(0L)
    }
}
