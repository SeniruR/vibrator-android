package com.example.haptictester.ui

import android.app.Activity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.haptictester.haptic.CompareAlgorithm
import com.example.haptictester.haptic.CompareSlotState
import com.example.haptictester.viewmodel.HapticViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoCompareFullscreenOverlay(
    viewModel: HapticViewModel,
    videoPlayer: ExoPlayer,
    videoPlaying: Boolean,
    compareSlots: Map<CompareAlgorithm, CompareSlotState>,
    activeAlgorithm: CompareAlgorithm?,
    onDismiss: () -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val window = (context as Activity).window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = WindowInsetsControllerCompat(window, view)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    LaunchedEffect(videoPlaying, activeAlgorithm) {
        if (!videoPlaying) return@LaunchedEffect
        while (isActive && videoPlaying) {
            viewModel.onVideoPlaybackPosition(videoPlayer.currentPosition.toInt())
            delay(20)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = videoPlayer
                    useController = true
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowFastForwardButton(false)
                    setShowRewindButton(false)
                }
            },
            update = { it.player = videoPlayer },
            modifier = Modifier.fillMaxSize(),
        )

        // Top-only overlay so ExoPlayer seek bar at the bottom stays reachable.
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.72f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Haptic compare",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = activeAlgorithm?.let { "Active: ${it.shortLabel} · ${it.description}" }
                                ?: "Tap A–E to switch haptics",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = Color.White)
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CompareAlgorithm.all.forEach { algorithm ->
                        val slot = compareSlots[algorithm]
                        val ready = slot?.isReady == true
                        val loading = slot?.loading == true
                        val selected = activeAlgorithm == algorithm
                        if (selected) {
                            Button(
                                onClick = {
                                    if (ready) {
                                        viewModel.switchCompareSlot(
                                            algorithm = algorithm,
                                            positionMs = videoPlayer.currentPosition,
                                        )
                                    }
                                },
                                enabled = ready && !loading,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Text(if (loading) "${algorithm.shortLabel}…" else algorithm.shortLabel)
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    if (ready) {
                                        viewModel.switchCompareSlot(
                                            algorithm = algorithm,
                                            positionMs = videoPlayer.currentPosition,
                                        )
                                    }
                                },
                                enabled = ready && !loading,
                            ) {
                                Text(
                                    when {
                                        loading -> "${algorithm.shortLabel}…"
                                        ready -> algorithm.shortLabel
                                        else -> "${algorithm.shortLabel} –"
                                    },
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
