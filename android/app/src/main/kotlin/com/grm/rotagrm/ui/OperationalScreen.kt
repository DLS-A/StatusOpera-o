package com.grm.rotagrm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.grm.rotagrm.OperationalUiState
import com.grm.rotagrm.domain.model.AlertEvaluator
import com.grm.rotagrm.domain.model.EquipmentStatus
import com.grm.rotagrm.domain.model.OperationalAlert
import com.grm.rotagrm.domain.model.OperationalRecord
import com.grm.rotagrm.settings.DEFAULT_PAUSE_STATUS
import com.grm.rotagrm.settings.MonitoringSettings
import kotlinx.coroutines.delay
import java.time.Instant

private enum class Page(val title: String, val label: String) {
    HOME("Início", "Início"), EQUIPMENT("Equipamentos", "Equip."), PAUSES("Pausas", "Pausas"),
    MAINTENANCE("Manutenções", "Manut."), SETTINGS("Configurações", "Ajustes"),
}

private enum class EquipmentFilter(val label: String) {
    ALL("Todos"), ACTIVE("Com status"), IDLE("Sem status"),
}

@Composable
fun OperationalScreen(
    state: OperationalUiState,
    notificationsAllowed: Boolean,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    onRequestNotifications: () -> Unit,
    onAcknowledge: (String) -> Unit,
    onRefreshInterval: (Int) -> Unit,
    onStatusLimit: (String, Int) -> Unit,
    onStatusAlerts: (String, Boolean) -> Unit,
    onRepeatMinutes: (Int) -> Unit,
    onSound: (Boolean) -> Unit,
    onRestoreSettings: () -> Unit,
) {
    var page by remember { mutableStateOf(Page.HOME) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Page.entries.forEach { item ->
                    NavigationBarItem(
                        selected = page == item,
                        onClick = { page = item },
                        icon = { Text(item.label.take(1)) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("ROTA GRM", style = MaterialTheme.typography.headlineMedium)
                    Text(if (state.configured) "Conexão segura configurada" else "Configure backend HTTPS e provedor OIDC.")
                }
                if (state.loading) CircularProgressIndicator()
            }
            if (state.message.isNotBlank()) Text(state.message, Modifier.padding(horizontal = 20.dp))
            if (!state.signedIn) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Entre para consultar os dados operacionais autorizados.")
                    Button(onClick = onLogin, enabled = state.configured) { Text("Entrar com segurança") }
                }
            } else {
                when (page) {
                    Page.HOME -> HomePage(state, notificationsAllowed, onRefresh, onSignOut, onRequestNotifications, onAcknowledge)
                    Page.EQUIPMENT -> EquipmentPage(state.snapshot?.equipment.orEmpty(), state)
                    Page.PAUSES -> RecordsPage("Pausas ergonômicas", state.snapshot?.pauses.orEmpty(), state)
                    Page.MAINTENANCE -> RecordsPage("Manutenções abertas", state.snapshot?.maintenance.orEmpty(), state)
                    Page.SETTINGS -> SettingsPage(
                        state = state,
                        notificationsAllowed = notificationsAllowed,
                        onRequestNotifications = onRequestNotifications,
                        onRefreshInterval = onRefreshInterval,
                        onStatusLimit = onStatusLimit,
                        onStatusAlerts = onStatusAlerts,
                        onRepeatMinutes = onRepeatMinutes,
                        onSound = onSound,
                        onRestoreSettings = onRestoreSettings,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomePage(
    state: OperationalUiState,
    notificationsAllowed: Boolean,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    onRequestNotifications: () -> Unit,
    onAcknowledge: (String) -> Unit,
) {
    val snapshot = state.snapshot
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "Estado da conexão: ${if (state.apiAvailable) "conectado" else if (snapshot != null) "indisponível; exibindo último dado" else "aguardando dados"}",
                color = if (state.apiAvailable) MaterialTheme.colorScheme.primary else Color(0xFF8A5A00),
            )
            Text("Última atualização bem-sucedida: ${snapshot?.updatedAt ?: "—"}")
            Text("Equipamentos: ${snapshot?.equipment?.size ?: 0}    Alertas ativos: ${state.alerts.size}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRefresh, enabled = !state.loading) { Text("Atualizar agora") }
                OutlinedButton(onClick = onSignOut) { Text("Sair") }
            }
            if (!notificationsAllowed) {
                Text("As notificações estão desativadas; alertas continuam visíveis enquanto esta tela estiver aberta.")
                Button(onClick = onRequestNotifications) { Text("Permitir notificações") }
            }
            Text("Pausas: ${snapshot?.pauses?.size ?: 0}   Manutenções: ${snapshot?.maintenance?.size ?: 0}")
            Text("Alertas ativos", style = MaterialTheme.typography.titleLarge)
        }
        if (state.alerts.isEmpty()) item { Text("Nenhum limite excedido.") }
        items(state.alerts, key = { "alert-${it.id}" }) { alert ->
            AlertCard(alert, state.settings, alert.id in state.acknowledgedAlertIds, onAcknowledge)
        }
    }
}

@Composable
private fun EquipmentPage(equipment: List<EquipmentStatus>, state: OperationalUiState) {
    var search by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(EquipmentFilter.ALL) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val filtered = equipment.filter { item ->
        item.equipmentTag.contains(search, ignoreCase = true) && when (filter) {
            EquipmentFilter.ALL -> true
            EquipmentFilter.ACTIVE -> item.current != null
            EquipmentFilter.IDLE -> item.current == null
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Equipamentos", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("Pesquisar equipamento") }, singleLine = true)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EquipmentFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                    )
                }
            }
        }
        items(filtered, key = { it.equipmentTag }) { item ->
            val record = item.current
            val limit = record?.let { state.settings.limitsMinutesByStatus[it.reasonName] }
                ?: record?.let { if (isErgonomic(it.reasonName)) state.settings.limitsMinutesByStatus[DEFAULT_PAUSE_STATUS] else null }
            val elapsed = record?.elapsedAt(now) ?: 0L
            val statusColor = when {
                limit != null && elapsed > limit * 60L -> Color(0xFFB3261E)
                limit != null && elapsed >= limit * 48L -> Color(0xFF8A5A00)
                else -> MaterialTheme.colorScheme.primary
            }
            Card(Modifier.fillMaxWidth().clickable { selectedTag = if (selectedTag == item.equipmentTag) null else item.equipmentTag }) {
                Column(Modifier.padding(14.dp)) {
                    Text(item.equipmentTag, style = MaterialTheme.typography.titleMedium, color = statusColor)
                    Text(record?.reasonName ?: "Sem apontamento aberto")
                    if (record != null) {
                        Text("Duração: ${duration(elapsed)}")
                        Text("Operador: ${record.operator.ifBlank { "Não informado" }} • Local: ${record.location.ifBlank { "Não informado" }}")
                        if (selectedTag == item.equipmentTag) {
                            Text("Início (UTC): ${record.startEpochMillis?.let { Instant.ofEpochMilli(it).toString() } ?: "Indisponível"}")
                            Text("Limite: ${limit?.let { "$it min" } ?: "Não configurado"}")
                            if (limit != null && elapsed > limit * 60L) Text("Excedido em: ${duration(elapsed - limit * 60L)}", color = Color(0xFFB3261E))
                            Text("Tipo: ${record.equipmentType} • Categoria: ${record.kpiCategory}")
                        }
                    }
                }
            }
        }
        if (filtered.isEmpty()) item { Text("Nenhum equipamento corresponde à pesquisa.") }
    }
}

@Composable
private fun RecordsPage(title: String, records: List<OperationalRecord>, state: OperationalUiState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(title, style = MaterialTheme.typography.titleLarge) }
        items(records, key = { it.id }) { item ->
            val limit = state.settings.limitsMinutesByStatus[item.reasonName]
                ?: if (isErgonomic(item.reasonName)) state.settings.limitsMinutesByStatus[DEFAULT_PAUSE_STATUS] else null
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("${item.equipmentTag} • ${item.reasonName}", style = MaterialTheme.typography.titleMedium)
                    Text("Início (UTC): ${item.startEpochMillis?.let { Instant.ofEpochMilli(it).toString() } ?: "Indisponível"}")
                    Text("Duração: ${duration(item.elapsedAt(now))}")
                    if (limit != null) Text("Limite: $limit min")
                    Text("Operador: ${item.operator.ifBlank { "Não informado" }} • Local: ${item.location.ifBlank { "Não informado" }}")
                }
            }
        }
        if (records.isEmpty()) item { Text("Nenhum registro aberto.") }
    }
}

@Composable
private fun SettingsPage(
    state: OperationalUiState,
    notificationsAllowed: Boolean,
    onRequestNotifications: () -> Unit,
    onRefreshInterval: (Int) -> Unit,
    onStatusLimit: (String, Int) -> Unit,
    onStatusAlerts: (String, Boolean) -> Unit,
    onRepeatMinutes: (Int) -> Unit,
    onSound: (Boolean) -> Unit,
    onRestoreSettings: () -> Unit,
) {
    val statuses = (state.snapshot?.equipment.orEmpty().mapNotNull { it.current?.reasonName } +
        state.settings.limitsMinutesByStatus.keys).distinct().sorted()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Monitoramento", style = MaterialTheme.typography.titleLarge)
            StepSetting("Atualização", "${state.settings.refreshIntervalSeconds} segundos", state.settings.refreshIntervalSeconds, 30, 3600, 30, onRefreshInterval)
            Text("Limites e alertas por status", style = MaterialTheme.typography.titleMedium)
        }
        items(statuses, key = { "setting-$it" }) { status ->
            StatusSetting(status, state.settings, onStatusLimit, onStatusAlerts)
        }
        item {
            StepSetting("Repetir alerta", "A cada ${state.settings.repeatAlertMinutes} min", state.settings.repeatAlertMinutes, 1, 1440, 1, onRepeatMinutes)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Som das notificações")
                    Text("O volume é controlado pelo Android e pelo canal de notificações.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(state.settings.alertSoundEnabled, onSound)
            }
            if (!notificationsAllowed) Button(onClick = onRequestNotifications) { Text("Permitir notificações") }
            OutlinedButton(onClick = onRestoreSettings) { Text("Restaurar padrões") }
            Text("A repetição opera enquanto o app consulta em primeiro plano. Alertas com app suspenso exigem monitor no servidor e push.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusSetting(
    status: String,
    settings: MonitoringSettings,
    onStatusLimit: (String, Int) -> Unit,
    onStatusAlerts: (String, Boolean) -> Unit,
) {
    val limit = settings.limitsMinutesByStatus[status]
        ?: if (isErgonomic(status)) settings.limitsMinutesByStatus[DEFAULT_PAUSE_STATUS] else null
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(status, style = MaterialTheme.typography.titleMedium)
            if (limit == null) {
                Text("Sem limite configurado")
                TextButton(onClick = { onStatusLimit(status, 60) }) { Text("Configurar limite inicial de 60 min") }
            } else {
                StepSetting("Limite", "$limit min", limit, 1, 1440, 1) { onStatusLimit(status, it) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (isMaintenance(status)) "Alertar quando iniciar hoje" else "Alertar ao exceder")
                Switch(
                    checked = settings.alertsEnabledByStatus[status]
                        ?: if (isErgonomic(status)) settings.alertsEnabledByStatus[DEFAULT_PAUSE_STATUS] ?: false else false,
                    onCheckedChange = { onStatusAlerts(status, it) },
                )
            }
        }
    }
}

@Composable
private fun StepSetting(
    title: String,
    valueLabel: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onValue: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) { Text(title); Text(valueLabel) }
        OutlinedButton(onClick = { onValue((value - step).coerceAtLeast(min)) }, enabled = value > min) { Text("−") }
        OutlinedButton(onClick = { onValue((value + step).coerceAtMost(max)) }, enabled = value < max) { Text("+") }
    }
}

@Composable
private fun AlertCard(
    alert: OperationalAlert,
    settings: MonitoringSettings,
    acknowledged: Boolean,
    onAcknowledge: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("${alert.equipmentTag} • ${alert.status}", style = MaterialTheme.typography.titleMedium, color = Color(0xFFB3261E))
            if (alert.maintenanceEvent) Text("Nova manutenção iniciada hoje.")
            else Text("Excedido em ${duration(alert.exceededSeconds)} (limite ${settings.limitsMinutesByStatus[alert.status] ?: alert.limitSeconds / 60} min)")
            if (acknowledged) Text("Reconhecido e silenciado")
            else TextButton(onClick = { onAcknowledge(alert.id) }) { Text("Reconhecer e silenciar") }
        }
    }
}

private fun OperationalRecord.elapsedAt(now: Long): Long = startEpochMillis?.let {
    ((now - it) / 1000L).coerceAtLeast(0L)
} ?: durationSeconds.coerceAtLeast(0L)

private fun duration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

private fun isErgonomic(status: String): Boolean =
    "PAUSA" in status.uppercase() || "ERGONOM" in status.uppercase()

private fun isMaintenance(status: String): Boolean = status.uppercase() in setOf(
    "EXEC MANUT CORRETIVA", "EXEC MANUT PREVENTIVA",
)
