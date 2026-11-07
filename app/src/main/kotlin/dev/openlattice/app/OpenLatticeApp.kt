package dev.openlattice.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.openlattice.app.data.Appearance
import dev.openlattice.app.data.AppearanceStore
import dev.openlattice.app.data.PrinterConnections
import dev.openlattice.app.data.PrinterStore
import dev.openlattice.app.monitor.PrintCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class OpenLatticeApp : Application() {
    lateinit var store: PrinterStore
        private set
    lateinit var connections: PrinterConnections
        private set
    lateinit var appearanceStore: AppearanceStore
        private set
    /** Current look; null until read from disk (a few ms after start). */
    lateinit var appearance: StateFlow<Appearance?>
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        store = PrinterStore(this)
        connections = PrinterConnections(store)
        appearanceStore = AppearanceStore(this)
        appearance = appearanceStore.appearance.stateIn(scope, SharingStarted.Eagerly, null)
        PrintCheckWorker.schedule(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = connections.setForeground(true)
            override fun onStop(owner: LifecycleOwner) = connections.setForeground(false)
        })
    }
}
