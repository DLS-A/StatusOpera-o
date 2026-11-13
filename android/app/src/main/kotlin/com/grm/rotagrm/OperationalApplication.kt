package com.grm.rotagrm

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.grm.rotagrm.notifications.OperationalMonitoringService
import com.grm.rotagrm.notifications.OperationalRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** One process-wide engine: Activity, service and Worker share cache, mutex,
 * maintenance baseline, settings and notification deduplication. */
class OperationalApplication : Application() {
    val monitor: OperationalViewModel by lazy { OperationalViewModel(this) }
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        if (OperationalMonitoringService.enabled(this)) OperationalRefreshWorker.schedule(this)
        val connected = mutableSetOf<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!usable) connected.remove(network)
                else if (connected.add(network) && OperationalMonitoringService.enabled(this@OperationalApplication))
                    recoveryScope.launch { monitor.refresh() }
            }
            override fun onLost(network: Network) { connected.remove(network) }
        }
        // Observe actual connectivity; no FGS start or alarm from the background.
        // WorkManager handles process/reboot survival when this process is absent.
        try { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback) }
        catch (_: RuntimeException) { /* Scheduled/manual refresh remains available. */ }
    }
}