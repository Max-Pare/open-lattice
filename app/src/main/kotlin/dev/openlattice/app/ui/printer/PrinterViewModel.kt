package dev.openlattice.app.ui.printer

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.openlattice.app.data.SavedPrinter
import dev.openlattice.app.monitor.PrintMonitorService
import dev.openlattice.app.data.UriUploadSource
import dev.openlattice.app.ui.PendingShare
import dev.openlattice.app.ui.app
import dev.openlattice.core.HistoryEntry
import dev.openlattice.core.PrinterCommand
import dev.openlattice.core.PrinterDriver
import dev.openlattice.core.PrinterFile
import dev.openlattice.core.PrinterInfo
import dev.openlattice.core.PrinterState
import dev.openlattice.core.Storage
import dev.openlattice.core.StorageInfo
import dev.openlattice.core.UploadProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FilesUi(
    val storage: Storage = Storage.LOCAL,
    val files: List<PrinterFile> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val space: StorageInfo? = null,
)

data class HistoryUi(val entries: List<HistoryEntry> = emptyList(), val loading: Boolean = false, val error: String? = null)

data class UploadUi(val fileName: String, val progress: UploadProgress?, val done: Boolean = false, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class PrinterViewModel(application: Application, private val printerId: String) : AndroidViewModel(application) {
    private val app = application.app
    private val driverFlow = MutableStateFlow<PrinterDriver?>(null)

    val printer = MutableStateFlow<SavedPrinter?>(null)

    val state: StateFlow<PrinterState> = driverFlow
        .flatMapLatest { it?.state ?: flowOf(PrinterState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PrinterState())

    val info: StateFlow<PrinterInfo?> = driverFlow
        .flatMapLatest { it?.info ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _files = MutableStateFlow(FilesUi())
    val files: StateFlow<FilesUi> = _files.asStateFlow()

    private val _history = MutableStateFlow(HistoryUi())
    val history: StateFlow<HistoryUi> = _history.asStateFlow()

    private val _upload = MutableStateFlow<UploadUi?>(null)
    val upload: StateFlow<UploadUi?> = _upload.asStateFlow()
    private var uploadJob: Job? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** Thumbnail cache: file name -> image (null = none / failed). */
    val thumbnails = mutableStateMapOf<String, ImageBitmap?>()

    private var cameraUrlCache: String? = null

    private val _dismissedErrors = MutableStateFlow<Set<Int>>(emptySet())
    /** Error codes the user hid; a code shows again once it clears and recurs. */
    val dismissedErrors: StateFlow<Set<Int>> = _dismissedErrors.asStateFlow()

    fun dismissError(code: Int) {
        _dismissedErrors.value = _dismissedErrors.value + code
    }

    init {
        viewModelScope.launch {
            val p = app.store.get(printerId) ?: return@launch
            printer.value = p
            driverFlow.value = app.connections.acquire(p)
            launch {
                state.collect { s ->
                    if (s.machine.isJobActive) PrintMonitorService.start(getApplication())
                    val present = s.exceptions.map { it.code }.toSet()
                    if (!present.containsAll(_dismissedErrors.value)) _dismissedErrors.value = _dismissedErrors.value intersect present
                }
            }
            // A file shared into the app while choosing this printer.
            PendingShare.uri.value?.let { uri ->
                PendingShare.uri.value = null
                upload(uri)
            }
        }
    }

    private suspend fun driver(): PrinterDriver = driverFlow.filterNotNull().first()

    fun send(command: PrinterCommand, success: String? = null) {
        viewModelScope.launch {
            try {
                driver().send(command)
                success?.let { _messages.emit(it) }
            } catch (e: Exception) {
                _messages.emit(e.message ?: "Command failed")
            }
        }
    }

    fun retryConnection() = app.connections.reconnect(printerId)

    fun loadFiles(storage: Storage = _files.value.storage) {
        _files.value = _files.value.copy(storage = storage, loading = true, error = null)
        viewModelScope.launch {
            _files.value = try {
                val d = driver()
                val files = d.listFiles(storage).sortedByDescending { it.createdAtEpochSec ?: 0 }
                val space = if (storage == Storage.LOCAL) runCatching { d.storageInfo() }.getOrNull() else null
                FilesUi(storage, files, space = space)
            } catch (e: Exception) {
                FilesUi(storage, emptyList(), error = e.message ?: "Could not list files")
            }
        }
    }

    fun loadThumbnail(storage: Storage, name: String) {
        if (thumbnails.containsKey(name)) return
        thumbnails[name] = null
        viewModelScope.launch {
            val bytes = runCatching { driver().thumbnail(storage, name) }.getOrNull() ?: return@launch
            val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            thumbnails[name] = bmp
        }
    }

    fun deleteFile(storage: Storage, name: String) {
        viewModelScope.launch {
            try {
                driver().deleteFiles(storage, listOf(name))
                _messages.emit("Deleted $name")
                loadFiles(storage)
            } catch (e: Exception) {
                _messages.emit(e.message ?: "Delete failed")
            }
        }
    }

    fun loadHistory() {
        _history.value = _history.value.copy(loading = true, error = null)
        viewModelScope.launch {
            _history.value = try {
                HistoryUi(driver().history())
            } catch (e: Exception) {
                HistoryUi(error = e.message ?: "Could not load history")
            }
        }
    }

    fun deleteHistory(taskId: String) {
        viewModelScope.launch {
            try {
                driver().deleteHistory(listOf(taskId))
                loadHistory()
            } catch (e: Exception) {
                _messages.emit(e.message ?: "Delete failed")
            }
        }
    }

    fun upload(uri: Uri) {
        if (uploadJob?.isActive == true) {
            viewModelScope.launch { _messages.emit("An upload is already running") }
            return
        }
        uploadJob = viewModelScope.launch {
            val source = try {
                withContext(Dispatchers.IO) { UriUploadSource.from(getApplication(), uri) }
            } catch (e: Exception) {
                _messages.emit(e.message ?: "Cannot read the file")
                return@launch
            }
            _upload.value = UploadUi(source.name, null)
            try {
                driver().upload(source).collect { _upload.value = UploadUi(source.name, it) }
                _upload.value = _upload.value?.copy(done = true)
                loadFiles(Storage.LOCAL)
            } catch (e: Exception) {
                _upload.value = _upload.value?.copy(error = e.message ?: "Upload failed")
            }
        }
    }

    fun cancelUpload() {
        uploadJob?.cancel()
        _upload.value = null
    }

    fun dismissUpload() {
        _upload.value = null
    }

    suspend fun cameraUrl(): String? {
        cameraUrlCache?.let { return it }
        return runCatching { driver().cameraUrl() }.getOrNull()?.also { cameraUrlCache = it }
    }

    fun rename(name: String) {
        viewModelScope.launch {
            try {
                driver().send(PrinterCommand.Rename(name))
                printer.value?.let { app.store.upsert(it.copy(name = name)); printer.value = it.copy(name = name) }
                _messages.emit("Renamed")
            } catch (e: Exception) {
                _messages.emit(e.message ?: "Rename failed")
            }
        }
    }

    fun updateConnection(host: String, accessCode: String) {
        val p = printer.value ?: return
        viewModelScope.launch {
            val updated = p.copy(host = host.trim(), accessCode = accessCode.trim())
            app.store.upsert(updated)
            app.connections.release(printerId)
            printer.value = updated
            driverFlow.value = app.connections.acquire(updated)
            cameraUrlCache = null
        }
    }

    fun remove(onRemoved: () -> Unit) {
        viewModelScope.launch {
            app.connections.release(printerId)
            app.connections.forget(printerId)
            driverFlow.value = null
            app.store.remove(printerId)
            onRemoved()
        }
    }

    override fun onCleared() {
        if (driverFlow.value != null) app.connections.release(printerId)
    }
}
