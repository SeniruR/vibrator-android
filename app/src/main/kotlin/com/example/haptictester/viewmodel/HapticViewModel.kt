package com.example.haptictester.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.example.haptictester.haptic.AudioHapticController
import com.example.haptictester.haptic.AudioHapticController.HapticPulseDebug
import com.example.haptictester.haptic.CompareAlgorithm
import com.example.haptictester.haptic.CompareSlotState
import com.example.haptictester.haptic.DetectedEvent
import com.example.haptictester.haptic.DetectedEvents
import com.example.haptictester.haptic.HapticFolderMatcher
import com.example.haptictester.haptic.HapticController
import com.example.haptictester.haptic.HapticTrackFormat
import com.example.haptictester.haptic.VideoHapticController
import com.example.haptictester.haptic.WavHapticParser
import com.example.haptictester.haptic.ThrottleGate
import com.example.haptictester.sync.DesktopSyncServer
import com.example.haptictester.sync.RemoteSyncCommand

data class AudioDebugPulse(
    val sampleIndex: Int,
    val level: Int,
    val onMs: Long,
    val offMs: Long,
    val amplitude: Int,
    val isBass: Boolean,
    val isDrum: Boolean,
    val isSustained: Boolean,
)

data class AudioDebugFrame(
    val level: Int,
    val pulse: AudioDebugPulse? = null,
)

class HapticViewModel(application: Application) : AndroidViewModel(application) {
    private val haptic = HapticController(application.applicationContext)
    private val audioHaptic = AudioHapticController(application.applicationContext, haptic)
    private val videoHaptic = VideoHapticController(application.applicationContext, haptic)
    private val gate = ThrottleGate(100L)
    private val syncPrefs = application.getSharedPreferences("haptic_sync_prefs", Context.MODE_PRIVATE)
    private val syncServer = DesktopSyncServer(
        onCommand = ::onRemoteSyncCommand,
        onReceiveFile = ::receiveUploadedFile,
        onFinishBundleUpload = ::finishBundleUpload,
        onGetBundleManifest = ::getBundleManifest,
        onOpenBundleFileStream = ::openBundleFileStream,
    )
    val syncStatus = syncServer.status

    private val _remoteSyncCommands = MutableSharedFlow<RemoteSyncCommand>(extraBufferCapacity = 32)
    val remoteSyncCommands = _remoteSyncCommands.asSharedFlow()

    @Volatile
    private var lastSyncPositionMs: Long = 0L

    @Volatile
    private var lastSyncDurationMs: Long = 0L

    @Volatile
    private var lastSyncSpeed: Float = 1.0f

    @Volatile
    private var lastHeartbeatElapsedMs: Long = 0L

    private var liveUpdateJob: Job? = null
    private var analysisJob: Job? = null
    private var sensitivityReanalyzeJob: Job? = null
    private var hapticTrackLoadJob: Job? = null
    private var loadedAudioUri: Uri? = null
    private var loadedVideoUri: Uri? = null
    private var analysisToken: Long = 0L
    private var hapticTrackToken: Long = 0L
    private val compareLoadJobs = mutableMapOf<CompareAlgorithm, Job>()
    private val compareLoadTokens = mutableMapOf<CompareAlgorithm, Long>()

    private val _compareSlots = MutableStateFlow(defaultCompareSlots())
    val compareSlots = _compareSlots.asStateFlow()

    private val _activeCompareAlgorithm = MutableStateFlow<CompareAlgorithm?>(null)
    val activeCompareAlgorithm = _activeCompareAlgorithm.asStateFlow()

    private val _amplitude = MutableStateFlow(255)
    val amplitude = _amplitude.asStateFlow()

    private val _duty = MutableStateFlow(50)
    val duty = _duty.asStateFlow()

    private val _periodMs = MutableStateFlow(200)
    val periodMs = _periodMs.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting = _isTesting.asStateFlow()

    private val _hasAmplitude = MutableStateFlow(haptic.hasAmplitudeControl())
    val hasAmplitude = _hasAmplitude.asStateFlow()

    private val _hasVibrator = MutableStateFlow(haptic.hasVibrator())
    val hasVibrator = _hasVibrator.asStateFlow()

    private val _hasPrimitives = MutableStateFlow(haptic.hasScalablePrimitives())
    val hasPrimitives = _hasPrimitives.asStateFlow()

    private val _selectedAudioName = MutableStateFlow<String?>(null)
    val selectedAudioName = _selectedAudioName.asStateFlow()

    private val _audioPlaying = MutableStateFlow(false)
    val audioPlaying = _audioPlaying.asStateFlow()

    private val _audioVibrateEnabled = MutableStateFlow(true)
    val audioVibrateEnabled = _audioVibrateEnabled.asStateFlow()

    private val _audioLevel = MutableStateFlow(0)
    val audioLevel = _audioLevel.asStateFlow()

    private val _audioError = MutableStateFlow<String?>(null)
    val audioError = _audioError.asStateFlow()

    private val _audioAnalyzing = MutableStateFlow(false)
    val audioAnalyzing = _audioAnalyzing.asStateFlow()

    private val _audioAnalysisReady = MutableStateFlow(false)
    val audioAnalysisReady = _audioAnalysisReady.asStateFlow()

    private val _audioSensitivityLevel = MutableStateFlow(7)
    val audioSensitivityLevel = _audioSensitivityLevel.asStateFlow()

    private val _audioDebugFrames = MutableStateFlow<List<AudioDebugFrame>>(emptyList())
    val audioDebugFrames = _audioDebugFrames.asStateFlow()

    private val _selectedVideoName = MutableStateFlow<String?>(null)
    val selectedVideoName = _selectedVideoName.asStateFlow()

    private val _selectedHapticTrackName = MutableStateFlow<String?>(null)
    val selectedHapticTrackName = _selectedHapticTrackName.asStateFlow()

    private val _hapticTrackFormat = MutableStateFlow<HapticTrackFormat?>(null)
    val hapticTrackFormat = _hapticTrackFormat.asStateFlow()

    private val _hapticTrackLoading = MutableStateFlow(false)
    val hapticTrackLoading = _hapticTrackLoading.asStateFlow()

    private val _videoPlaying = MutableStateFlow(false)
    val videoPlaying = _videoPlaying.asStateFlow()

    private val _videoError = MutableStateFlow<String?>(null)
    val videoError = _videoError.asStateFlow()

    private val _videoMapWindows = MutableStateFlow(0)
    val videoMapWindows = _videoMapWindows.asStateFlow()

    private val _videoMapWindowSizeMs = MutableStateFlow(0L)
    val videoMapWindowSizeMs = _videoMapWindowSizeMs.asStateFlow()

    private val _hapticTrackDurationMs = MutableStateFlow(0L)
    val hapticTrackDurationMs = _hapticTrackDurationMs.asStateFlow()

    private val _demoVideoUri = MutableStateFlow<Uri?>(null)
    val demoVideoUri = _demoVideoUri.asStateFlow()

    private val _folderSummary = MutableStateFlow<String?>(null)
    val folderSummary = _folderSummary.asStateFlow()

    private val _detectedEvents = MutableStateFlow<List<DetectedEvent>>(emptyList())
    val detectedEvents = _detectedEvents.asStateFlow()

    private val _selectedEventIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedEventIds = _selectedEventIds.asStateFlow()

    private var loadedEventsRawJson: String? = null

    private val _syncRootFolderName = MutableStateFlow<String?>(null)
    val syncRootFolderName = _syncRootFolderName.asStateFlow()

    private val _activeBundleId = MutableStateFlow<String?>(null)
    val activeBundleId = _activeBundleId.asStateFlow()

    private val _availableBundles = MutableStateFlow<List<String>>(emptyList())
    val availableBundles = _availableBundles.asStateFlow()

    private val _desktopLibraryBundles = MutableStateFlow<List<String>>(emptyList())
    val desktopLibraryBundles = _desktopLibraryBundles.asStateFlow()

    private val _transferStatus = MutableStateFlow<String?>(null)
    val transferStatus = _transferStatus.asStateFlow()

    // Incremented whenever the app needs the user to pick a Library Folder (UI opens the folder picker)
    private val _libraryFolderPickRequest = MutableStateFlow(0)
    val libraryFolderPickRequest = _libraryFolderPickRequest.asStateFlow()

    // Actions to resume automatically once the user has picked a Library Folder
    private var pendingLibrarySyncAfterPick = false
    private var pendingSaveNameAfterPick: String? = null

    init {
        pushAudioTuning()
        restoreSyncStorageFolder()
    }

    /**
     * Returns the user-selected Library Folder, or null if none is selected / it is no longer accessible.
     * There is intentionally NO hidden fallback storage: all bundles live only in this visible folder.
     */
    private fun libraryRootOrNull(): DocumentFile? {
        val app = getApplication<Application>()
        val raw = syncPrefs.getString("sync_root_tree_uri", null)
        if (raw.isNullOrBlank()) return null
        val doc = runCatching { DocumentFile.fromTreeUri(app, Uri.parse(raw)) }.getOrNull() ?: return null
        return doc.takeIf { it.canRead() && it.canWrite() }
    }

    private fun askForLibraryFolder(message: String) {
        _transferStatus.value = message
        _libraryFolderPickRequest.value = _libraryFolderPickRequest.value + 1
    }

    fun startDesktopSync(port: Int = DesktopSyncServer.DEFAULT_PORT) {
        refreshAvailableBundles()
        syncServer.start(port)
    }

    fun stopDesktopSync() {
        syncServer.stop()
    }

    fun refreshSyncIps() {
        syncServer.refreshLocalIps()
    }

    fun setDesktopLibraryBundles(bundles: List<String>) {
        _desktopLibraryBundles.value = bundles.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    }

    fun requestLibrarySyncFromAndroid() {
        val status = syncStatus.value
        if (!status.running || status.connectedClientIp == null) {
            _transferStatus.value = "Connect to Haptic Studio Desktop first to sync libraries."
            return
        }
        if (_syncRootFolderName.value == null || libraryRootOrNull() == null) {
            pendingLibrarySyncAfterPick = true
            askForLibraryFolder("Select a Library Folder on the phone before syncing.")
            return
        }
        _transferStatus.value = "Requesting library sync with Desktop…"
        syncServer.broadcastJson(JSONObject().put("type", "REQUEST_LIBRARY_SYNC"))
    }

    fun clearLoadedMedia(broadcastToDesktop: Boolean = false) {
        stopVideo()
        loadedVideoUri = null
        loadedEventsRawJson = null
        _demoVideoUri.value = null
        _selectedVideoName.value = null
        _selectedHapticTrackName.value = null
        _hapticTrackFormat.value = null
        _hapticTrackLoading.value = false
        _videoMapWindows.value = 0
        _videoMapWindowSizeMs.value = 0L
        _hapticTrackDurationMs.value = 0L
        _compareSlots.value = defaultCompareSlots()
        _activeCompareAlgorithm.value = null
        _detectedEvents.value = emptyList()
        _selectedEventIds.value = emptySet()
        _folderSummary.value = null
        _videoError.value = null
        _activeBundleId.value = null
        lastSyncPositionMs = 0L
        lastSyncDurationMs = 0L
        if (broadcastToDesktop) {
            val status = syncStatus.value
            if (status.running && status.connectedClientIp != null) {
                syncServer.broadcastJson(JSONObject().put("type", "COMMAND_CLEAR_MEDIA"))
            }
        }
    }

    fun setSyncStorageFolder(treeUri: Uri) {
        val app = getApplication<Application>()
        takePersistableTreePermission(app, treeUri)
        syncPrefs.edit().putString("sync_root_tree_uri", treeUri.toString()).apply()
        val folderName = DocumentFile.fromTreeUri(app, treeUri)?.name ?: "Selected Folder"
        _syncRootFolderName.value = folderName
        if (_transferStatus.value?.startsWith("Select a Library Folder") == true ||
            _transferStatus.value?.startsWith("Haptic Studio") == true
        ) {
            _transferStatus.value = null
        }

        val resumeSync = pendingLibrarySyncAfterPick
        val resumeSaveName = pendingSaveNameAfterPick
        pendingLibrarySyncAfterPick = false
        pendingSaveNameAfterPick = null

        refreshAvailableBundles {
            // Runs after the new folder's bundle list has been sent to Desktop
            if (resumeSaveName != null) {
                saveCurrentFilesToLibrary(resumeSaveName)
            } else if (resumeSync) {
                requestLibrarySyncFromAndroid()
            }
        }
    }

    private fun restoreSyncStorageFolder() {
        val app = getApplication<Application>()
        val rawUri = syncPrefs.getString("sync_root_tree_uri", null)
        if (!rawUri.isNullOrBlank()) {
            val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
            if (uri != null) {
                val doc = runCatching { DocumentFile.fromTreeUri(app, uri) }.getOrNull()
                if (doc != null && doc.canRead()) {
                    _syncRootFolderName.value = doc.name ?: "Selected Folder"
                }
            }
        }
        refreshAvailableBundles()
    }

    fun refreshAvailableBundles(afterRefresh: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val names = linkedSetOf<String>()

            // Only the user-selected Library Folder is ever listed (no hidden fallback storage)
            libraryRootOrNull()?.listFiles()?.forEach { child ->
                val name = child.name?.trim()
                if (child.isDirectory && !name.isNullOrEmpty()) {
                    names += name
                }
            }

            _availableBundles.value = names.sortedBy { it.lowercase() }
            broadcastSyncState(isHeartbeat = false)
            afterRefresh?.invoke()
        }
    }

    fun deleteBundleById(bundleId: String) {
        val trimmed = bundleId.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            libraryRootOrNull()?.listFiles()?.firstOrNull {
                it.isDirectory && it.name.equals(trimmed, ignoreCase = true)
            }?.delete()

            if (_activeBundleId.value.equals(trimmed, ignoreCase = true)) {
                _activeBundleId.value = null
            }
            refreshAvailableBundles()
        }
    }

    fun saveCurrentFilesToLibrary(rawBundleName: String) {
        val sanitized = rawBundleName.trim()
            .replace('/', '_')
            .replace('\\', '_')
            .replace("..", "_")
            .trim()
        if (sanitized.isEmpty()) {
            _videoError.value = "Please enter a valid bundle name."
            return
        }
        val videoUri = loadedVideoUri
        if (videoUri == null) {
            _videoError.value = "Load a video or folder first before saving to the Library."
            return
        }

        if (_syncRootFolderName.value == null || libraryRootOrNull() == null) {
            pendingSaveNameAfterPick = sanitized
            askForLibraryFolder("Select a Library Folder first — '$sanitized' will be saved there.")
            return
        }

        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _transferStatus.value = "Saving '$sanitized' to Library…"
                val rootDoc = libraryRootOrNull()
                    ?: throw IllegalStateException("No Library Folder selected on the phone.")

                val videoFileName = (_selectedVideoName.value?.takeIf { it.isNotBlank() } ?: "video.mp4")
                val eventsJsonText = loadedEventsRawJson ?: serializeDetectedEventsJson(_detectedEvents.value)

                val bundleDir = rootDoc.listFiles().firstOrNull {
                    it.isDirectory && it.name.equals(sanitized, ignoreCase = true)
                } ?: rootDoc.createDirectory(sanitized)
                    ?: throw IllegalStateException("Could not create folder '$sanitized' in Library")

                // 1. Copy Video
                copyUriToDocumentDir(app, videoUri, bundleDir, videoFileName)

                // 2. Copy loaded A-E WAV slots
                for (algo in CompareAlgorithm.all) {
                    val slot = _compareSlots.value[algo] ?: continue
                    val slotUri = slot.uri ?: continue
                    val wavName = slot.fileName?.takeIf { it.endsWith(".wav", ignoreCase = true) }
                        ?: when (algo) {
                            CompareAlgorithm.A -> "algorithm_a_perception_mapping.wav"
                            CompareAlgorithm.B -> "algorithm_b_frequency_shifting.wav"
                            CompareAlgorithm.C -> "algorithm_c_pitch_matching.wav"
                            CompareAlgorithm.D -> "algorithm_d_haptic_gen.wav"
                            CompareAlgorithm.E -> "algorithm_e_rule_based.wav"
                        }
                    copyUriToDocumentDir(app, slotUri, bundleDir, wavName)
                }

                // 3. Write events.json
                if (eventsJsonText.isNotBlank()) {
                    bundleDir.findFile("events.json")?.delete()
                    val evDoc = bundleDir.createFile("application/json", "events.json")
                    if (evDoc != null) {
                        app.contentResolver.openOutputStream(evDoc.uri, "w")?.use { out ->
                            out.write(eventsJsonText.toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                    }
                }

                _activeBundleId.value = sanitized
                _folderSummary.value = "$sanitized (Saved in Library)"
                _transferStatus.value = "Saved '$sanitized' to Library."
                refreshAvailableBundles()
                viewModelScope.launch {
                    delay(3500)
                    if (_transferStatus.value == "Saved '$sanitized' to Library.") {
                        _transferStatus.value = null
                    }
                }
            } catch (err: Throwable) {
                _transferStatus.value = "Save failed: ${err.message ?: "Error"}"
            }
        }
    }

    private fun copyUriToDocumentDir(app: Application, sourceUri: Uri, targetDir: DocumentFile, fileName: String) {
        val existing = targetDir.findFile(fileName)
        if (existing != null && existing.uri == sourceUri) return
        existing?.delete()
        val created = targetDir.createFile(guessMimeType(fileName), fileName)
            ?: throw IllegalStateException("Could not create '$fileName'")
        app.contentResolver.openInputStream(sourceUri)?.use { input ->
            app.contentResolver.openOutputStream(created.uri, "w")?.use { output ->
                input.copyTo(output, 64 * 1024)
                output.flush()
            }
        }
    }

    private fun copyUriToFile(app: Application, sourceUri: Uri, targetFile: File) {
        if (sourceUri.scheme == "file" && sourceUri.path == targetFile.absolutePath) return
        app.contentResolver.openInputStream(sourceUri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output, 64 * 1024)
                output.flush()
            }
        }
    }

    private fun serializeDetectedEventsJson(events: List<DetectedEvent>): String {
        if (events.isEmpty()) return ""
        val arr = JSONArray()
        events.forEach { ev ->
            arr.put(
                JSONObject()
                    .put("event_id", ev.id)
                    .put("label", ev.label)
                    .put("category", ev.category)
                    .put("start_sec", ev.startMs / 1000.0)
                    .put("end_sec", ev.endMs / 1000.0)
            )
        }
        return JSONObject().put("events", arr).toString(2)
    }

    private fun requireLibraryRootForTransfer(): DocumentFile {
        return libraryRootOrNull() ?: run {
            pendingLibrarySyncAfterPick = true
            askForLibraryFolder("Haptic Studio wants to sync. Select a Library Folder on the phone first.")
            throw IllegalStateException(
                "No Library Folder selected on the phone. Select one on the phone, then sync again."
            )
        }
    }

    private fun getBundleManifest(bundleId: String): Result<JSONArray> {
        return runCatching {
            val arr = JSONArray()
            val rootDoc = requireLibraryRootForTransfer()

            val bundleDir = rootDoc.listFiles().firstOrNull {
                it.isDirectory && it.name.equals(bundleId, ignoreCase = true)
            } ?: throw IllegalArgumentException("Bundle '$bundleId' not found on phone")

            val seenNames = mutableSetOf<String>()
            bundleDir.listFiles().forEach { child ->
                val name = child.name
                if (child.isFile && !name.isNullOrBlank()) {
                    seenNames += name.lowercase()
                    arr.put(
                        JSONObject()
                            .put("name", name)
                            .put("size", child.length())
                    )
                }
            }
            if ("events.json" !in seenNames) {
                val nestedEvents = findEventsJson(bundleDir)
                if (nestedEvents != null) {
                    arr.put(
                        JSONObject()
                            .put("name", "events.json")
                            .put("size", nestedEvents.length())
                    )
                }
            }
            arr
        }
    }

    private fun openBundleFileStream(bundleId: String, fileName: String): Result<Pair<Long, InputStream>> {
        val app = getApplication<Application>()
        return runCatching {
            _transferStatus.value = "Sending $bundleId / $fileName to PC…"
            val rootDoc = requireLibraryRootForTransfer()

            val bundleDir = rootDoc.listFiles().firstOrNull {
                it.isDirectory && it.name.equals(bundleId, ignoreCase = true)
            } ?: throw IllegalArgumentException("Bundle '$bundleId' not found")

            val targetDoc = bundleDir.listFiles().firstOrNull {
                it.isFile && it.name.equals(fileName, ignoreCase = true)
            } ?: if (fileName.equals("events.json", ignoreCase = true)) {
                findEventsJson(bundleDir)
            } else {
                null
            } ?: throw IllegalArgumentException("File '$fileName' not found in '$bundleId'")

            val stream = app.contentResolver.openInputStream(targetDoc.uri)
                ?: throw IllegalStateException("Cannot open '$fileName'")
            targetDoc.length() to stream
        }
    }

    private fun receiveUploadedFile(
        bundleId: String,
        fileName: String,
        contentLength: Long,
        input: InputStream,
    ): Result<Unit> {
        val app = getApplication<Application>()
        return runCatching {
            // Never store into hidden app storage: a visible Library Folder is required
            val rootDoc = requireLibraryRootForTransfer()
            _transferStatus.value = "Syncing $bundleId / $fileName…"

            val bundleDir = rootDoc.findFile(bundleId)?.takeIf { it.isDirectory }
                ?: rootDoc.createDirectory(bundleId)
                ?: throw IllegalStateException("Could not create bundle directory '$bundleId' in Library Folder")

            // Delete existing file with the same name so SAF does not append (1)
            bundleDir.findFile(fileName)?.delete()
            val mime = guessMimeType(fileName)
            val targetFile = bundleDir.createFile(mime, fileName)
                ?: throw IllegalStateException("Could not create file '$fileName' in '$bundleId'")

            app.contentResolver.openOutputStream(targetFile.uri, "w")?.use { output ->
                copyStreamExact(input, output, contentLength, bundleId, fileName)
            } ?: throw IllegalStateException("Unable to open output stream for '$fileName'")
        }.onFailure { err ->
            if (_transferStatus.value?.startsWith("Haptic Studio wants to sync") != true) {
                _transferStatus.value = "Sync failed: ${err.message ?: "Error"}"
            }
        }
    }


    private fun copyStreamExact(
        input: InputStream,
        output: OutputStream,
        contentLength: Long,
        bundleId: String,
        fileName: String,
    ) {
        val buffer = ByteArray(64 * 1024)
        var remaining = contentLength
        var received = 0L
        var lastReportMs = 0L
        while (remaining > 0L) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) throw EOFException("Unexpected end of upload stream for $fileName")
            output.write(buffer, 0, read)
            remaining -= read
            received += read
            val now = SystemClock.elapsedRealtime()
            if (contentLength > 0 && now - lastReportMs >= 150L) {
                lastReportMs = now
                val pct = ((received * 100L) / contentLength).coerceIn(0L, 100L)
                _transferStatus.value = "Syncing $bundleId / $fileName ($pct%)"
            }
        }
        output.flush()
    }

    private fun guessMimeType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".avi") -> "video/x-msvideo"
            lower.endsWith(".wav") -> "audio/x-wav"
            lower.endsWith(".json") -> "application/json"
            else -> "application/octet-stream"
        }
    }

    private fun finishBundleUpload(bundleId: String, autoLoad: Boolean): Result<Unit> {
        return runCatching {
            _transferStatus.value = "Synced '$bundleId'"
            refreshAvailableBundles()
            if (autoLoad) {
                onRemoteSyncCommand(RemoteSyncCommand.LoadBundle(bundleId))
            }
            viewModelScope.launch {
                delay(3500)
                if (_transferStatus.value == "Synced '$bundleId'" || _transferStatus.value?.startsWith("Sending ") == true) {
                    _transferStatus.value = null
                }
            }
        }
    }

    fun setSelectedEventIds(ids: Set<String>, broadcast: Boolean = true) {
        val validIds = _detectedEvents.value.map { it.id }.toSet()
        val filtered = if (validIds.isEmpty()) ids else ids.intersect(validIds)
        _selectedEventIds.value = filtered
        if (broadcast) {
            broadcastSyncState(isHeartbeat = false)
        }
    }

    fun toggleSelectedEvent(eventId: String, broadcast: Boolean = true): Set<String> {
        val current = _selectedEventIds.value
        val next = if (eventId in current) current - eventId else current + eventId
        _selectedEventIds.value = next
        if (broadcast) {
            broadcastSyncState(isHeartbeat = false)
        }
        return next
    }

    fun notifyPlayerStateChanged(
        positionMs: Long = lastSyncPositionMs,
        durationMs: Long = lastSyncDurationMs,
        speed: Float = lastSyncSpeed,
    ) {
        lastSyncPositionMs = positionMs.coerceAtLeast(0L)
        if (durationMs > 0L) lastSyncDurationMs = durationMs
        lastSyncSpeed = speed
        broadcastSyncState(isHeartbeat = false)
    }

    private fun onRemoteSyncCommand(command: RemoteSyncCommand) {
        viewModelScope.launch(Dispatchers.Main) {
            when (command) {
                is RemoteSyncCommand.RequestState -> {
                    broadcastSyncState(isHeartbeat = false)
                }
                is RemoteSyncCommand.UpdateDesktopLibrary -> {
                    setDesktopLibraryBundles(command.bundles)
                }
                else -> {
                    _remoteSyncCommands.tryEmit(command)
                }
            }
        }
    }

    fun broadcastSyncState(
        positionMs: Long = lastSyncPositionMs,
        durationMs: Long = lastSyncDurationMs,
        speed: Float = lastSyncSpeed,
        isHeartbeat: Boolean = false,
    ) {
        val status = syncStatus.value
        if (!status.running || status.connectedClientIp == null) return

        val readyAlgos = JSONArray()
        CompareAlgorithm.all.forEach { algo ->
            if (_compareSlots.value[algo]?.isReady == true) {
                readyAlgos.put(algo.id)
            }
        }

        val selectedEventsArray = JSONArray()
        _selectedEventIds.value.forEach { selectedEventsArray.put(it) }

        val availableBundlesArray = JSONArray()
        _availableBundles.value.forEach { availableBundlesArray.put(it) }

        val effectiveDuration = when {
            durationMs > 0L -> durationMs
            lastSyncDurationMs > 0L -> lastSyncDurationMs
            _hapticTrackDurationMs.value > 0L -> _hapticTrackDurationMs.value
            else -> 0L
        }

        val payload = JSONObject()
            .put("type", if (isHeartbeat) "HEARTBEAT" else "STATE_SYNC")
            .put("playing", _videoPlaying.value)
            .put("timeMs", positionMs.coerceAtLeast(0L))
            .put("durationMs", effectiveDuration)
            .put("speed", speed.toDouble())
            .put("activeAlgorithm", _activeCompareAlgorithm.value?.id ?: JSONObject.NULL)
            .put("readyAlgorithms", readyAlgos)
            .put("selectedEventIds", selectedEventsArray)
            .put("videoName", _selectedVideoName.value ?: JSONObject.NULL)

        if (!isHeartbeat) {
            payload.put("activeBundleId", _activeBundleId.value ?: JSONObject.NULL)
            payload.put("availableBundles", availableBundlesArray)
        }

        syncServer.broadcastJson(payload)
    }

    fun setAmplitude(v: Int) {
        _amplitude.value = v.coerceIn(0, 255)
        requestLiveUpdate()
    }

    fun setDuty(percent: Int) {
        _duty.value = percent.coerceIn(0, 100)
        requestLiveUpdate()
    }

    fun setPeriodMs(value: Int) {
        _periodMs.value = value.coerceIn(60, 1000)
        requestLiveUpdate()
    }

    fun startTest() {
        if (!_hasVibrator.value) return
        if (_audioPlaying.value) return
        if (!gate.canExecute()) return
        _isTesting.value = true
        requestLiveUpdate()
    }

    fun stopTest() {
        liveUpdateJob?.cancel()
        liveUpdateJob = null
        haptic.cancel()
        _isTesting.value = false
    }

    fun setAudioVibrateEnabled(enabled: Boolean) {
        _audioVibrateEnabled.value = enabled
        audioHaptic.setVibrateFromAudio(enabled)
    }

    fun setAudioSensitivityLevel(value: Int) {
        _audioSensitivityLevel.value = value.coerceIn(1, 10)
        pushAudioTuning()
        scheduleReanalyzeLoadedAudio()
    }

    fun loadAudio(uri: Uri) {
        val app = getApplication<Application>()
        loadedAudioUri = uri
        _audioDebugFrames.value = emptyList()
        _audioLevel.value = 0
        _audioAnalyzing.value = true
        _audioAnalysisReady.value = false
        takePersistableReadPermission(app, uri)

        val displayName = audioHaptic.load(
            uri = uri,
            vibrateFromAudio = _audioVibrateEnabled.value,
            onLevel = { level ->
                _audioLevel.value = level
                appendAudioHistory(level)
            },
            onPulse = { pulse ->
                appendPulseHistory(pulse)
            },
            onEnded = {
                _audioPlaying.value = false
            },
            onError = { message ->
                _audioError.value = message
            },
        )
        _selectedAudioName.value = displayName
        _audioError.value = null
        _audioPlaying.value = false

        startAnalysis(
            uri = uri,
            emptyMessage = "Pre-scan did not find a strong bass/drum pattern; live fallback remains available.",
        )
    }

    fun playAudio() {
        if (_audioAnalyzing.value) return
        stopTest()
        stopVideo()
        audioHaptic.play()
        _audioPlaying.value = true
    }

    fun pauseAudio() {
        audioHaptic.pause()
        _audioPlaying.value = false
        haptic.cancel()
    }

    fun stopAudio() {
        audioHaptic.stop()
        _audioPlaying.value = false
        _audioLevel.value = 0
        haptic.cancel()
    }

    fun loadBundledDemo() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val dir = withContext(Dispatchers.IO) {
                    unpackDemoAssets(app)
                } ?: return@launch
                val video = File(dir, "video.mp4")
                if (!video.exists()) return@launch

                _activeBundleId.value = null
                _compareSlots.value = defaultCompareSlots()
                _activeCompareAlgorithm.value = null

                val videoUri = Uri.fromFile(video)
                loadVideo(videoUri)
                _demoVideoUri.value = videoUri

                val slots = listOf(
                    CompareAlgorithm.A to "algorithm_a_perception_mapping.wav",
                    CompareAlgorithm.B to "algorithm_b_frequency_shifting.wav",
                    CompareAlgorithm.C to "algorithm_c_pitch_matching.wav",
                    CompareAlgorithm.D to "algorithm_d_haptic_gen.wav",
                    CompareAlgorithm.E to "algorithm_e_rule_based.wav",
                )
                for ((algorithm, name) in slots) {
                    val wav = File(dir, name)
                    if (wav.exists()) {
                        loadCompareSlot(algorithm, Uri.fromFile(wav))
                    }
                }
                _detectedEvents.value = withContext(Dispatchers.IO) {
                    runCatching {
                        app.assets.open("demo/events.json").bufferedReader().use { reader ->
                            val raw = reader.readText()
                            loadedEventsRawJson = raw
                            DetectedEvents.parse(raw)
                        }
                    }.getOrDefault(emptyList())
                }
                _selectedEventIds.value = emptySet()
                broadcastSyncState(isHeartbeat = false)
            } catch (throwable: Throwable) {
                _videoError.value = throwable.message ?: throwable.toString()
            }
        }
    }

    fun loadFolder(treeUri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                takePersistableTreePermission(app, treeUri)
                val root = DocumentFile.fromTreeUri(app, treeUri)
                    ?: throw IllegalArgumentException("Unable to open the selected folder")
                val folderLabel = root.name?.trim()?.takeIf { it.isNotEmpty() } ?: "custom_folder"
                // Loading a random folder outside the Library blanks the Desktop app until saved to Library & synced
                loadFolderDocument(root, folderLabel, isLibraryBundle = false)
                val status = syncStatus.value
                if (status.running && status.connectedClientIp != null) {
                    syncServer.broadcastJson(JSONObject().put("type", "COMMAND_CLEAR_MEDIA"))
                }
            } catch (throwable: Throwable) {
                _folderSummary.value = null
                _videoError.value = throwable.message ?: throwable.toString()
            }
        }
    }

    fun loadBundleById(bundleId: String) {
        val trimmed = bundleId.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                val rootDoc = withContext(Dispatchers.IO) { libraryRootOrNull() }
                if (rootDoc != null) {
                    val subDoc = withContext(Dispatchers.IO) {
                        rootDoc.listFiles().firstOrNull {
                            it.isDirectory && it.name.equals(trimmed, ignoreCase = true)
                        }
                    }
                    if (subDoc != null) {
                        loadFolderDocument(subDoc, subDoc.name ?: trimmed, isLibraryBundle = true)
                        return@launch
                    }
                }

                // Bundle is not in the phone's Library Folder -> blank phone media
                clearLoadedMedia(broadcastToDesktop = false)
                _videoError.value = "Bundle '$trimmed' is not in the phone's Library Folder. Open Library -> Sync Library with PC."
            } catch (throwable: Throwable) {
                _videoError.value = throwable.message ?: throwable.toString()
            }
        }
    }

    private suspend fun loadFolderDocument(root: DocumentFile, bundleId: String, isLibraryBundle: Boolean) {
        val app = getApplication<Application>()
        val (children, rawEventsPair) = withContext(Dispatchers.IO) {
            val files = root.listFiles().filter { it.isFile && !it.name.isNullOrBlank() }
            val rawAndParsed = findEventsJson(root)?.let { file ->
                app.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                    val raw = reader.readText()
                    raw to DetectedEvents.parse(raw)
                }
            }
            files to rawAndParsed
        }
        val byName = children.associateBy { it.name!! }
        val match = HapticFolderMatcher.match(byName.keys.toList())
        if (match.isEmpty) {
            _folderSummary.value = null
            _videoError.value = "No video or A–E WAVs found in '$bundleId'."
            return
        }

        _activeBundleId.value = if (isLibraryBundle) bundleId else null
        _folderSummary.value = "$bundleId (${match.summary()})"
        _videoError.value = null
        _compareSlots.value = defaultCompareSlots()
        _activeCompareAlgorithm.value = null

        match.videoName?.let { name ->
            val uri = byName[name]?.uri ?: return@let
            loadVideo(uri)
            _demoVideoUri.value = uri
        }
        for ((algorithm, name) in match.slotNames) {
            val uri = byName[name]?.uri ?: continue
            loadCompareSlot(algorithm, uri)
        }
        loadedEventsRawJson = rawEventsPair?.first
        _detectedEvents.value = rawEventsPair?.second.orEmpty()
        _selectedEventIds.value = emptySet()
        broadcastSyncState(isHeartbeat = false)
    }

    private suspend fun loadLocalFileBundle(dir: File, bundleId: String, isLibraryBundle: Boolean) {
        val (files, rawEventsPair) = withContext(Dispatchers.IO) {
            val childFiles = dir.listFiles()?.filter { it.isFile } ?: emptyList()
            val eventsFile = File(dir, "debug/context_detector/events.json").takeIf { it.exists() }
                ?: File(dir, "events.json").takeIf { it.exists() }
            val rawAndParsed = eventsFile?.let { f ->
                runCatching {
                    val raw = f.readText()
                    raw to DetectedEvents.parse(raw)
                }.getOrNull()
            }
            childFiles to rawAndParsed
        }
        val byName = files.associateBy { it.name }
        val match = HapticFolderMatcher.match(byName.keys.toList())
        if (match.isEmpty) {
            _folderSummary.value = null
            _videoError.value = "No video or A–E WAVs found in '$bundleId'."
            return
        }

        _activeBundleId.value = if (isLibraryBundle) bundleId else null
        _folderSummary.value = "$bundleId (${match.summary()})"
        _videoError.value = null
        _compareSlots.value = defaultCompareSlots()
        _activeCompareAlgorithm.value = null

        match.videoName?.let { name ->
            val file = byName[name] ?: return@let
            val uri = Uri.fromFile(file)
            loadVideo(uri)
            _demoVideoUri.value = uri
        }
        for ((algorithm, name) in match.slotNames) {
            val file = byName[name] ?: continue
            loadCompareSlot(algorithm, Uri.fromFile(file))
        }
        loadedEventsRawJson = rawEventsPair?.first
        _detectedEvents.value = rawEventsPair?.second.orEmpty()
        _selectedEventIds.value = emptySet()
        broadcastSyncState(isHeartbeat = false)
    }

    private fun findEventsJson(root: DocumentFile): DocumentFile? {
        val pending = ArrayDeque<DocumentFile>()
        pending.add(root)
        var fallback: DocumentFile? = null
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            for (child in current.listFiles()) {
                if (child.isDirectory) {
                    pending.add(child)
                    continue
                }
                if (!child.name.equals("events.json", ignoreCase = true)) continue
                val location = child.uri.toString()
                if (location.contains("context_detector")) return child
                if (fallback == null) fallback = child
            }
        }
        return fallback
    }

    private fun takePersistableTreePermission(app: Application, uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
            try {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
                // one-shot tree grants still allow listing for this session
            }
        }
    }

    private fun unpackDemoAssets(app: Application): File? {
        val names = app.assets.list("demo") ?: return null
        if (names.isEmpty()) return null
        val dir = File(app.cacheDir, "demo")
        dir.mkdirs()
        for (name in names) {
            if (name.endsWith(".json", ignoreCase = true)) continue
            app.assets.open("demo/$name").use { input ->
                File(dir, name).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return dir
    }

    fun loadVideo(uri: Uri) {
        val app = getApplication<Application>()
        loadedVideoUri = uri
        _selectedVideoName.value = resolveDisplayName(app, uri)
        _videoError.value = null
        _detectedEvents.value = emptyList()
        _selectedEventIds.value = emptySet()
        lastSyncPositionMs = 0L
        takePersistableReadPermission(app, uri)
        broadcastSyncState(positionMs = 0L, isHeartbeat = false)
    }

    fun loadVideoHapticWav(uri: Uri) {
        loadHapticTrack(uri)
    }

    fun loadCompareSlot(algorithm: CompareAlgorithm, uri: Uri) {
        val app = getApplication<Application>()
        takePersistableReadPermission(app, uri)
        val fileName = resolveDisplayName(app, uri) ?: algorithm.shortLabel

        val token = (compareLoadTokens[algorithm] ?: 0L) + 1L
        compareLoadTokens[algorithm] = token
        compareLoadJobs[algorithm]?.cancel()

        updateCompareSlot(algorithm) {
            it.copy(loading = true, error = null, fileName = fileName, uri = uri)
        }

        compareLoadJobs[algorithm] = viewModelScope.launch {
            try {
                val map = withContext(Dispatchers.IO) {
                    WavHapticParser.parse(app, uri)
                }
                if (compareLoadTokens[algorithm] != token) return@launch

                updateCompareSlot(algorithm) {
                    it.copy(
                        loading = false,
                        fileName = fileName,
                        map = map,
                        error = null,
                    )
                }

                if (_activeCompareAlgorithm.value == null) {
                    _activeCompareAlgorithm.value = algorithm
                }
                if (_activeCompareAlgorithm.value == algorithm) {
                    applyCompareSlot(algorithm, seekPositionMs = null)
                }
                broadcastSyncState(isHeartbeat = false)
            } catch (throwable: Throwable) {
                if (compareLoadTokens[algorithm] == token) {
                    updateCompareSlot(algorithm) {
                        it.copy(
                            loading = false,
                            map = null,
                            error = throwable.message ?: throwable.toString(),
                        )
                    }
                }
            }
        }
    }

    fun switchCompareSlot(algorithm: CompareAlgorithm, positionMs: Long, syncNow: Boolean = true) {
        val slot = _compareSlots.value[algorithm] ?: return
        if (slot.map == null) {
            _videoError.value = "Load algorithm ${algorithm.shortLabel} WAV first."
            return
        }
        _activeCompareAlgorithm.value = algorithm
        lastSyncPositionMs = positionMs.coerceAtLeast(0L)
        applyCompareSlot(algorithm, seekPositionMs = if (syncNow) positionMs else null)
        _videoError.value = null
        broadcastSyncState(positionMs = positionMs, isHeartbeat = false)
    }

    fun hasAnyCompareSlotReady(): Boolean {
        return _compareSlots.value.values.any { it.isReady }
    }

    private fun applyCompareSlot(algorithm: CompareAlgorithm, seekPositionMs: Long?) {
        val slot = _compareSlots.value[algorithm] ?: return
        val map = slot.map ?: return
        val label = "${algorithm.shortLabel} · ${slot.fileName ?: algorithm.description}"
        videoHaptic.loadMapData(map, label)
        _selectedHapticTrackName.value = label
        _hapticTrackFormat.value = HapticTrackFormat.WAV
        _videoMapWindows.value = map.track.size
        _videoMapWindowSizeMs.value = map.windowSizeMs
        _hapticTrackDurationMs.value = map.durationMs
        if (seekPositionMs != null && _videoPlaying.value) {
            videoHaptic.forceSyncAt(seekPositionMs, _amplitude.value)
        }
    }

    private fun updateCompareSlot(
        algorithm: CompareAlgorithm,
        transform: (CompareSlotState) -> CompareSlotState,
    ) {
        _compareSlots.value = _compareSlots.value.toMutableMap().apply {
            val current = get(algorithm) ?: CompareSlotState(algorithm)
            put(algorithm, transform(current))
        }
    }

    private fun defaultCompareSlots(): Map<CompareAlgorithm, CompareSlotState> {
        return CompareAlgorithm.all.associateWith { CompareSlotState(it) }
    }

    private fun loadHapticTrack(uri: Uri) {
        val app = getApplication<Application>()
        _videoError.value = null
        takePersistableReadPermission(app, uri)

        val token = ++hapticTrackToken
        hapticTrackLoadJob?.cancel()
        _hapticTrackLoading.value = true
        clearHapticTrackState()

        hapticTrackLoadJob = viewModelScope.launch {
            try {
                val label = withContext(Dispatchers.IO) { videoHaptic.loadWav(uri) }
                if (token != hapticTrackToken) return@launch

                _selectedHapticTrackName.value = label
                _hapticTrackFormat.value = videoHaptic.getTrackFormat()
                _videoMapWindows.value = videoHaptic.getWindowCount()
                _videoMapWindowSizeMs.value = videoHaptic.getWindowSizeMs()
                _hapticTrackDurationMs.value = videoHaptic.getDurationMs()
            } catch (throwable: Throwable) {
                if (token == hapticTrackToken) {
                    clearHapticTrackState()
                    _videoError.value = throwable.message ?: throwable.toString()
                }
            } finally {
                if (token == hapticTrackToken) {
                    _hapticTrackLoading.value = false
                }
            }
        }
    }

    fun playVideo() {
        if (loadedVideoUri == null) {
            _videoError.value = "Load a video file before playing."
            return
        }
        val hasTrack = videoHaptic.hasLoadedTrack() ||
            hasAnyCompareSlotReady()
        if (!hasTrack) {
            _videoError.value = "Load a WAV track or an A–E comparison track before playing."
            return
        }
        if (_hapticTrackLoading.value) {
            _videoError.value = "Haptic track is still loading. Please wait."
            return
        }

        if (!videoHaptic.hasLoadedTrack()) {
            val active = _activeCompareAlgorithm.value
                ?: _compareSlots.value.entries.firstOrNull { it.value.isReady }?.key
            if (active != null) {
                applyCompareSlot(active, seekPositionMs = null)
            }
        }

        stopTest()
        stopAudio()
        _videoPlaying.value = true
        _videoError.value = null
        broadcastSyncState(isHeartbeat = false)
    }

    fun pauseVideo() {
        _videoPlaying.value = false
        videoHaptic.stop()
        broadcastSyncState(isHeartbeat = false)
    }

    fun stopVideo() {
        _videoPlaying.value = false
        videoHaptic.stop()
        lastSyncPositionMs = 0L
        broadcastSyncState(positionMs = 0L, isHeartbeat = false)
    }

    fun onVideoPlaybackPosition(positionMs: Int, videoDurationMs: Long = 0L) {
        val posLong = positionMs.toLong().coerceAtLeast(0L)
        lastSyncPositionMs = posLong
        if (videoDurationMs > 0L) lastSyncDurationMs = videoDurationMs

        if (!_videoPlaying.value) return
        if (videoHaptic.hasLoadedTrack()) {
            videoHaptic.updatePlaybackPosition(posLong, _amplitude.value)
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastHeartbeatElapsedMs >= 200L) {
            lastHeartbeatElapsedMs = now
            broadcastSyncState(positionMs = posLong, durationMs = videoDurationMs, isHeartbeat = true)
        }
    }

    fun hasLoadedHapticTrack(): Boolean = videoHaptic.hasLoadedTrack()

    private fun clearHapticTrackState() {
        _selectedHapticTrackName.value = null
        _hapticTrackFormat.value = null
        _videoMapWindows.value = 0
        _videoMapWindowSizeMs.value = 0L
        _hapticTrackDurationMs.value = 0L
    }

    private fun appendAudioHistory(level: Int) {
        val next = (_audioDebugFrames.value + AudioDebugFrame(level = level)).takeLast(120)
        _audioDebugFrames.value = next
    }

    private fun appendPulseHistory(pulse: HapticPulseDebug) {
        val framePulse = AudioDebugPulse(
            sampleIndex = _audioDebugFrames.value.size,
            level = pulse.level,
            onMs = pulse.onMs,
            offMs = pulse.offMs,
            amplitude = pulse.amplitude,
            isBass = pulse.isBassOnset,
            isDrum = pulse.isDrumHit,
            isSustained = pulse.isSustained,
        )
        _audioDebugFrames.value = (_audioDebugFrames.value + AudioDebugFrame(level = pulse.level, pulse = framePulse)).takeLast(120)
    }

    private fun requestLiveUpdate() {
        if (!_isTesting.value || !_hasVibrator.value) {
            return
        }

        liveUpdateJob?.cancel()
        liveUpdateJob = viewModelScope.launch {
            val waitMs = gate.timeUntilNextMs()
            if (waitMs > 0) {
                delay(waitMs)
            }
            if (!_isTesting.value) return@launch

            haptic.cancel()

            val amp = _amplitude.value
            val dutyPct = _duty.value
            val period = _periodMs.value.toLong()
            val onMs = (period * dutyPct / 100.0).toLong().coerceIn(10L, period)
            val offMs = (period - onMs).coerceAtLeast(10L)
            val timings = longArrayOf(onMs, offMs)
            val amplitudes = intArrayOf(amp, 0)
            haptic.vibrateWaveform(timings, amplitudes, -1)
        }
    }

    private fun pushAudioTuning() {
        val level = _audioSensitivityLevel.value.coerceIn(1, 10)

        val profile = when (level) {
            in 1..2 -> 0.0
            in 3..4 -> 0.25
            in 5..6 -> 0.5
            in 7..8 -> 0.75
            else -> 1.0
        }

        val noiseGate = (42.0 - profile * 36.0)
        val onsetThreshold = (60.0 - profile * 44.0).toInt()
        val sustainedThreshold = (118.0 - profile * 92.0).toInt()
        val smoothingAlpha = 0.06 + profile * 0.34
        val peakDecay = 0.996 - profile * 0.035
        val beatHoldoffMs = (320.0 - profile * 240.0).toLong()
        val bassThreshold = 520.0 - profile * 450.0
        val drumThreshold = 320.0 - profile * 270.0

        audioHaptic.updateTuning(
            noiseGate = noiseGate,
            onsetThreshold = onsetThreshold,
            sustainedThreshold = sustainedThreshold,
            smoothingAlpha = smoothingAlpha,
            peakDecay = peakDecay,
            beatHoldoffMs = beatHoldoffMs,
            useBassOnly = false,
            useDrumOnly = false,
            bassThreshold = bassThreshold,
            drumThreshold = drumThreshold,
        )
    }

    private fun reanalyzeLoadedAudio() {
        val uri = loadedAudioUri ?: return
        audioHaptic.stop()
        _audioPlaying.value = false
        _audioLevel.value = 0
        haptic.cancel()

        startAnalysis(
            uri = uri,
            emptyMessage = "Sensitivity update did not find a stronger bass/drum pattern; live fallback remains available.",
        )
    }

    private fun scheduleReanalyzeLoadedAudio() {
        val uri = loadedAudioUri ?: return

        sensitivityReanalyzeJob?.cancel()
        sensitivityReanalyzeJob = viewModelScope.launch {
            delay(250)
            if (loadedAudioUri != uri) return@launch
            reanalyzeLoadedAudio()
        }
    }

    private fun startAnalysis(
        uri: Uri,
        emptyMessage: String,
    ) {
        val token = ++analysisToken
        analysisJob?.cancel()
        _audioAnalyzing.value = true
        _audioAnalysisReady.value = false
        _audioError.value = null

        analysisJob = viewModelScope.launch {
            try {
                val events = audioHaptic.analyze(uri)
                if (token != analysisToken || loadedAudioUri != uri) return@launch
                audioHaptic.setPrecomputedAnalysis(events)
                _audioAnalysisReady.value = events.isNotEmpty()
                if (events.isEmpty()) {
                    _audioError.value = emptyMessage
                }
            } catch (throwable: Throwable) {
                if (token == analysisToken) {
                    _audioError.value = throwable.message ?: throwable.toString()
                }
            } finally {
                if (token == analysisToken) {
                    _audioAnalyzing.value = false
                }
            }
        }
    }

    private fun takePersistableReadPermission(app: Application, uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Exception) {
            // file:// demo URIs and one-shot picker grants do not persist
        }
    }

    override fun onCleared() {
        syncServer.stop()
        audioHaptic.release()
        videoHaptic.release()
        stopTest()
        super.onCleared()
    }

    private fun resolveDisplayName(app: Application, uri: Uri): String? {
        return try {
            app.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
