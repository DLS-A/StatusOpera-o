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
)
