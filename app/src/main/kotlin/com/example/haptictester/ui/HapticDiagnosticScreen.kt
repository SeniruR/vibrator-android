package com.example.haptictester.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.haptictester.viewmodel.HapticViewModel
import androidx.compose.runtime.collectAsState

@Composable
fun HapticDiagnosticScreen(viewModel: HapticViewModel) {
    val amplitude by viewModel.amplitude.collectAsState()
    val duty by viewModel.duty.collectAsState()
    val periodMs by viewModel.periodMs.collectAsState()
    val isTesting by viewModel.isTesting.collectAsState()
    val hasAmplitude by viewModel.hasAmplitude.collectAsState()
    val hasVibrator by viewModel.hasVibrator.collectAsState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Haptic Diagnostic Tester", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Use the sliders while the motor is running to test intensity, pulse width, and frequency-like behavior.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(20.dp))

            ControlBlock(
                title = "Intensity (Amplitude)",
                valueText = "$amplitude / 255",
                helperText = if (hasAmplitude) {
                    "Hardware amplitude control is available. This slider should change actual motor strength."
                } else {
                    "This phone does not support real amplitude control. Use this as a software intensity target only."
                }
            ) {
                Slider(
                    value = amplitude.toFloat(),
                    onValueChange = { viewModel.setAmplitude(it.toInt()) },
                    valueRange = 0f..255f,
                    enabled = hasAmplitude
                )
            }
            Spacer(modifier = Modifier.height(14.dp))

            ControlBlock(
                title = "Pulse Width (Duty Cycle)",
                valueText = "$duty% ON time",
                helperText = "Higher duty cycle means the motor stays ON longer in each pulse, which feels stronger or more continuous."
            ) {
                Slider(
                    value = duty.toFloat(),
                    onValueChange = { viewModel.setDuty(it.toInt()) },
                    valueRange = 0f..100f
                )
            }
            Spacer(modifier = Modifier.height(14.dp))

            ControlBlock(
                title = "Frequency (Pulse Period)",
                valueText = "$periodMs ms",
                helperText = "Smaller values mean faster buzzing. Larger values mean slower thumps."
            ) {
                Slider(
                    value = periodMs.toFloat(),
                    onValueChange = { viewModel.setPeriodMs(it.toInt()) },
                    valueRange = 60f..1000f
                )
            }
            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.startTest() },
                    enabled = hasVibrator && !isTesting
                ) {
                    Text("Start Test")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.stopTest() },
                    enabled = isTesting
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
                    Text("pulsePeriodMs: $periodMs")
                }
            }
        }
    }
}

@Composable
private fun ControlBlock(
    title: String,
    valueText: String,
    helperText: String,
    slider: @Composable () -> Unit
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
