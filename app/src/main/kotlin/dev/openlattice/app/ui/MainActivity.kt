package dev.openlattice.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.openlattice.app.ui.add.AddPrinterScreen
import dev.openlattice.app.ui.camera.CameraScreen
import dev.openlattice.app.ui.printer.PrinterScreen
import dev.openlattice.app.ui.printers.PrinterListScreen
import dev.openlattice.app.ui.theme.OpenLatticeTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** A file shared into the app, waiting for the user to choose the printer to send it to. */
object PendingShare {
    val uri = MutableStateFlow<Uri?>(null)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            OpenLatticeTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "printers") {
                    composable("printers") {
                        PrinterListScreen(
                            onAdd = { nav.navigate("add") },
                            onOpen = { id -> nav.navigate("printer/$id") },
                        )
                    }
                    composable("add") {
                        AddPrinterScreen(onDone = { id ->
                            nav.popBackStack()
                            if (id != null) nav.navigate("printer/$id")
                        })
                    }
                    composable("printer/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                        PrinterScreen(
                            printerId = it.arguments!!.getString("id")!!,
                            onBack = { nav.popBackStack() },
                            onCamera = { id -> nav.navigate("camera/$id") },
                            onRemoved = { nav.popBackStack("printers", inclusive = false) },
                        )
                    }
                    composable("camera/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                        CameraScreen(printerId = it.arguments!!.getString("id")!!, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) PendingShare.uri.value = uri
    }
}
