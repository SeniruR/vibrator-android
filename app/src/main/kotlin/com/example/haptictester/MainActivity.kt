package com.example.haptictester

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.example.haptictester.viewmodel.HapticViewModel
import com.example.haptictester.ui.HapticDiagnosticScreen

class MainActivity : ComponentActivity() {
    private val viewModel: HapticViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HapticDiagnosticScreen(viewModel = viewModel)
        }
    }
}
