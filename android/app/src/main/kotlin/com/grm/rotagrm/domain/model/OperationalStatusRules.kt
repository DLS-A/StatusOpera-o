package com.grm.rotagrm.domain.model

private fun normalizedOperationalStatus(status: String): String =
    java.text.Normalizer.normalize(status.uppercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").trim().replace(Regex("\\s+"), " ").replace(Regex("\\s*-\\s*"), "-")

fun isMaintenanceStatus(status: String): Boolean = normalizedOperationalStatus(status) in
    setOf("AG MANUTENCAO", "EXEC MANUT CORRETIVA", "EXEC MANUT PREVENTIVA", "EXEC MANUT PREVENTIVA-250 HRS")

fun isAwaitingMaintenanceStatus(status: String): Boolean = normalizedOperationalStatus(status) == "AG MANUTENCAO"

/** Presentation-only filter. Does not change the monitor, notifications or maintenance tab. */
fun homeAlerts(alerts: List<OperationalAlert>): List<OperationalAlert> =
    alerts.filter { isAwaitingMaintenanceStatus(it.status) || normalizedOperationalStatus(it.status) == "REFEICAO" }

fun equipmentTabRecords(equipment: List<EquipmentStatus>): List<EquipmentStatus> =
    equipment.filter { !isMaintenanceStatus(it.current?.reasonName.orEmpty()) }