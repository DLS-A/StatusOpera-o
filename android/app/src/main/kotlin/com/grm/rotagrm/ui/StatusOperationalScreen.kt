package com.grm.rotagrm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grm.rotagrm.BuildConfig
import com.grm.rotagrm.OperationalUiState
import com.grm.rotagrm.R
import com.grm.rotagrm.domain.model.EquipmentStatus
import com.grm.rotagrm.domain.model.OperationalAlert
import com.grm.rotagrm.domain.model.OperationalRecord
import com.grm.rotagrm.settings.DEFAULT_PAUSE_STATUS
import com.grm.rotagrm.settings.MonitoringSettings
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val BrandBlue = Color(0xFF2E3192)
private val MutedText = Color(0xFF596579)
private val AlertRed = Color(0xFFB3261E)
private val WarningAmber = Color(0xFF8A5A00)

private enum class OperationalTab(val title: String, val shortTitle: String, val icon: Int) {
    HOME("Início", "Início", R.drawable.ic_home),
    EQUIPMENT("Equipamentos", "Equip.", R.drawable.ic_equipment),
    PAUSES("Pausas", "Pausas", R.drawable.ic_pause),
    MAINTENANCE("Manutenções", "Manut.", R.drawable.ic_maintenance),
    SETTINGS("Ajustes", "Ajustes", R.drawable.ic_settings),
}

@Composable
fun OperationalScreen(
    state: OperationalUiState,
    notificationsAllowed: Boolean,
    onRefresh: () -> Unit,
    onRequestNotifications: () -> Unit,
    onAcknowledge: (String) -> Unit,
    onRefreshInterval: (Int) -> Unit,
    onStatusLimit: (String, Int) -> Unit,
    onStatusAlerts: (String, Boolean) -> Unit,
    onRepeatMinutes: (Int) -> Unit,
    onSound: (Boolean) -> Unit,
    onVisual: (Boolean) -> Unit,
    onRestoreSettings: () -> Unit,
) {
    var tab by remember { mutableStateOf(OperationalTab.HOME) }
    val visible = state
    Scaffold(
        containerColor = Color.White,
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                OperationalTab.entries.forEach { item ->
                    NavigationBarItem(
                        modifier = Modifier.semantics { contentDescription = item.title },
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(painterResource(item.icon), null, Modifier.size(23.dp)) },
                        label = { Text(item.shortTitle, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BrandBlue, selectedTextColor = BrandBlue,
                            indicatorColor = Color(0xFFEDEDF8),
                            unselectedIconColor = MutedText, unselectedTextColor = MutedText,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(painterResource(R.drawable.ic_equipment), null, Modifier.size(30.dp), tint = BrandBlue)
                Column(Modifier.weight(1f)) {
                    Text("Status Operacional", color = BrandBlue, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("Apontamentos da obra · acesso livre", style = MaterialTheme.typography.labelSmall, color = MutedText)
                }
                if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            CurrentSnapshotBanner(visible)
            if (state.message.isNotBlank()) Text(state.message, Modifier.padding(horizontal = 18.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            Box(Modifier.weight(1f)) {
                when (tab) {
                    OperationalTab.HOME -> OverviewContent(visible, notificationsAllowed, onRefresh, onRequestNotifications, onAcknowledge)
                    OperationalTab.EQUIPMENT -> EquipmentContent(visible.snapshot?.equipment.orEmpty(), visible)
                    OperationalTab.PAUSES -> RecordContent("Pausas ergonômicas", visible.snapshot?.pauses.orEmpty(), visible, R.drawable.ic_pause)
                    OperationalTab.MAINTENANCE -> RecordContent("Manutenções abertas", visible.snapshot?.maintenance.orEmpty(), visible, R.drawable.ic_maintenance)
                    OperationalTab.SETTINGS -> PreferencesContent(visible, notificationsAllowed, onRequestNotifications, onRefreshInterval, onStatusLimit, onStatusAlerts, onRepeatMinutes, onSound, onVisual, onRestoreSettings)
                }
            }
        }
    }
}

@Composable
private fun OutlinePanel(modifier: Modifier = Modifier, alert: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, if (alert) AlertRed.copy(alpha = .4f) else BrandBlue.copy(alpha = .14f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) }
}

@Composable
private fun SectionHeading(title: String, trailing: String = "") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (trailing.isNotEmpty()) Text(trailing, style = MaterialTheme.typography.labelSmall, color = MutedText)
    }
}

@Composable
private fun MetricPanel(label: String, value: String, icon: Int, modifier: Modifier = Modifier) {
    OutlinePanel(modifier) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = BrandBlue)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MutedText)
        Text(value, color = BrandBlue, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OverviewContent(state: OperationalUiState, notificationsAllowed: Boolean, onRefresh: () -> Unit, onPermission: () -> Unit, onAcknowledge: (String) -> Unit) {
    val snapshot = state.snapshot
    val equipment = snapshot?.equipment.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeading("Visão geral", if (state.apiAvailable) "Conectado" else if (snapshot != null) "Último dado salvo" else "Sem dados") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricPanel("Equipamentos", snapshot?.equipment?.size?.toString() ?: "—", R.drawable.ic_equipment, Modifier.weight(1f))
                MetricPanel("Alertas ativos", if (snapshot != null) state.alerts.size.toString() else "—", R.drawable.ic_equipment, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricPanel("Pausas", snapshot?.pauses?.size?.toString() ?: "—", R.drawable.ic_pause, Modifier.weight(1f))
                MetricPanel("Manutenções", snapshot?.maintenance?.size?.toString() ?: "—", R.drawable.ic_maintenance, Modifier.weight(1f))
            }
        }
        item {
            OutlinePanel {
                Text("Classificação operacional", fontWeight = FontWeight.SemiBold, color = BrandBlue)
                Text(if (snapshot == null) "Aguardando dados autorizados."
                    else "Em operação: ${equipment.count { it.operationalState == "operating" }} · Parados: ${equipment.count { it.operationalState == "stopped" }}", style = MaterialTheme.typography.bodySmall)
                if (snapshot != null) {
                    Text("Sem classificação confirmada: ${equipment.count { it.operationalState == "unknown" }}", style = MaterialTheme.typography.bodySmall)
                    Text("Com apontamento: ${equipment.count { it.current != null }} · Sem apontamento: ${equipment.count { it.current == null }}", style = MaterialTheme.typography.bodySmall)
                    Text("Ausência de apontamento não significa equipamento parado. Categorias KPI precisam de confirmação administrativa.", style = MaterialTheme.typography.labelSmall, color = MutedText)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRefresh, enabled = state.signedIn && !state.loading, modifier = Modifier.weight(1f)) { Text("Atualizar") }
            }
        }
        if (state.signedIn && !notificationsAllowed) item {
            OutlinePanel {
                Text("Notificações desativadas", fontWeight = FontWeight.SemiBold)
                Text("Alertas continuam visíveis em primeiro plano.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onPermission) { Text("Permitir notificações") }
            }
        }
        item { SectionHeading("Alertas ativos") }
        if (state.alerts.isEmpty()) item {
            Text(if (snapshot == null) "Aguardando conexão com o servidor." else "Nenhum limite excedido.", color = MutedText, style = MaterialTheme.typography.bodySmall)
        }
        items(state.alerts, key = { "alert-${it.id}" }) { alert ->
            OutlinePanel(alert = true) {
                Text(alert.equipmentTag, fontWeight = FontWeight.Bold, color = AlertRed)
                Text(alert.status, style = MaterialTheme.typography.labelMedium)
                Text(if (alert.maintenanceEvent) "Nova manutenção iniciada hoje." else "Excedido em ${clockDuration(alert.exceededSeconds)} · limite ${state.settings.limitsMinutesByStatus[alert.status] ?: alert.limitSeconds / 60} min", style = MaterialTheme.typography.bodySmall)
                if (alert.id in state.acknowledgedAlertIds) Text("Reconhecido e silenciado", style = MaterialTheme.typography.labelSmall)
                else TextButton(onClick = { onAcknowledge(alert.id) }) { Text("Reconhecer e silenciar") }
            }
        }
    }
}

@Composable
private fun EquipmentContent(equipment: List<EquipmentStatus>, state: OperationalUiState) {
    var search by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    val now = rememberOperationalClock()
    // Sem enum, chips ou filtro oculto de status: somente a pesquisa.
    val filtered = equipment.filter { it.equipmentTag.contains(search, ignoreCase = true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionHeading("Equipamentos", if (state.snapshot != null) "${equipment.size} monitorados" else "")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("Pesquisar equipamento") }, singleLine = true, shape = RoundedCornerShape(12.dp))
        }
        items(filtered, key = { it.equipmentTag }) { item ->
            val record = item.current
            val limit = record?.let { operationalLimit(it.reasonName, state.settings) }
            val elapsed = record?.elapsedSeconds(state.snapshot, confirmedNow(state, now)) ?: 0L
            val exceeded = limit != null && elapsed > limit * 60L
            val color = if (exceeded) AlertRed else if (limit != null && elapsed >= limit * 48L) WarningAmber else BrandBlue
            OutlinePanel(Modifier.clickable { selectedTag = if (selectedTag == item.equipmentTag) null else item.equipmentTag }, exceeded) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.ic_equipment), null, Modifier.size(20.dp), tint = color)
                    Text(item.equipmentTag, Modifier.weight(1f), fontWeight = FontWeight.Bold, color = color)
                    if (exceeded) Text("Limite excedido", color = AlertRed, style = MaterialTheme.typography.labelSmall)
                    Text(if (selectedTag == item.equipmentTag) "−" else "+", color = BrandBlue)
                }
                Text(record?.reasonName ?: "Sem apontamento aberto", color = color, style = MaterialTheme.typography.labelMedium)
                if (record != null) {
                    Text("Duração: ${clockDuration(elapsed)}", fontWeight = FontWeight.SemiBold, color = BrandBlue)
                    Text("Operador: ${record.operator.ifBlank { "Não informado" }}", style = MaterialTheme.typography.bodySmall)
                    Text("Local: ${record.location.ifBlank { "Não informado" }}", style = MaterialTheme.typography.bodySmall)
                    if (selectedTag == item.equipmentTag) {
                        Text("Início: ${record.startEpochMillis?.let { localCollectionTime(Instant.ofEpochMilli(it).toString()) } ?: "Indisponível"}", style = MaterialTheme.typography.bodySmall)
                        Text("Limite: ${limit?.let { "$it min" } ?: "Não configurado"}", style = MaterialTheme.typography.bodySmall)
                        if (exceeded && limit != null) Text("Excedido em ${clockDuration(elapsed - limit * 60L)}", color = AlertRed)
                        Text("Tipo: ${record.equipmentType} · Categoria: ${record.kpiCategory}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (filtered.isEmpty()) item { EmptyOperationalList(state, if (search.isNotBlank()) "Nenhum equipamento corresponde à pesquisa." else "Nenhum equipamento na coleta.") }
    }
}

@Composable
private fun RecordContent(title: String, records: List<OperationalRecord>, state: OperationalUiState, icon: Int) {
    val now = rememberOperationalClock()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeading(title, if (state.snapshot != null) "${records.size} registros" else "") }
        items(records, key = { it.id }) { record ->
            val elapsed = record.elapsedSeconds(state.snapshot, confirmedNow(state, now))
            val limit = operationalLimit(record.reasonName, state.settings)
            val exceeded = limit != null && elapsed > limit * 60L
            OutlinePanel(alert = exceeded) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(icon), null, Modifier.size(22.dp), tint = if (exceeded) AlertRed else BrandBlue)
                    Text(record.equipmentTag, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    if (exceeded) Text("Limite excedido", color = AlertRed, style = MaterialTheme.typography.labelSmall)
                }
                Text(record.reasonName, color = if (exceeded) AlertRed else BrandBlue, style = MaterialTheme.typography.labelMedium)
                Text(clockDuration(elapsed), color = BrandBlue, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Duração do apontamento", color = MutedText, style = MaterialTheme.typography.labelSmall)
                HorizontalDivider(Modifier.padding(vertical = 5.dp), color = Color(0xFFEDEDF8))
                Text("Início: ${record.startEpochMillis?.let { localCollectionTime(Instant.ofEpochMilli(it).toString()) } ?: "Indisponível"}", style = MaterialTheme.typography.bodySmall)
                if (limit != null) Text("Limite: $limit min", style = MaterialTheme.typography.bodySmall)
                Text("Operador: ${record.operator.ifBlank { "Não informado" }}", style = MaterialTheme.typography.bodySmall)
                Text("Local: ${record.location.ifBlank { "Não informado" }}", style = MaterialTheme.typography.bodySmall)
                if (exceeded && limit != null) Text("Limite excedido em ${clockDuration(elapsed - limit * 60L)}", color = AlertRed, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (records.isEmpty()) item { EmptyOperationalList(state, "Nenhum registro aberto.") }
    }
}

@Composable
private fun EmptyOperationalList(state: OperationalUiState, emptyMessage: String) {
    OutlinePanel {
        Text(if (state.snapshot == null) "Aguardando dados" else emptyMessage, color = MutedText, fontWeight = FontWeight.SemiBold)
        if (state.snapshot == null) Text("Conecte à internet e toque em Atualizar para consultar os apontamentos.", style = MaterialTheme.typography.bodySmall, color = MutedText)
    }
}

@Composable
private fun PreferencesContent(state: OperationalUiState, allowed: Boolean, onPermission: () -> Unit, onRefresh: (Int) -> Unit, onLimit: (String, Int) -> Unit, onAlerts: (String, Boolean) -> Unit, onRepeat: (Int) -> Unit, onSound: (Boolean) -> Unit, onVisual: (Boolean) -> Unit, onRestore: () -> Unit) {
    val settings = state.settings
    val statuses = (state.snapshot?.equipment.orEmpty().mapNotNull { it.current?.reasonName } + settings.limitsMinutesByStatus.keys).distinct().sorted()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeading("Monitoramento") }
        item { OutlinePanel { ValueStepper("Atualização", "${settings.refreshIntervalSeconds} segundos", settings.refreshIntervalSeconds, 30, 3600, 30, onRefresh) } }
        item { SectionHeading("Limites e alertas") }
        items(statuses, key = { "setting-$it" }) { status ->
            OutlinePanel {
                Text(status, color = BrandBlue, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                val limit = operationalLimit(status, settings)
                if (limit == null) {
                    Text("Sem limite configurado", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onLimit(status, 60) }) { Text("Configurar limite de 60 min") }
                } else ValueStepper("Limite", "$limit min", limit, 1, 1440, 1) { onLimit(status, it) }
                val checked = settings.alertsEnabledByStatus[status] ?: if (ergonomicStatus(status)) settings.alertsEnabledByStatus[DEFAULT_PAUSE_STATUS] ?: false else false
                PreferenceSwitch(if (maintenanceStatus(status)) "Alertar novo apontamento" else "Alertar ao exceder", checked) { onAlerts(status, it) }
            }
        }
        item { SectionHeading("Notificações") }
        item {
            OutlinePanel {
                ValueStepper("Repetir alerta", "A cada ${settings.repeatAlertMinutes} min", settings.repeatAlertMinutes, 1, 1440, 1, onRepeat)
                PreferenceSwitch("Som", settings.alertSoundEnabled, onSound)
                PreferenceSwitch("Notificações visuais", settings.alertVisualEnabled, onVisual)
                Text("O volume é controlado pelo Android e pelo canal de notificações.", style = MaterialTheme.typography.labelSmall, color = MutedText)
                if (!allowed) TextButton(onClick = onPermission) { Text("Permitir notificações") }
            }
        }
        item { OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text("Restaurar padrões") } }
        item {
            OutlinePanel {
                Text(if (BuildConfig.FCM_ENABLED) "Push habilitado nesta compilação" else "Push desabilitado nesta compilação", color = BrandBlue, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                Text("Alertas e repetição locais funcionam durante o uso em primeiro plano. Sem push com o app fechado.", style = MaterialTheme.typography.labelSmall, color = MutedText)
            }
        }
        item { Text("Versão ${BuildConfig.VERSION_NAME} · ${if (state.configured) "acesso livre" else "servidor pendente"}", color = MutedText, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun PreferenceSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun ValueStepper(title: String, description: String, value: Int, min: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.labelSmall, color = MutedText)
        }
        OutlinedButton(onClick = { onChange((value - step).coerceAtLeast(min)) }, enabled = value > min, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.semantics { contentDescription = "Diminuir $title" }) { Text("−") }
        OutlinedButton(onClick = { onChange((value + step).coerceAtMost(max)) }, enabled = value < max, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.semantics { contentDescription = "Aumentar $title" }) { Text("+") }
    }
}

@Composable
private fun rememberOperationalClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    return now
}

private fun ergonomicStatus(status: String): Boolean = "PAUSA" in status.uppercase() || "ERGONOM" in status.uppercase()
private fun maintenanceStatus(status: String): Boolean = status.uppercase().replace(Regex("\\s*-\\s*"), "-") in setOf("AG MANUTENCAO", "EXEC MANUT CORRETIVA", "EXEC MANUT PREVENTIVA", "EXEC MANUT PREVENTIVA-250 HRS")
private fun operationalLimit(status: String, settings: MonitoringSettings): Int? = settings.limitsMinutesByStatus[status]
    ?: if (ergonomicStatus(status)) settings.limitsMinutesByStatus[DEFAULT_PAUSE_STATUS] else null
private fun OperationalRecord.recordSeconds(now: Long): Long = startEpochMillis?.let { ((now - it) / 1000L).coerceAtLeast(0L) } ?: durationSeconds.coerceAtLeast(0L)
private fun clockDuration(seconds: Long): String = "%02d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
private fun localCollectionTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss '· São Paulo'").withZone(ZoneId.of("America/Sao_Paulo")).format(Instant.parse(value))
}.getOrDefault("Horário indisponível")
private fun oldCollection(state: OperationalUiState, now: Long): Boolean {
    val snapshot = state.snapshot ?: return true
    val updated = runCatching { Instant.parse(snapshot.updatedAt).toEpochMilli() }.getOrDefault(0L)
    return snapshot.stale || !state.apiAvailable || now - updated > maxOf(120_000L, snapshot.refreshIntervalSeconds * 2000L)
}
private fun confirmedNow(state: OperationalUiState, now: Long): Long =
    if (oldCollection(state, now)) state.snapshot?.updatedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: now else now

@Composable
private fun CurrentSnapshotBanner(state: OperationalUiState) {
    val now = rememberOperationalClock()
    val snapshot = state.snapshot ?: return
    val stale = oldCollection(state, now)
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = if (stale) Color(0xFFFFF7E8) else Color(0xFFEDEDF8), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(if (stale) "Dados desatualizados" else "Dados atualizados", color = if (stale) WarningAmber else BrandBlue, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(localCollectionTime(snapshot.updatedAt), style = MaterialTheme.typography.labelSmall, color = MutedText)
            if (stale) Text("Durações e alertas refletem essa coleta, não o estado atual.", style = MaterialTheme.typography.labelSmall, color = WarningAmber)
        }
    }
}