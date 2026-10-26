package com.grm.rotagrm.domain.model

data class OperationalSnapshot(
    val updatedAt: String,
    val equipment: List<EquipmentStatus>,
    val pauses: List<OperationalRecord>,
    val maintenance: List<OperationalRecord>,
    val maintenanceAlerts: List<OperationalRecord> = emptyList(),
)

data class EquipmentStatus(
    val equipmentTag: String,
    val current: OperationalRecord?,
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
