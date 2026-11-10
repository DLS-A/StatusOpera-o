package com.grm.rotagrm.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.grm.rotagrm.MainActivity
import com.grm.rotagrm.OperationalApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Public snapshot polling, not Firebase. Started only from visible Activity.
 * dataSync is intentional: Android 15's six-hour budget is respected. */
class OperationalMonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!enabled(this) || !OperationalNotifications.hasPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, monitoringNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, monitoringNotification())
        } catch (_: RuntimeException) {
            status.value = "Android impediu o monitor contínuo; verificação periódica de apoio."
            stopSelf()
            return START_NOT_STICKY
        }
        running.value = true
        status.value = "Monitor em segundo plano ativo"
        OperationalRefreshWorker.schedule(this)
        if (loop == null) loop = scope.launch {
            val monitor = (application as OperationalApplication).monitor
            while (true) {
                monitor.refresh()
                delay(monitor.state.value.settings.refreshIntervalSeconds * 1000L)
            }
        }
        // Never automatically restart a dataSync FGS from background after timeout.
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        status.value = "Limite do Android atingido; verificação periódica de apoio. Reabra o app para retomar."
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running.value = false
        if (!enabled(this)) status.value = "Monitor em segundo plano pausado"
        else if (status.value == "Monitor em segundo plano ativo") status.value = "Verificação periódica de apoio; reabra para retomar o monitor."
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun monitoringNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Monitor em segundo plano", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            description = "Consulta dos apontamentos; os alertas sonoros usam outro canal."
        })
        val open = PendingIntent.getActivity(this, ID, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, ID + 1, Intent(this, OperationalMonitoringService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Status Operacional — monitor ativo")
            .setContentText("Consultando apontamentos mesmo fora da tela. Internet necessária.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Pausar monitor", stop).build())
            .build()
    }

    companion object {
        private const val ID = 104
        private const val CHANNEL = "operational_monitor"
        private const val STOP = "com.grm.rotagrm.STOP_MONITOR"
        private val running = MutableStateFlow(false)
        private val status = MutableStateFlow("Abra o app e permita notificações para iniciar o monitor.")
        val isRunning = running.asStateFlow()
        val monitoringStatus = status.asStateFlow()
        fun enabled(context: Context): Boolean = context.getSharedPreferences("background_monitor", Context.MODE_PRIVATE).getBoolean("enabled", true)
        private fun setEnabled(context: Context, value: Boolean) {
            context.getSharedPreferences("background_monitor", Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
            if (!value) OperationalRefreshWorker.cancel(context)
        }
        fun start(context: Context, explicit: Boolean = false) {
            if (explicit) setEnabled(context, true)
            if (!enabled(context) || !OperationalNotifications.hasPermission(context)) return
            OperationalRefreshWorker.schedule(context)
            if (running.value) return
            try { context.startForegroundService(Intent(context, OperationalMonitoringService::class.java)) }
            catch (_: RuntimeException) { status.value = "Monitor contínuo indisponível; verificação periódica de apoio." }
        }
        fun pause(context: Context) {
            setEnabled(context, false)
            context.stopService(Intent(context, OperationalMonitoringService::class.java))
            status.value = "Monitor em segundo plano pausado"
        }
    }
}