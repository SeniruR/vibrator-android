package com.example.haptictester

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import com.example.haptictester.viewmodel.HapticViewModel
import com.example.haptictester.ui.HapticDiagnosticScreen

class MainActivity : ComponentActivity() {
    private val viewModel: HapticViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF5E35B1),
                    primaryContainer = androidx.compose.ui.graphics.Color(0xFFEDE7F6),
                    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFE8EAF6),
                ),
            ) {
                HapticDiagnosticScreen(viewModel = viewModel)
            }
        }
    }
}
