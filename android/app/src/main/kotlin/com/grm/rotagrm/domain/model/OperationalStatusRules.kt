package com.grm.rotagrm.domain.model

private fun normalizedOperationalStatus(status: String): String =
    java.text.Normalizer.normalize(status.uppercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").trim().replace(Regex("\\s+"), " ").replace(Regex("\\s*-\\s*"), "-")

fun isMaintenanceStatus(status: String): Boolean = normalizedOperationalStatus(status) in
    setOf("AG MANUTENCAO", "EXEC MANUT CORRETIVA", "EXEC MANUT PREVENTIVA", "EXEC MANUT PREVENTIVA-250 HRS")

fun isAwaitingMaintenanceStatus(status: String): Boolean = normalizedOperationalStatus(status) == "AG MANUTENCAO"

fun isReleasedMaintenanceStatus(status: String): Boolean = normalizedOperationalStatus(status) == "LIBERADO PELA MANUT"

/** Presentation only: released equipment is not a maintenance/sound event. */
fun homeAlerts(alerts: List<OperationalAlert>, snapshot: OperationalSnapshot? = null,
    now: Long = System.currentTimeMillis()): List<OperationalAlert> {
    val visible = alerts.filter { isAwaitingMaintenanceStatus(it.status) ||
        isReleasedMaintenanceStatus(it.status) || normalizedOperationalStatus(it.status) == "REFEICAO" }
    val released = snapshot?.equipment.orEmpty().mapNotNull { it.current }
        .filter { it.id.isNotBlank() && isReleasedMaintenanceStatus(it.reasonName) }
        .map { OperationalAlert(id = it.id, equipmentTag = it.equipmentTag, status = it.reasonName,
            elapsedSeconds = it.elapsedSeconds(snapshot, now), limitSeconds = 0L, exceededSeconds = 0L) }
    return (visible + released).distinctBy { it.id }
}

fun homeAlertDescription(alert: OperationalAlert, elapsed: Long): String = when {
    isAwaitingMaintenanceStatus(alert.status) -> "Aguardando manutenção."
    isReleasedMaintenanceStatus(alert.status) -> "Liberado pela manutenção."
    else -> "Excedido em %02d:%02d:%02d · limite %d min".format(
        (elapsed - alert.limitSeconds).coerceAtLeast(0L) / 3600,
        (elapsed - alert.limitSeconds).coerceAtLeast(0L) % 3600 / 60,
        (elapsed - alert.limitSeconds).coerceAtLeast(0L) % 60, alert.limitSeconds / 60)
}

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