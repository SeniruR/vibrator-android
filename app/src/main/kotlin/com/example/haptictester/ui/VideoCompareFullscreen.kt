package com.example.haptictester.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.haptictester.haptic.CompareAlgorithm
import com.example.haptictester.haptic.CompareSlotState
import com.example.haptictester.haptic.HapticMapData
import com.example.haptictester.viewmodel.HapticViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
    val activity = context as Activity

    DisposableEffect(Unit) {
        val window = activity.window
        val previousOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
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
            activity.requestedOrientation = previousOrientation
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    var positionMs by remember(videoPlayer) { mutableStateOf(videoPlayer.currentPosition.coerceAtLeast(0L)) }
    var isScrubbing by remember(videoPlayer) { mutableStateOf(false) }
    var pendingSeekTargetMs by remember(videoPlayer) { mutableStateOf<Long?>(null) }
    var selectedSpeed by remember(videoPlayer) { mutableStateOf(videoPlayer.playbackParameters.speed) }
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var debugVisible by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionRevision by remember { mutableIntStateOf(0) }
    var playerDurationMs by remember(videoPlayer) { mutableStateOf(knownDurationMs(videoPlayer)) }
    var unseekable by remember(videoPlayer) { mutableStateOf(isUnseekable(videoPlayer)) }
    // Fragmented MP4s report no duration until fully buffered; the haptic WAVs span the same clip.
    val hapticDurationMs = compareSlots.values.maxOfOrNull { it.map?.durationMs ?: 0L } ?: 0L
    val durationMs = if (playerDurationMs > 0L) playerDurationMs else hapticDurationMs
    val speedOptions = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    fun refreshPlayerInfo() {
        playerDurationMs = knownDurationMs(videoPlayer)
        unseekable = isUnseekable(videoPlayer)
    }

    fun keepControlsVisible() {
        controlsVisible = true
        interactionRevision++
    }

    BackHandler(onBack = onDismiss)

    DisposableEffect(videoPlayer) {
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                positionMs = newPosition.positionMs.coerceAtLeast(0L)
                if (reason == Player.DISCONTINUITY_REASON_SEEK) pendingSeekTargetMs = null
            }

            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                selectedSpeed = playbackParameters.speed
            }

            override fun onEvents(player: Player, events: Player.Events) {
                refreshPlayerInfo()
            }
        }
        videoPlayer.addListener(listener)
        onDispose { videoPlayer.removeListener(listener) }
    }

    LaunchedEffect(pendingSeekTargetMs) {
        val targetMs = pendingSeekTargetMs ?: return@LaunchedEffect
        repeat(40) {
            val actualMs = videoPlayer.currentPosition.coerceAtLeast(0L)
            if (kotlin.math.abs(actualMs - targetMs) <= 100L) {
                positionMs = actualMs
                pendingSeekTargetMs = null
                return@LaunchedEffect
            }
            delay(25L)
        }
        positionMs = videoPlayer.currentPosition.coerceAtLeast(0L)
        pendingSeekTargetMs = null
    }

    LaunchedEffect(videoPlaying, controlsVisible, interactionRevision) {
        if (!videoPlaying || !controlsVisible) return@LaunchedEffect
        delay(3_000L)
        controlsVisible = false
    }

    LaunchedEffect(videoPlaying, isScrubbing, pendingSeekTargetMs) {
        while (isActive) {
            if (!isScrubbing && pendingSeekTargetMs == null) {
                positionMs = videoPlayer.currentPosition.coerceAtLeast(0L)
                if (videoPlaying) {
                    viewModel.onVideoPlaybackPosition(positionMs.toInt(), knownDurationMs(videoPlayer))
                }
            }
            delay(if (videoPlaying) 20L else 100L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .zIndex(10f),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(if (debugVisible) 0.62f else 1f)
                    .fillMaxHeight(),
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = videoPlayer
                            useController = false
                            setShowNextButton(false)
                            setShowPreviousButton(false)
                            setShowFastForwardButton(false)
                            setShowRewindButton(false)
                        }
                    },
                    update = { it.player = videoPlayer },
                    modifier = Modifier.fillMaxSize(),
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(controlsVisible) {
                            detectTapGestures {
                                controlsVisible = !controlsVisible
                                interactionRevision++
                            }
                        },
                )

                if (controlsVisible) {
                    OutlinedButton(
                        onClick = {
                            keepControlsVisible()
                            debugVisible = !debugVisible
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                    ) {
                        Text("!", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }

                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Black.copy(alpha = 0.84f),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(formatPlaybackTime(positionMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                Slider(
                                    value = positionMs.coerceIn(0L, durationMs.coerceAtLeast(1L)).toFloat(),
                                    onValueChange = { value ->
                                        keepControlsVisible()
                                        isScrubbing = true
                                        positionMs = value.toLong().coerceIn(0L, durationMs)
                                    },
                                    onValueChangeFinished = {
                                        val targetMs = positionMs.coerceIn(0L, durationMs)
                                        pendingSeekTargetMs = targetMs
                                        videoPlayer.seekTo(targetMs)
                                        viewModel.onVideoPlaybackPosition(targetMs.toInt(), durationMs)
                                        isScrubbing = false
                                    },
                                    valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                                    enabled = durationMs > 0L && !unseekable,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(formatPlaybackTime(durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                            if (unseekable) {
                                Text(
                                    "This video file can't be seeked (fragmented MP4). Re-export it with the latest notebook.",
                                    color = Color(0xFFF2B84B),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box {
                                    OutlinedButton(
                                        onClick = {
                                            keepControlsVisible()
                                            speedMenuExpanded = true
                                        },
                                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp),
                                    ) {
                                        Text("${selectedSpeed}x", color = Color.White)
                                    }
                                    DropdownMenu(
                                        expanded = speedMenuExpanded,
                                        onDismissRequest = { speedMenuExpanded = false },
                                    ) {
                                        speedOptions.forEach { speed ->
                                            DropdownMenuItem(
                                                text = { Text("${speed}x") },
                                                onClick = {
                                                    selectedSpeed = speed
                                                    videoPlayer.setPlaybackSpeed(speed)
                                                    speedMenuExpanded = false
                                                    keepControlsVisible()
                                                },
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.weight(1f))
                                Button(
                                    onClick = {
                                        keepControlsVisible()
                                        if (videoPlayer.isPlaying) {
                                            videoPlayer.pause()
                                        } else {
                                            if (videoPlayer.playbackState == Player.STATE_ENDED) {
                                                videoPlayer.seekTo(0L)
                                                positionMs = 0L
                                            }
                                            videoPlayer.play()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 4.dp),
                                ) {
                                    Text(if (videoPlaying) "Pause" else "Play")
                                }
                                Spacer(modifier = Modifier.weight(1f))

                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    CompareAlgorithm.all.forEach { algorithm ->
                                        val slot = compareSlots[algorithm]
                                        val ready = slot?.isReady == true
                                        val loading = slot?.loading == true
                                        val selected = activeAlgorithm == algorithm
                                        val onSelect = {
                                            keepControlsVisible()
                                            if (ready && !loading) {
                                                viewModel.switchCompareSlot(algorithm, videoPlayer.currentPosition)
                                            }
                                        }
                                        if (selected) {
                                            Button(
                                                onClick = onSelect,
                                                enabled = ready && !loading,
                                                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                                            ) { Text(if (loading) "…" else algorithm.shortLabel) }
                                        } else {
                                            OutlinedButton(
                                                onClick = onSelect,
                                                enabled = ready && !loading,
                                                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                                            ) { Text(if (loading) "…" else algorithm.shortLabel, color = Color.White) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (debugVisible) {
                LiveWavPatternPanel(
                    compareSlots = compareSlots,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onTap = {
                        controlsVisible = !controlsVisible
                        interactionRevision++
                    },
                    modifier = Modifier.weight(0.38f).fillMaxHeight(),
                )
            }
        }
    }
}

private fun knownDurationMs(player: Player): Long = player.duration.coerceAtLeast(0L)

private fun isUnseekable(player: Player): Boolean {
    val timeline = player.currentTimeline
    if (timeline.isEmpty) return false
    val window = timeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
    return !window.isPlaceholder && !window.isSeekable
}

private fun formatPlaybackTime(timeMs: Long): String {
    val totalSeconds = (timeMs.coerceAtLeast(0L) / 1000L).toInt()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun LiveWavPatternPanel(
    compareSlots: Map<CompareAlgorithm, CompareSlotState>,
    positionMs: Long,
    durationMs: Long,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { onTap() }
        },
        color = Color(0xFF101720),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "LIVE WAV · A–D",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "${formatPlaybackTime(positionMs)} / ${formatPlaybackTime(durationMs)}",
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall,
            )

            listOf(CompareAlgorithm.A, CompareAlgorithm.B, CompareAlgorithm.C, CompareAlgorithm.D).forEach { algorithm ->
                val slot = compareSlots[algorithm]
                val color = when (algorithm) {
                    CompareAlgorithm.A -> Color(0xFF59B7F2)
                    CompareAlgorithm.B -> Color(0xFF43C68A)
                    CompareAlgorithm.C -> Color(0xFFF2B84B)
                    CompareAlgorithm.D -> Color(0xFFED7777)
                    CompareAlgorithm.E -> Color.White
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${algorithm.shortLabel} · ${algorithm.description}",
                            color = color,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (slot?.isReady != true) {
                            Text("No WAV", color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (slot?.map != null) {
                        WavEnvelopeChart(
                            map = slot.map,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            color = color,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color(0xFF080D13), RoundedCornerShape(4.dp)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WavEnvelopeChart(
    map: HapticMapData,
    positionMs: Long,
    durationMs: Long,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val samples = remember(map) {
        (map.envelope.ifEmpty { map.track }).toSortedMap().entries.map { it.key to it.value }
    }
    Canvas(
        modifier = modifier.background(Color(0xFF080D13), RoundedCornerShape(4.dp)),
    ) {
        val midY = size.height * 0.5f
        val chartDuration = maxOf(durationMs, map.durationMs, 1L).toFloat()
        drawLine(Color.White.copy(alpha = 0.14f), Offset(0f, midY), Offset(size.width, midY), 1f)

        val stride = (samples.size / 450).coerceAtLeast(1)
        for (index in samples.indices step stride) {
            val (timeMs, rawValue) = samples[index]
            val x = (timeMs / chartDuration * size.width).coerceIn(0f, size.width)
            val value = rawValue.coerceIn(0, 255) / 255f
            val topY = midY - value * size.height * 0.44f
            drawLine(color, Offset(x, midY), Offset(x, topY), strokeWidth = 1.5f, cap = StrokeCap.Round)
        }

        val cursorX = (positionMs.coerceAtLeast(0L) / chartDuration * size.width).coerceIn(0f, size.width)
        drawLine(Color.White, Offset(cursorX, 0f), Offset(cursorX, size.height), strokeWidth = 1.5f)
    }
}
