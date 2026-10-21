package dev.openlattice.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.openlattice.app.data.PrinterConnections
import dev.openlattice.app.data.PrinterStore
import dev.openlattice.app.monitor.PrintCheckWorker

class OpenLatticeApp : Application() {
    lateinit var store: PrinterStore
        private set
    lateinit var connections: PrinterConnections
        private set

    override fun onCreate() {
        super.onCreate()
        store = PrinterStore(this)
        connections = PrinterConnections(store)
        PrintCheckWorker.schedule(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = connections.setForeground(true)
            override fun onStop(owner: LifecycleOwner) = connections.setForeground(false)
        })
    }
}
