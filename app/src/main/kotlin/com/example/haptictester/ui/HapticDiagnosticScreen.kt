package com.example.haptictester.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.haptictester.viewmodel.AudioDebugFrame
import com.example.haptictester.viewmodel.HapticViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun HapticDiagnosticScreen(viewModel: HapticViewModel) {
    val context = LocalContext.current
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
    val audioSensitivityLevel by viewModel.audioSensitivityLevel.collectAsState()
    val audioAnalyzing by viewModel.audioAnalyzing.collectAsState()
    val audioAnalysisReady by viewModel.audioAnalysisReady.collectAsState()
    val audioDebugFrames by viewModel.audioDebugFrames.collectAsState()
    val selectedVideoName by viewModel.selectedVideoName.collectAsState()
    val selectedVideoMapName by viewModel.selectedVideoMapName.collectAsState()
    val videoPlaying by viewModel.videoPlaying.collectAsState()
    val videoError by viewModel.videoError.collectAsState()
    val videoMapWindows by viewModel.videoMapWindows.collectAsState()
    val videoMapWindowSizeMs by viewModel.videoMapWindowSizeMs.collectAsState()

    val recordAudioGranted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val videoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 1f
        }
    }
    DisposableEffect(videoPlayer) {
        onDispose {
            videoPlayer.release()
        }
    }

    var loadedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var videoPrepared by remember { mutableStateOf(false) }
    var videoHasAudio by remember { mutableStateOf(false) }

    val openAudioLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.loadAudio(uri)
        }
    }
    val openVideoLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            loadedVideoUri = uri
            videoPrepared = false
            videoHasAudio = false
            viewModel.loadVideo(uri)
        }
    }
    val openMapLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.loadVideoHapticMap(uri)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (!granted) {
            // UI below explains why vibration-from-audio remains unavailable.
        }
    }

    LaunchedEffect(videoPlaying, loadedVideoUri, selectedVideoMapName) {
        if (!videoPlaying) {
            return@LaunchedEffect
        }

        while (isActive && videoPlaying) {
            viewModel.onVideoPlaybackPosition(videoPlayer.currentPosition.toInt())
            delay(20)
        }
    }

    LaunchedEffect(loadedVideoUri) {
        val uri = loadedVideoUri ?: return@LaunchedEffect
        videoPlayer.stop()
        videoPlayer.clearMediaItems()
        videoPlayer.setMediaItem(MediaItem.fromUri(uri))
        videoPlayer.prepare()
        videoPrepared = true
        videoHasAudio = true
        videoPlayer.playWhenReady = false
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Haptic Diagnostic Tester", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Load a video plus a generated haptic JSON map for synced vibration, or use the existing audio mode below.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(16.dp))

            VideoBlock(
                selectedVideoName = selectedVideoName,
                selectedVideoMapName = selectedVideoMapName,
                videoPlaying = videoPlaying,
                videoPrepared = videoPrepared,
                videoHasAudio = videoHasAudio,
                videoMapWindows = videoMapWindows,
                videoMapWindowSizeMs = videoMapWindowSizeMs,
                videoError = videoError,
                onSelectVideo = { openVideoLauncher.launch(arrayOf("video/*")) },
                onSelectMap = { openMapLauncher.launch(arrayOf("application/json", "text/*")) },
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
                onVideoPlayerReady = { playerView ->
                    playerView.player = videoPlayer
                    playerView.useController = true
                    playerView.setShowNextButton(false)
                    playerView.setShowPreviousButton(false)
                    playerView.setShowFastForwardButton(false)
                    playerView.setShowRewindButton(false)
                    videoPlayer.addListener(object : Player.Listener {
                        override fun onTracksChanged(tracks: Tracks) {
                            videoHasAudio = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (!isPlaying && videoPlaying) {
                                viewModel.stopVideo()
                            }
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) {
                                videoPlayer.seekTo(0)
                                viewModel.stopVideo()
                            }
                        }
                    })
                },
            )

            Spacer(modifier = Modifier.height(14.dp))

            AudioBlock(
                selectedAudioName = selectedAudioName,
                audioPlaying = audioPlaying,
                recordAudioGranted = recordAudioGranted,
                audioLevel = audioLevel,
                audioError = audioError,
                audioAnalyzing = audioAnalyzing,
                audioAnalysisReady = audioAnalysisReady,
                onSelectAudio = { openAudioLauncher.launch(arrayOf("audio/*")) },
                onGrantPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onPlayAudio = {
                    if (!recordAudioGranted) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.playAudio()
                    }
                },
                onPauseAudio = { viewModel.pauseAudio() },
                onStopAudio = { viewModel.stopAudio() },
            )

            Spacer(modifier = Modifier.height(14.dp))

            DebugTimelineCard(
                frames = audioDebugFrames,
            )

            Spacer(modifier = Modifier.height(14.dp))

            ControlBlock(
                title = "Audio Sensitivity",
                valueText = "Level $audioSensitivityLevel / 10",
                helperText = "Lower levels need louder bass and clearer drum hits. Higher levels react faster and more easily.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Slider(
                        value = audioSensitivityLevel.toFloat(),
                        onValueChange = { viewModel.setAudioSensitivityLevel(it.toInt()) },
                        valueRange = 1f..10f,
                        steps = 8,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            ControlBlock(
                title = "Intensity (Amplitude)",
                valueText = "$amplitude / 255",
                helperText = if (hasAmplitude) {
                    "Hardware amplitude control is available. This slider should change actual motor strength."
                } else {
                    "This phone does not support real amplitude control. Use this as a software intensity target only."
                },
            ) {
                androidx.compose.material3.Slider(
                    value = amplitude.toFloat(),
                    onValueChange = { viewModel.setAmplitude(it.toInt()) },
                    valueRange = 0f..255f,
                    enabled = hasAmplitude,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            ControlBlock(
                title = "Pulse Width (Duty Cycle)",
                valueText = "$duty% ON time",
                helperText = "Higher duty cycle means the motor stays ON longer in each pulse, which feels stronger or more continuous.",
            ) {
                androidx.compose.material3.Slider(
                    value = duty.toFloat(),
                    onValueChange = { viewModel.setDuty(it.toInt()) },
                    valueRange = 0f..100f,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            ControlBlock(
                title = "Frequency (Pulse Period)",
                valueText = "$periodMs ms",
                helperText = "Smaller values mean faster buzzing. Larger values mean slower thumps.",
            ) {
                androidx.compose.material3.Slider(
                    value = periodMs.toFloat(),
                    onValueChange = { viewModel.setPeriodMs(it.toInt()) },
                    valueRange = 60f..1000f,
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.startTest() },
                    enabled = hasVibrator && !isTesting && !audioPlaying && !videoPlaying,
                ) {
                    Text("Start Test")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.stopTest() },
                    enabled = isTesting,
                ) {
                    Text("Stop")
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Hardware Dashboard", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("hasVibrator: $hasVibrator")
                    Text("hasAmplitudeControl: $hasAmplitude")
                    Text("isVibrating: $isTesting")
                    Text("audioPlaying: $audioPlaying")
                    Text("videoPlaying: $videoPlaying")
                    Text("selectedAudio: ${selectedAudioName ?: "none"}")
                    Text("selectedVideo: ${selectedVideoName ?: "none"}")
                    Text("videoMap: ${selectedVideoMapName ?: "none"}")
                }
            }
        }
    }
}

@Composable
private fun VideoBlock(
    selectedVideoName: String?,
    selectedVideoMapName: String?,
    videoPlaying: Boolean,
    videoPrepared: Boolean,
    videoHasAudio: Boolean,
    videoMapWindows: Int,
    videoMapWindowSizeMs: Long,
    videoError: String?,
    onSelectVideo: () -> Unit,
    onSelectMap: () -> Unit,
    onPlayVideo: () -> Unit,
    onPauseVideo: () -> Unit,
    onStopVideo: () -> Unit,
    onVideoPlayerReady: (PlayerView) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Video Playback Mode", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Choose an mp4 and a generated haptic JSON map. The video preview runs in the app and the JSON windows trigger vibrations as playback advances.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(10.dp))

            AndroidView(
                factory = { context ->
                    PlayerView(context).also { onVideoPlayerReady(it) }
                },
                update = { view ->
                    view.useController = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text("Selected video: ${selectedVideoName ?: "none"}")
            Text("Selected map: ${selectedVideoMapName ?: "none"}")
            Text("Map windows: $videoMapWindows")
            Text("Map window size: ${if (videoMapWindowSizeMs > 0) "$videoMapWindowSizeMs ms" else "unknown"}")
            Text(if (videoPrepared) "Video ready" else "Video not loaded yet")
            Text(if (videoHasAudio) "Audio track detected" else "No audio track detected yet")
            Text(if (videoPlaying) "Video playing" else "Video stopped")
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSelectVideo) { Text("Open Video") }
                Button(onClick = onSelectMap) { Text("Open JSON") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onPlayVideo,
                    enabled = selectedVideoName != null && selectedVideoMapName != null && videoPrepared,
                ) {
                    Text("Play")
                }
                Button(
                    onClick = onPauseVideo,
                    enabled = videoPlaying,
                ) {
                    Text("Pause")
                }
                Button(
                    onClick = onStopVideo,
                    enabled = selectedVideoName != null,
                ) {
                    Text("Stop")
                }
            }

            if (videoError != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = videoError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AudioBlock(
    selectedAudioName: String?,
    audioPlaying: Boolean,
    recordAudioGranted: Boolean,
    audioLevel: Int,
    audioError: String?,
    audioAnalyzing: Boolean,
    audioAnalysisReady: Boolean,
    onSelectAudio: () -> Unit,
    onGrantPermission: () -> Unit,
    onPlayAudio: () -> Unit,
    onPauseAudio: () -> Unit,
    onStopAudio: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Audio Playback Mode", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Pick an audio file and let the phone build a beat timeline from bass and drum peaks before playback.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Selected file: ${selectedAudioName ?: "none"}")
            Text("Audio level: $audioLevel%")
            Text(if (audioAnalyzing) "Analyzing track..." else if (audioAnalysisReady) "Analysis ready" else "Analysis fallback only")
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = audioLevel / 100f,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSelectAudio) { Text("Open Audio") }
                Button(onClick = onPlayAudio, enabled = selectedAudioName != null && !audioPlaying && !audioAnalyzing) { Text("Play") }
                Button(onClick = onPauseAudio, enabled = audioPlaying) { Text("Pause") }
                Button(onClick = onStopAudio, enabled = selectedAudioName != null) { Text("Stop") }
            }
            if (!recordAudioGranted) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Microphone permission is needed for Visualizer-based audio tracking. Grant it to let the app analyze bass and drum energy from the playing track.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onGrantPermission) {
                    Text("Grant Audio Permission")
                }
            }
            if (audioError != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = audioError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ControlBlock(
    title: String,
    valueText: String,
    helperText: String,
    slider: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(valueText, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            slider()
            Spacer(modifier = Modifier.height(6.dp))
            Text(helperText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DebugTimelineCard(
    frames: List<AudioDebugFrame>,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Debug Timeline", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Top lane = audio envelope. Bottom lane = generated haptic events.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimelineLegendItem(color = Color(0xFF6B4BB5), label = "Audio")
                TimelineLegendItem(color = Color(0xFFFF7043), label = "Bass")
                TimelineLegendItem(color = Color(0xFF42A5F5), label = "Drum")
                TimelineLegendItem(color = Color(0xFF26A69A), label = "Sustain")
            }
            Spacer(modifier = Modifier.height(10.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
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

                drawLine(
                    color = Color(0xFFD9D2E8),
                    start = Offset(0f, audioMidY),
                    end = Offset(width, audioMidY),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Color(0xFFD9D2E8),
                    start = Offset(0f, eventMidY),
                    end = Offset(width, eventMidY),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
                for (i in 0..4) {
                    val y = height * (0.08f + i * 0.12f)
                    drawLine(
                        color = Color(0xFFEAE4F4),
                        start = Offset(0f, y),
                        end = Offset(width, y),
                        strokeWidth = 1f,
                    )
                }

                val points = samples.mapIndexed { index, frame ->
                    val x = index * stepX
                    val normalized = (frame.level.coerceIn(0, 100) / 100f)
                    val y = audioMidY - (normalized * audioHeight)
                    Offset(x, y)
                }

                for (i in 0 until points.lastIndex) {
                    drawLine(
                        color = Color(0xFF6B4BB5),
                        start = points[i],
                        end = points[i + 1],
                        strokeWidth = 4f,
                        cap = StrokeCap.Round,
                    )
                }

                points.forEach { point ->
                    drawCircle(Color(0xFF8E63D6), radius = 3f, center = point)
                }

                samples.forEachIndexed { index, frame ->
                    val pulse = frame.pulse ?: return@forEachIndexed
                    val x = index * stepX
                    val laneTop = height * 0.58f
                    val laneBottom = height * 0.96f
                    val barTop = if (pulse.isSustained) laneTop + eventHeight * 0.10f else laneTop + eventHeight * 0.28f
                    val barBottom = laneBottom
                    val barColor = when {
                        pulse.isSustained -> Color(0xFF26A69A)
                        pulse.isBass && pulse.isDrum -> Color(0xFFEF6C00)
                        pulse.isBass -> Color(0xFFFF7043)
                        pulse.isDrum -> Color(0xFF42A5F5)
                        else -> Color(0xFF7E57C2)
                    }
                    drawLine(
                        color = Color(0xFFE7DFF3),
                        start = Offset(x, laneTop),
                        end = Offset(x, laneBottom),
                        strokeWidth = 1f,
                    )
                    drawLine(
                        color = barColor,
                        start = Offset(x, barBottom),
                        end = Offset(x, barTop),
                        strokeWidth = 6f,
                        cap = StrokeCap.Round,
                    )
                    drawCircle(barColor, radius = 6f, center = Offset(x, barTop + 8f))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Purple line = audio level history. Lower lane markers show which haptic type fired at that moment.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TimelineLegendItem(
    color: Color,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            modifier = Modifier
                .width(10.dp)
                .height(10.dp),
        ) {
            drawCircle(color = color, radius = size.minDimension / 2f)
        }
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
