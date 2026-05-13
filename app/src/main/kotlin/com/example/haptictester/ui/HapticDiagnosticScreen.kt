package com.example.haptictester.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.haptictester.viewmodel.HapticViewModel
import com.example.haptictester.viewmodel.AudioDebugFrame

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
    val audioVibrateEnabled by viewModel.audioVibrateEnabled.collectAsState()
    val audioLevel by viewModel.audioLevel.collectAsState()
    val audioError by viewModel.audioError.collectAsState()
    val audioNoiseGate by viewModel.audioNoiseGate.collectAsState()
    val audioOnsetThreshold by viewModel.audioOnsetThreshold.collectAsState()
    val audioSustainedThreshold by viewModel.audioSustainedThreshold.collectAsState()
    val audioSmoothing by viewModel.audioSmoothing.collectAsState()
    val audioPeakDecay by viewModel.audioPeakDecay.collectAsState()
    val audioBeatHoldoff by viewModel.audioBeatHoldoff.collectAsState()
    val audioDebugFrames by viewModel.audioDebugFrames.collectAsState()

    val recordAudioGranted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val openAudioLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.loadAudio(uri)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (!granted) {
            // UI below explains why vibration-from-audio remains unavailable.
        }
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
                text = "Use the sliders while the motor is running, or load an audio file to drive vibrations from the sound envelope.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(16.dp))

            AudioBlock(
                selectedAudioName = selectedAudioName,
                audioPlaying = audioPlaying,
                audioVibrateEnabled = audioVibrateEnabled,
                recordAudioGranted = recordAudioGranted,
                audioLevel = audioLevel,
                audioError = audioError,
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
                onToggleVibrateFromAudio = { enabled ->
                    if (!recordAudioGranted && enabled) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.setAudioVibrateEnabled(enabled)
                    }
                },
            )

            Spacer(modifier = Modifier.height(14.dp))

            DebugTimelineCard(
                frames = audioDebugFrames,
            )

            Spacer(modifier = Modifier.height(14.dp))

            ControlBlock(
                title = "Audio Sensitivity",
                valueText = "Noise gate: $audioNoiseGate | Onset: $audioOnsetThreshold | Sustain: $audioSustainedThreshold",
                helperText = "Adjust while audio is playing. Higher values reduce false triggers from fans/background noise.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Slider(
                        value = audioNoiseGate.toFloat(),
                        onValueChange = { viewModel.setAudioNoiseGate(it.toInt()) },
                        valueRange = 0f..40f,
                    )
                    Text("Noise gate")
                    androidx.compose.material3.Slider(
                        value = audioOnsetThreshold.toFloat(),
                        onValueChange = { viewModel.setAudioOnsetThreshold(it.toInt()) },
                        valueRange = 1f..50f,
                    )
                    Text("Bass onset threshold")
                    androidx.compose.material3.Slider(
                        value = audioSustainedThreshold.toFloat(),
                        onValueChange = { viewModel.setAudioSustainedThreshold(it.toInt()) },
                        valueRange = 1f..100f,
                    )
                    Text("Sustained tone threshold")
                    androidx.compose.material3.Slider(
                        value = audioSmoothing.toFloat(),
                        onValueChange = { viewModel.setAudioSmoothing(it.toInt()) },
                        valueRange = 5f..80f,
                    )
                    Text("Smoothing")
                    androidx.compose.material3.Slider(
                        value = audioPeakDecay.toFloat(),
                        onValueChange = { viewModel.setAudioPeakDecay(it.toInt()) },
                        valueRange = 80f..99f,
                    )
                    Text("Peak decay")

                    androidx.compose.material3.Slider(
                        value = audioBeatHoldoff.toFloat(),
                        onValueChange = { viewModel.setAudioBeatHoldoff(it.toInt()) },
                        valueRange = 60f..250f,
                    )
                    Text("Beat holdoff")
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
                    enabled = hasVibrator && !isTesting && !audioPlaying,
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
                    Text("selectedAudio: ${selectedAudioName ?: "none"}")
                }
            }
        }
    }
}

@Composable
private fun AudioBlock(
    selectedAudioName: String?,
    audioPlaying: Boolean,
    audioVibrateEnabled: Boolean,
    recordAudioGranted: Boolean,
    audioLevel: Int,
    audioError: String?,
    onSelectAudio: () -> Unit,
    onGrantPermission: () -> Unit,
    onPlayAudio: () -> Unit,
    onPauseAudio: () -> Unit,
    onStopAudio: () -> Unit,
    onToggleVibrateFromAudio: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Audio Playback Mode", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Pick an audio file and let the phone vibrate from the audio envelope. This uses the sound's waveform level, not perfect studio-grade analysis.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Selected file: ${selectedAudioName ?: "none"}")
            Text("Audio level: $audioLevel%")
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = audioLevel / 100f,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSelectAudio) { Text("Open Audio") }
                Button(onClick = onPlayAudio, enabled = selectedAudioName != null && !audioPlaying) { Text("Play") }
                Button(onClick = onPauseAudio, enabled = audioPlaying) { Text("Pause") }
                Button(onClick = onStopAudio, enabled = selectedAudioName != null) { Text("Stop") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(
                    checked = audioVibrateEnabled,
                    onCheckedChange = onToggleVibrateFromAudio,
                    enabled = recordAudioGranted,
                )
                Text(if (audioVibrateEnabled) "Vibrate from audio ON" else "Vibrate from audio OFF")
            }
            if (!recordAudioGranted) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Microphone permission is needed for Visualizer-based audio tracking. Grant it to enable vibration from the music.",
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
                text = "Top line = audio envelope. Bars = moments when haptic vibration is generated.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(10.dp))
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
                val midY = height * 0.68f
                val graphHeight = height * 0.58f

                // baseline and grid
                drawLine(
                    color = Color(0xFFD9D2E8),
                    start = Offset(0f, midY),
                    end = Offset(width, midY),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
                for (i in 0..4) {
                    val y = height * (0.12f + i * 0.12f)
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
                    val y = midY - (normalized * graphHeight)
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
                    val barTop = height * 0.10f
                    val barBottom = height * 0.95f
                    val barColor = if (pulse.isBass) Color(0xFFFF7043) else if (pulse.isSustained) Color(0xFF26A69A) else Color(0xFF7E57C2)
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
            Text("Purple line = audio level history. Orange bars = bass hits. Teal bars = sustained rumble.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
