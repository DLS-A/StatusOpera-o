package com.grm.rotagrm

import android.app.Application

/** One process-wide engine: Activity, service and Worker share cache, mutex,
 * maintenance baseline, settings and notification deduplication. */
class OperationalApplication : Application() {
    val monitor: OperationalViewModel by lazy { OperationalViewModel(this) }
}