package com.grm.rotagrm.domain.model

fun isMaintenanceStatus(status: String): Boolean {
    val normalized = java.text.Normalizer.normalize(status.uppercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").trim().replace(Regex("\\s+"), " ").replace(Regex("\\s*-\\s*"), "-")
    return normalized in setOf("AG MANUTENCAO", "EXEC MANUT CORRETIVA", "EXEC MANUT PREVENTIVA", "EXEC MANUT PREVENTIVA-250 HRS")
}

fun equipmentTabRecords(equipment: List<EquipmentStatus>): List<EquipmentStatus> =
    equipment.filter { !isMaintenanceStatus(it.current?.reasonName.orEmpty()) }