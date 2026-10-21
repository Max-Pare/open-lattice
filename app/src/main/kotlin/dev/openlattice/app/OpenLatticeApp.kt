package dev.openlattice.app

import android.app.Application
import dev.openlattice.app.data.PrinterConnections
import dev.openlattice.app.data.PrinterStore

class OpenLatticeApp : Application() {
    lateinit var store: PrinterStore
        private set
    lateinit var connections: PrinterConnections
        private set

    override fun onCreate() {
        super.onCreate()
        store = PrinterStore(this)
        connections = PrinterConnections(store)
    }
}
