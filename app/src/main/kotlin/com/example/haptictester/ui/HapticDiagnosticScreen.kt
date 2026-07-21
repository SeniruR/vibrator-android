package com.example.haptictester.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.haptictester.haptic.CompareAlgorithm
import com.example.haptictester.haptic.CompareSlotState
import com.example.haptictester.haptic.HapticTrackFormat
import com.example.haptictester.viewmodel.AudioDebugFrame
import com.example.haptictester.viewmodel.HapticViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private enum class AppTab(val label: String) {
    Video("Video + Haptic"),
    Audio("Audio"),
    Manual("Manual Test"),
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HapticDiagnosticScreen(viewModel: HapticViewModel) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    val amplitude by viewModel.amplitude.collectAsState()
    val duty by viewModel.duty.collectAsState()
    val periodMs by viewModel.periodMs.collectAsState()
    val isTesting by viewModel.isTesting.collectAsState()
    val hasAmplitude by viewModel.hasAmplitude.collectAsState()
    val hasVibrator by viewModel.hasVibrator.collectAsState()
    val selectedAudioName by viewModel.selectedAudioName.collectAsState()
    val audioPlaying by viewModel.audioPlaying.collectAsState()
    val audioLevel by viewModel.audioLevel.collectAsState()
    val audioError by viewModel.audioError.collectAsState()
    val audioAnalyzing by viewModel.audioAnalyzing.collectAsState()
    val audioAnalysisReady by viewModel.audioAnalysisReady.collectAsState()
    val audioSensitivityLevel by viewModel.audioSensitivityLevel.collectAsState()
    val audioDebugFrames by viewModel.audioDebugFrames.collectAsState()
    val selectedVideoName by viewModel.selectedVideoName.collectAsState()
    val selectedHapticTrackName by viewModel.selectedHapticTrackName.collectAsState()
    val hapticTrackFormat by viewModel.hapticTrackFormat.collectAsState()
    val hapticTrackLoading by viewModel.hapticTrackLoading.collectAsState()
    val videoPlaying by viewModel.videoPlaying.collectAsState()
    val videoError by viewModel.videoError.collectAsState()
    val videoMapWindows by viewModel.videoMapWindows.collectAsState()
    val videoMapWindowSizeMs by viewModel.videoMapWindowSizeMs.collectAsState()
    val hapticTrackDurationMs by viewModel.hapticTrackDurationMs.collectAsState()
    val compareSlots by viewModel.compareSlots.collectAsState()
    val activeCompareAlgorithm by viewModel.activeCompareAlgorithm.collectAsState()

    val recordAudioGranted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val videoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 1f
        }
    }

    DisposableEffect(videoPlayer) {
        onDispose { videoPlayer.release() }
    }

    var loadedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var videoPrepared by remember { mutableStateOf(false) }
    var videoHasAudio by remember { mutableStateOf(false) }
    var videoDurationMs by remember { mutableStateOf(0L) }
    var isVideoFullscreen by remember { mutableStateOf(false) }
    var pendingCompareAlgorithm by remember { mutableStateOf<CompareAlgorithm?>(null) }

    val openAudioLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) viewModel.loadAudio(uri)
    }
    val openVideoLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            loadedVideoUri = uri
            videoPrepared = false
            videoHasAudio = false
            viewModel.loadVideo(uri)
        }
    }
    val openJsonLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) viewModel.loadVideoHapticMap(uri)
    }
    val openWavLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) viewModel.loadVideoHapticWav(uri)
    }
    val openCompareLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        val algorithm = pendingCompareAlgorithm
        if (uri != null && algorithm != null) {
            viewModel.loadCompareSlot(algorithm, uri)
        }
        pendingCompareAlgorithm = null
    }
    val permissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { /* handled in UI */ }

    val hasCompareReady = compareSlots.values.any { it.isReady }
    val canPlayVideo = selectedVideoName != null &&
        videoPrepared &&
        !hapticTrackLoading &&
        (selectedHapticTrackName != null || hasCompareReady)

    DisposableEffect(videoPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                videoHasAudio = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && !videoPlaying) {
                    viewModel.playVideo()
                } else if (!isPlaying && videoPlaying) {
                    viewModel.pauseVideo()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        videoDurationMs = videoPlayer.duration.coerceAtLeast(0L)
                        videoPrepared = true
                    }
                    Player.STATE_ENDED -> {
                        videoPlayer.seekTo(0)
                        viewModel.stopVideo()
                    }
                }
            }
        }
        videoPlayer.addListener(listener)
        onDispose { videoPlayer.removeListener(listener) }
    }

    LaunchedEffect(videoPlaying, loadedVideoUri, selectedHapticTrackName, isVideoFullscreen) {
        if (!videoPlaying || isVideoFullscreen) return@LaunchedEffect
        while (isActive && videoPlaying) {
            viewModel.onVideoPlaybackPosition(
                positionMs = videoPlayer.currentPosition.toInt(),
                videoDurationMs = videoDurationMs,
            )
            delay(20)
        }
    }

    LaunchedEffect(loadedVideoUri) {
        val uri = loadedVideoUri ?: return@LaunchedEffect
        videoPlayer.stop()
        videoPlayer.clearMediaItems()
        videoPlayer.setMediaItem(MediaItem.fromUri(uri))
        videoPlayer.prepare()
        videoPrepared = false
        videoDurationMs = 0L
        videoPlayer.playWhenReady = false
    }

    val durationMismatch = videoDurationMs > 0L &&
        hapticTrackDurationMs > 0L &&
        kotlin.math.abs(videoDurationMs - hapticTrackDurationMs) > 2_000L

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Haptic Tester", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (hasVibrator) "Ready on device" else "No vibrator detected",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                AppTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(tab.label, maxLines = 1) },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IntensityCapCard(
                    amplitude = amplitude,
                    hasAmplitude = hasAmplitude,
                    onAmplitudeChange = viewModel::setAmplitude,
                )

                when (AppTab.entries[selectedTab]) {
                    AppTab.Video -> VideoTab(
                        selectedVideoName = selectedVideoName,
                        selectedHapticTrackName = selectedHapticTrackName,
                        hapticTrackFormat = hapticTrackFormat,
                        hapticTrackLoading = hapticTrackLoading,
                        compareSlots = compareSlots,
                        activeCompareAlgorithm = activeCompareAlgorithm,
                        canPlayVideo = canPlayVideo,
                        videoPlaying = videoPlaying,
                        videoPrepared = videoPrepared,
                        videoHasAudio = videoHasAudio,
                        videoMapWindows = videoMapWindows,
                        videoMapWindowSizeMs = videoMapWindowSizeMs,
                        hapticTrackDurationMs = hapticTrackDurationMs,
                        videoDurationMs = videoDurationMs,
                        durationMismatch = durationMismatch,
                        videoError = videoError,
                        onSelectVideo = { openVideoLauncher.launch(arrayOf("video/*")) },
                        onSelectJson = { openJsonLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                        onSelectWav = { openWavLauncher.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*")) },
                        onLoadCompareSlot = { algorithm ->
                            pendingCompareAlgorithm = algorithm
                            openCompareLauncher.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*"))
                        },
                        onSwitchCompareSlot = { algorithm ->
                            viewModel.switchCompareSlot(algorithm, videoPlayer.currentPosition)
                        },
                        onPlayVideo = {
                            viewModel.playVideo()
                            videoPlayer.playWhenReady = true
                            videoPlayer.play()
                        },
                        onPauseVideo = {
                            videoPlayer.pause()
                            viewModel.pauseVideo()
                        },
                        onStopVideo = {
                            videoPlayer.pause()
                            videoPlayer.seekTo(0)
                            viewModel.stopVideo()
                        },
                        onFullscreen = {
                            if (loadedVideoUri == null) return@VideoTab
                            if (!videoPlaying) {
                                viewModel.playVideo()
                                videoPlayer.playWhenReady = true
                                videoPlayer.play()
                            }
                            isVideoFullscreen = true
                        },
                        videoPlayer = videoPlayer,
                    )

                    AppTab.Audio -> AudioTab(
                        selectedAudioName = selectedAudioName,
                        audioPlaying = audioPlaying,
                        recordAudioGranted = recordAudioGranted,
                        audioLevel = audioLevel,
                        audioError = audioError,
                        audioAnalyzing = audioAnalyzing,
                        audioAnalysisReady = audioAnalysisReady,
                        audioSensitivityLevel = audioSensitivityLevel,
                        audioDebugFrames = audioDebugFrames,
                        onSelectAudio = { openAudioLauncher.launch(arrayOf("audio/*")) },
                        onGrantPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onPlayAudio = {
                            if (!recordAudioGranted) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                viewModel.playAudio()
                            }
                        },
                        onPauseAudio = viewModel::pauseAudio,
                        onStopAudio = viewModel::stopAudio,
                        onSensitivityChange = viewModel::setAudioSensitivityLevel,
                    )

                    AppTab.Manual -> ManualTestTab(
                        duty = duty,
                        periodMs = periodMs,
                        isTesting = isTesting,
                        hasVibrator = hasVibrator,
                        audioPlaying = audioPlaying,
                        videoPlaying = videoPlaying,
                        onDutyChange = viewModel::setDuty,
                        onPeriodChange = viewModel::setPeriodMs,
                        onStartTest = viewModel::startTest,
                        onStopTest = viewModel::stopTest,
                    )
                }

                HardwareStatusCard(
                    hasVibrator = hasVibrator,
                    hasAmplitude = hasAmplitude,
                    isTesting = isTesting,
                    audioPlaying = audioPlaying,
                    videoPlaying = videoPlaying,
                )
            }
        }
    }

        if (isVideoFullscreen) {
            VideoCompareFullscreenOverlay(
                viewModel = viewModel,
                videoPlayer = videoPlayer,
                videoPlaying = videoPlaying,
                compareSlots = compareSlots,
                activeAlgorithm = activeCompareAlgorithm,
                onDismiss = { isVideoFullscreen = false },
            )
        }
    }
}

@Composable
private fun IntensityCapCard(
    amplitude: Int,
    hasAmplitude: Boolean,
    onAmplitudeChange: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Haptic Intensity Cap", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = "$amplitude / 255 — scales all synced haptic output",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = amplitude.toFloat(),
                onValueChange = { onAmplitudeChange(it.toInt()) },
                valueRange = 0f..255f,
            )
            Text(
                text = if (hasAmplitude) {
                    "Hardware amplitude control available."
                } else {
                    "No hardware amplitude control — slider still caps software intensity."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoTab(
    selectedVideoName: String?,
    selectedHapticTrackName: String?,
    hapticTrackFormat: HapticTrackFormat?,
    hapticTrackLoading: Boolean,
    compareSlots: Map<CompareAlgorithm, CompareSlotState>,
    activeCompareAlgorithm: CompareAlgorithm?,
    canPlayVideo: Boolean,
    videoPlaying: Boolean,
    videoPrepared: Boolean,
    videoHasAudio: Boolean,
    videoMapWindows: Int,
    videoMapWindowSizeMs: Long,
    hapticTrackDurationMs: Long,
    videoDurationMs: Long,
    durationMismatch: Boolean,
    videoError: String?,
    onSelectVideo: () -> Unit,
    onSelectJson: () -> Unit,
    onSelectWav: () -> Unit,
    onLoadCompareSlot: (CompareAlgorithm) -> Unit,
    onSwitchCompareSlot: (CompareAlgorithm) -> Unit,
    onPlayVideo: () -> Unit,
    onPauseVideo: () -> Unit,
    onStopVideo: () -> Unit,
    onFullscreen: () -> Unit,
    videoPlayer: ExoPlayer,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Synced Video Playback", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "Load an MP4 plus haptic tracks. Use A–D slots for ground-truth WAVs, then fullscreen to switch live.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Algorithm compare (WAV)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompareAlgorithm.all.forEach { algorithm ->
                    val slot = compareSlots[algorithm]
                    val ready = slot?.isReady == true
                    val loading = slot?.loading == true
                    OutlinedButton(
                        onClick = {
                            if (ready) {
                                onSwitchCompareSlot(algorithm)
                            } else {
                                onLoadCompareSlot(algorithm)
                            }
                        },
                        enabled = !loading,
                    ) {
                        Text(
                            when {
                                loading -> "${algorithm.shortLabel}…"
                                ready -> "${algorithm.shortLabel} ✓"
                                else -> "Load ${algorithm.shortLabel}"
                            },
                        )
                    }
                }
            }
            activeCompareAlgorithm?.let { active ->
                Text(
                    text = "Active: ${active.shortLabel} (${active.description})",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            compareSlots.values.filter { it.error != null }.forEach { slot ->
                Text(
                    text = "${slot.algorithm.shortLabel}: ${slot.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text("Single track (optional JSON/WAV)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)

            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        player = videoPlayer
                        useController = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setShowFastForwardButton(false)
                        setShowRewindButton(false)
                    }
                },
                update = { it.player = videoPlayer },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(label = if (videoPrepared) "Video ready" else "No video")
                StatusChip(label = if (videoHasAudio) "Audio track" else "No audio")
                StatusChip(label = if (videoPlaying) "Playing" else "Stopped")
                if (hapticTrackFormat != null) {
                    StatusChip(label = "Track: ${hapticTrackFormat.name}")
                }
            }

            FileRow(label = "Video", value = selectedVideoName)
            FileRow(label = "Haptic track", value = selectedHapticTrackName)

            if (videoMapWindows > 0) {
                Text(
                    text = "$videoMapWindows windows · ${videoMapWindowSizeMs}ms each · track ${formatDuration(hapticTrackDurationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (videoDurationMs > 0L) {
                Text(
                    text = "Video duration: ${formatDuration(videoDurationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (durationMismatch) {
                Text(
                    text = "Duration mismatch between video and haptic track — haptics may stop early or drift.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (hapticTrackLoading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                    Text("Loading haptic track…", style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSelectVideo, modifier = Modifier.weight(1f)) { Text("Video") }
                OutlinedButton(onClick = onSelectWav, modifier = Modifier.weight(1f)) { Text("WAV") }
                OutlinedButton(onClick = onSelectJson, modifier = Modifier.weight(1f)) { Text("JSON") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPlayVideo,
                    enabled = canPlayVideo,
                    modifier = Modifier.weight(1f),
                ) { Text("Play") }
                OutlinedButton(onClick = onPauseVideo, enabled = videoPlaying, modifier = Modifier.weight(1f)) { Text("Pause") }
                OutlinedButton(onClick = onStopVideo, enabled = selectedVideoName != null, modifier = Modifier.weight(1f)) { Text("Stop") }
            }

            OutlinedButton(onClick = onFullscreen, enabled = selectedVideoName != null, modifier = Modifier.fillMaxWidth()) {
                Text("Fullscreen + live A/B/C/D switch")
            }

            if (videoError != null) {
                Text(text = videoError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AudioTab(
    selectedAudioName: String?,
    audioPlaying: Boolean,
    recordAudioGranted: Boolean,
    audioLevel: Int,
    audioError: String?,
    audioAnalyzing: Boolean,
    audioAnalysisReady: Boolean,
    audioSensitivityLevel: Int,
    audioDebugFrames: List<AudioDebugFrame>,
    onSelectAudio: () -> Unit,
    onGrantPermission: () -> Unit,
    onPlayAudio: () -> Unit,
    onPauseAudio: () -> Unit,
    onStopAudio: () -> Unit,
    onSensitivityChange: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Live Audio Analysis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "Detects bass and drum hits in real time. Do not use haptic-groundtruth WAV files here — use Video mode instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FileRow(label = "Audio file", value = selectedAudioName)
            Text(
                text = when {
                    audioAnalyzing -> "Analyzing track…"
                    audioAnalysisReady -> "Beat timeline ready"
                    else -> "Live fallback mode"
                },
                style = MaterialTheme.typography.bodySmall,
            )

            LinearProgressIndicator(progress = audioLevel / 100f, modifier = Modifier.fillMaxWidth())
            Text("Level: $audioLevel%", style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSelectAudio) { Text("Open Audio") }
                Button(onClick = onPlayAudio, enabled = selectedAudioName != null && !audioPlaying && !audioAnalyzing) { Text("Play") }
                OutlinedButton(onClick = onPauseAudio, enabled = audioPlaying) { Text("Pause") }
                OutlinedButton(onClick = onStopAudio, enabled = selectedAudioName != null) { Text("Stop") }
            }

            Text("Sensitivity: $audioSensitivityLevel / 10", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = audioSensitivityLevel.toFloat(),
                onValueChange = { onSensitivityChange(it.toInt()) },
                valueRange = 1f..10f,
                steps = 8,
            )

            if (!recordAudioGranted) {
                Text(
                    text = "Microphone permission is needed for Visualizer-based tracking.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onGrantPermission) { Text("Grant Permission") }
            }

            if (audioError != null) {
                Text(text = audioError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (audioDebugFrames.isNotEmpty()) {
        DebugTimelineCard(frames = audioDebugFrames)
    }
}

@Composable
private fun ManualTestTab(
    duty: Int,
    periodMs: Int,
    isTesting: Boolean,
    hasVibrator: Boolean,
    audioPlaying: Boolean,
    videoPlaying: Boolean,
    onDutyChange: (Int) -> Unit,
    onPeriodChange: (Int) -> Unit,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Manual Motor Test", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "Pulse the vibrator directly to verify amplitude and duty cycle on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Duty cycle: $duty%", style = MaterialTheme.typography.bodyMedium)
            Slider(value = duty.toFloat(), onValueChange = { onDutyChange(it.toInt()) }, valueRange = 0f..100f)

            Text("Period: $periodMs ms", style = MaterialTheme.typography.bodyMedium)
            Slider(value = periodMs.toFloat(), onValueChange = { onPeriodChange(it.toInt()) }, valueRange = 60f..1000f)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStartTest,
                    enabled = hasVibrator && !isTesting && !audioPlaying && !videoPlaying,
                    modifier = Modifier.weight(1f),
                ) { Text("Start") }
                Button(
                    onClick = onStopTest,
                    enabled = isTesting,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun HardwareStatusCard(
    hasVibrator: Boolean,
    hasAmplitude: Boolean,
    isTesting: Boolean,
    audioPlaying: Boolean,
    videoPlaying: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Device", style = MaterialTheme.typography.labelLarge)
            Text("Vibrator: ${if (hasVibrator) "yes" else "no"} · Amplitude control: ${if (hasAmplitude) "yes" else "no"}")
            Text("Active: ${listOfNotNull(
                if (isTesting) "manual test" else null,
                if (audioPlaying) "audio" else null,
                if (videoPlaying) "video" else null,
            ).joinToString(", ").ifBlank { "idle" }}")
        }
    }
}

@Composable
private fun FileRow(label: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        Text(value ?: "not selected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusChip(label: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

@Composable
private fun DebugTimelineCard(frames: List<AudioDebugFrame>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Debug Timeline", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
            ) {
                val width = size.width
                val height = size.height
                drawRect(Color(0xFFF7F3FB), size = Size(width, height))

                val samples = frames.takeLast(120)
                if (samples.isEmpty()) return@Canvas

                val stepX = if (samples.size > 1) width / (samples.size - 1) else width
                val audioMidY = height * 0.32f
                val audioHeight = height * 0.22f
                val eventMidY = height * 0.78f
                val eventHeight = height * 0.18f

                val points = samples.mapIndexed { index, frame ->
                    val x = index * stepX
                    val normalized = frame.level.coerceIn(0, 100) / 100f
                    Offset(x, audioMidY - (normalized * audioHeight))
                }

                for (i in 0 until points.lastIndex) {
                    drawLine(Color(0xFF6B4BB5), points[i], points[i + 1], strokeWidth = 4f, cap = StrokeCap.Round)
                }

                samples.forEachIndexed { index, frame ->
                    val pulse = frame.pulse ?: return@forEachIndexed
                    val x = index * stepX
                    val barTop = if (pulse.isSustained) eventMidY - eventHeight * 0.6f else eventMidY - eventHeight * 0.3f
                    val barColor = when {
                        pulse.isSustained -> Color(0xFF26A69A)
                        pulse.isBass && pulse.isDrum -> Color(0xFFEF6C00)
                        pulse.isBass -> Color(0xFFFF7043)
                        pulse.isDrum -> Color(0xFF42A5F5)
                        else -> Color(0xFF7E57C2)
                    }
                    drawLine(barColor, Offset(x, eventMidY), Offset(x, barTop), strokeWidth = 6f, cap = StrokeCap.Round)
                }
            }
        }
    }
}
