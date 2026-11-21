package com.grm.rotagrm.domain.model

enum class HomeEquipmentGroup(val title: String) {
    OPERATING("Em operação"), STOPPED("Parados")
}

private fun homeReason(value: String): String =
    java.text.Normalizer.normalize(value.uppercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").trim().replace(Regex("\\s+"), " ").replace(Regex("\\s*-\\s*"), "-")

private val operatingReasons = setOf(
    "PRODUZINDO/OPERANDO", "ACERTO DE PRACA", "MOVIMENTANDO VAZIO", "CONTRAPILHANDO MATERIAL",
    "FILA NO CARREGAMENTO", "CARREGANDO CAMINHAO", "MANOBRA CARREGAMENTO", "EM CARREGAMENTO",
    "MOVIMENTANDO CHEIO", "FILA NO BASCULAMENTO", "MANOBRA BASCULAMENTO", "BASCULANDO",
    "SERVICO DE DRENAGEM", "CAPTACAO DE AGUA",
)
private val stoppedReasons = setOf(
    "FALTA DE OPERADOR", "LIBERADO PELA MANUT", "LIBERADO PELA MANUTENCAO",
)

fun homeReasonLabel(reason: String): String = when (homeReason(reason)) {
    "LIBERADO PELA MANUT", "LIBERADO PELA MANUTENCAO" -> "LIBERADO PELA MANUTENÇÃO"
    "FALTA DE OPERADOR" -> "FALTA DE OPERADOR"
    else -> reason
}

fun homeEquipmentGroup(reason: String?): HomeEquipmentGroup? = when (reason?.let(::homeReason)) {
    in operatingReasons -> HomeEquipmentGroup.OPERATING
    in stoppedReasons -> HomeEquipmentGroup.STOPPED
    else -> null
}

/** Current pointing only, across all equipment types. GRM statusType is not overridden. */
fun homeEquipment(equipment: List<EquipmentStatus>, group: HomeEquipmentGroup): List<EquipmentStatus> =
    equipment.distinctBy { it.equipmentTag }.filter { homeEquipmentGroup(it.current?.reasonName) == group }
        .sortedBy { it.equipmentTag }