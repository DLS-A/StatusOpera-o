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

fun operatorLabel(value: String): String = value.trim().takeUnless {
    normalizedOperationalStatus(it) in setOf("", "-", "NULL", "NONE", "NAO INFORMADO", "SEM OPERADOR")
} ?: "Apontado via Web"

fun pointingLocation(value: String): String? = value.trim().takeUnless {
    normalizedOperationalStatus(it) in setOf("", "-", "NULL", "NONE", "NAO INFORMADO")
}

fun alertPointing(alert: OperationalAlert, snapshot: OperationalSnapshot?): OperationalRecord? =
    (snapshot?.equipment.orEmpty().mapNotNull { it.current } + snapshot?.maintenance.orEmpty() + snapshot?.pauses.orEmpty())
        .firstOrNull { it.id == alert.id.removePrefix("maintenance:") && it.equipmentTag == alert.equipmentTag &&
            normalizedOperationalStatus(it.reasonName) == normalizedOperationalStatus(alert.status) }

/** Local preferences gate, not server authentication. Store only the PIN digest. */
fun settingsPasswordAccepted(value: String): Boolean {
    if (value.length != 5 || !value.all { it in '0'..'9' }) return false
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    return digest == "6aedeffa139da461eeee34338f149553b80ee36f883c0cb68890e2cc8d29effb"
}