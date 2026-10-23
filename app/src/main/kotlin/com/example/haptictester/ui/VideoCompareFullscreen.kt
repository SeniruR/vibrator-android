package com.example.haptictester.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.runtime.collectAsState
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
import com.example.haptictester.haptic.DetectedEvent
import com.example.haptictester.haptic.HapticMapData
import com.example.haptictester.sync.RemoteSyncCommand
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
    detectedEvents: List<DetectedEvent>,
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
    val selectedEventIds by viewModel.selectedEventIds.collectAsState()
    var eventPlayStartMs by remember { mutableStateOf<Long?>(null) }
    var eventPlayUntilMs by remember { mutableStateOf<Long?>(null) }
    var eventPlayToken by remember { mutableIntStateOf(0) }
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
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    pendingSeekTargetMs = null
                    viewModel.notifyPlayerStateChanged(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        speed = selectedSpeed,
                    )
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                selectedSpeed = playbackParameters.speed
                viewModel.notifyPlayerStateChanged(
                    positionMs = videoPlayer.currentPosition.coerceAtLeast(0L),
                    durationMs = durationMs,
                    speed = selectedSpeed,
                )
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

    fun selectedSpan(ids: Set<String> = selectedEventIds): Pair<Long, Long>? {
        val chosen = detectedEvents.filter { it.id in ids }
        if (chosen.isEmpty()) return null
        val start = chosen.minOf { it.startMs }.coerceAtLeast(0L)
        val end = chosen.maxOf { it.endMs }.coerceAtLeast(start + 200L)
        return start to end
    }

    fun playSpanOnce(startMs: Long, endMs: Long, algorithm: CompareAlgorithm?) {
        val start = startMs.coerceAtLeast(0L)
        val end = endMs.coerceAtLeast(start + 200L)
        eventPlayStartMs = start
        eventPlayUntilMs = end
        eventPlayToken++
        videoPlayer.pause()
        viewModel.pauseVideo()
        if (algorithm != null) {
            viewModel.switchCompareSlot(algorithm, start, syncNow = false)
        }
        // The decoder needs a moment after a seek; starting a little early lets the
        // picture be moving by the time the event begins.
        val seekTo = (start - EVENT_PREROLL_MS).coerceAtLeast(0L)
        pendingSeekTargetMs = seekTo
        videoPlayer.seekTo(seekTo)
        videoPlayer.playWhenReady = true
        videoPlayer.play()
        viewModel.notifyPlayerStateChanged(positionMs = seekTo, durationMs = durationMs, speed = selectedSpeed)
    }

    fun playWholeClip() {
        eventPlayStartMs = null
        eventPlayUntilMs = null
        eventPlayToken++
        videoPlayer.pause()
        viewModel.pauseVideo()
        pendingSeekTargetMs = 0L
        videoPlayer.seekTo(0L)
        videoPlayer.playWhenReady = true
        videoPlayer.play()
        viewModel.notifyPlayerStateChanged(positionMs = 0L, durationMs = durationMs, speed = selectedSpeed)
    }

    fun applyEventSelectionAfterChange(nextIds: Set<String>) {
        eventPlayStartMs = null
        eventPlayUntilMs = null
        if (nextIds.isEmpty()) {
            playWholeClip()
            return
        }
        if (!videoPlayer.isPlaying) {
            val start = detectedEvents.filter { it.id in nextIds }.minOfOrNull { it.startMs }?.coerceAtLeast(0L) ?: 0L
            viewModel.pauseVideo()
            pendingSeekTargetMs = start
            videoPlayer.seekTo(start)
            viewModel.notifyPlayerStateChanged(positionMs = start, durationMs = durationMs, speed = selectedSpeed)
        }
    }

    fun toggleEvent(event: DetectedEvent) {
        val next = viewModel.toggleSelectedEvent(event.id, broadcast = true)
        applyEventSelectionAfterChange(next)
    }

    LaunchedEffect(viewModel, videoPlayer) {
        viewModel.remoteSyncCommands.collect { command ->
            when (command) {
                is RemoteSyncCommand.Play -> {
                    val span = selectedSpan()
                    if (span != null && command.timeMs == null) {
                        playSpanOnce(span.first, span.second, activeAlgorithm)
                    } else {
                        if (command.timeMs != null && kotlin.math.abs(videoPlayer.currentPosition - command.timeMs) > 120L) {
                            pendingSeekTargetMs = command.timeMs
                            videoPlayer.seekTo(command.timeMs)
                        } else if (videoPlayer.playbackState == Player.STATE_ENDED) {
                            videoPlayer.seekTo(0L)
                            positionMs = 0L
                        }
                        viewModel.playVideo()
                        videoPlayer.playWhenReady = true
                        videoPlayer.play()
                    }
                }
                is RemoteSyncCommand.Pause -> {
                    videoPlayer.pause()
                    viewModel.pauseVideo()
                    if (command.timeMs != null && kotlin.math.abs(videoPlayer.currentPosition - command.timeMs) > 80L) {
                        pendingSeekTargetMs = command.timeMs
                        videoPlayer.seekTo(command.timeMs)
                        positionMs = command.timeMs
                    }
                    viewModel.notifyPlayerStateChanged(
                        positionMs = command.timeMs ?: videoPlayer.currentPosition.coerceAtLeast(0L),
                        durationMs = durationMs,
                        speed = selectedSpeed,
                    )
                }
                is RemoteSyncCommand.Seek -> {
                    val target = command.timeMs.coerceIn(0L, durationMs.coerceAtLeast(1L))
                    pendingSeekTargetMs = target
                    positionMs = target
                    videoPlayer.seekTo(target)
                    viewModel.onVideoPlaybackPosition(target.toInt(), durationMs)
                    viewModel.notifyPlayerStateChanged(positionMs = target, durationMs = durationMs, speed = selectedSpeed)
                }
                is RemoteSyncCommand.SetSpeed -> {
                    selectedSpeed = command.speed
                    videoPlayer.setPlaybackSpeed(command.speed)
                    viewModel.notifyPlayerStateChanged(
                        positionMs = videoPlayer.currentPosition.coerceAtLeast(0L),
                        durationMs = durationMs,
                        speed = command.speed,
                    )
                }
                is RemoteSyncCommand.SelectAlgorithm -> {
                    val algo = CompareAlgorithm.all.firstOrNull { it.id.equals(command.algorithmId, ignoreCase = true) }
                    if (algo != null && compareSlots[algo]?.isReady == true) {
                        val span = selectedSpan()
                        if (span != null) {
                            playSpanOnce(span.first, span.second, algo)
                        } else {
                            viewModel.switchCompareSlot(algo, videoPlayer.currentPosition.coerceAtLeast(0L))
                        }
                    }
                }
                is RemoteSyncCommand.ToggleEvent -> {
                    val next = viewModel.toggleSelectedEvent(command.eventId, broadcast = true)
                    applyEventSelectionAfterChange(next)
                }
                is RemoteSyncCommand.SetSelectedEvents -> {
                    viewModel.setSelectedEventIds(command.eventIds, broadcast = true)
                    applyEventSelectionAfterChange(viewModel.selectedEventIds.value)
                }
                RemoteSyncCommand.RequestState -> {
                    viewModel.notifyPlayerStateChanged(
                        positionMs = videoPlayer.currentPosition.coerceAtLeast(0L),
                        durationMs = durationMs,
                        speed = selectedSpeed,
                    )
                }
            }
        }
    }

    LaunchedEffect(videoPlaying, isScrubbing, pendingSeekTargetMs, eventPlayToken) {
        var armed = false
        while (isActive) {
            if (!isScrubbing && pendingSeekTargetMs == null) {
                positionMs = videoPlayer.currentPosition.coerceAtLeast(0L)
                val until = eventPlayUntilMs
                val start = eventPlayStartMs
                var reachedEventEnd = false
                if (until != null && start != null) {
                    if (!armed && positionMs + 250L >= start) armed = true
                    if (armed && positionMs >= until) {
                        eventPlayStartMs = null
                        eventPlayUntilMs = null
                        videoPlayer.pause()
                        viewModel.pauseVideo()
                        positionMs = until
                        viewModel.notifyPlayerStateChanged(positionMs = until, durationMs = durationMs, speed = selectedSpeed)
                        reachedEventEnd = true
                    }
                }
                if (reachedEventEnd) return@LaunchedEffect
                // While buffering after a seek the player already reports the target
                // time but shows the old frame, so haptics wait for real playback.
                if (videoPlaying && videoPlayer.isPlaying) {
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
                                            viewModel.pauseVideo()
                                        } else {
                                            val span = selectedSpan()
                                            if (span != null) {
                                                playSpanOnce(span.first, span.second, activeAlgorithm)
                                            } else {
                                                if (videoPlayer.playbackState == Player.STATE_ENDED) {
                                                    videoPlayer.seekTo(0L)
                                                    positionMs = 0L
                                                }
                                                viewModel.playVideo()
                                                videoPlayer.play()
                                            }
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
                                                val span = selectedSpan()
                                                if (span != null) {
                                                    playSpanOnce(span.first, span.second, algorithm)
                                                } else {
                                                    viewModel.switchCompareSlot(algorithm, videoPlayer.currentPosition)
                                                }
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
                DebugSidePanel(
                    compareSlots = compareSlots,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    events = detectedEvents,
                    selectedEventIds = selectedEventIds,
                    onToggleEvent = ::toggleEvent,
                    onToggleControls = {
                        controlsVisible = !controlsVisible
                        interactionRevision++
                    },
                    modifier = Modifier.weight(0.38f).fillMaxHeight(),
                )
            }
        }
    }
}

private const val EVENT_PREROLL_MS = 500L

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

private fun algorithmColor(algorithm: CompareAlgorithm): Color = when (algorithm) {
    CompareAlgorithm.A -> Color(0xFF59B7F2)
    CompareAlgorithm.B -> Color(0xFF43C68A)
    CompareAlgorithm.C -> Color(0xFFF2B84B)
    CompareAlgorithm.D -> Color(0xFFED7777)
    CompareAlgorithm.E -> Color(0xFFC9A0FF)
}

@Composable
private fun DebugSidePanel(
    compareSlots: Map<CompareAlgorithm, CompareSlotState>,
    positionMs: Long,
    durationMs: Long,
    events: List<DetectedEvent>,
    selectedEventIds: Set<String>,
    onToggleEvent: (DetectedEvent) -> Unit,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableIntStateOf(0) }
    Surface(modifier = modifier, color = Color(0xFF101720)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DebugTab("Live WAV", selected = tab == 0, onClick = { tab = 0 })
                DebugTab("Events", selected = tab == 1, onClick = { tab = 1 })
            }
            if (tab == 0) {
                LiveWavTab(
                    compareSlots = compareSlots,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onTap = onToggleControls,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else {
                EventListTab(
                    events = events,
                    compareSlots = compareSlots,
                    selectedEventIds = selectedEventIds,
                    onToggleEvent = onToggleEvent,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DebugTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) Color(0xFF2C4A63) else Color(0xFF1A2430)
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = background,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun LiveWavTab(
    compareSlots: Map<CompareAlgorithm, CompareSlotState>,
    positionMs: Long,
    durationMs: Long,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.pointerInput(Unit) { detectTapGestures { onTap() } },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "${formatPlaybackTime(positionMs)} / ${formatPlaybackTime(durationMs)}",
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.labelSmall,
        )
        listOf(CompareAlgorithm.A, CompareAlgorithm.B, CompareAlgorithm.C, CompareAlgorithm.D).forEach { algorithm ->
            val slot = compareSlots[algorithm]
            val color = algorithmColor(algorithm)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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

@Composable
private fun EventListTab(
    events: List<DetectedEvent>,
    compareSlots: Map<CompareAlgorithm, CompareSlotState>,
    selectedEventIds: Set<String>,
    onToggleEvent: (DetectedEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chosen = events.filter { it.id in selectedEventIds }
    val summary = when {
        events.isEmpty() -> "No events in this folder"
        chosen.isEmpty() -> "${events.size} events · tick the ones to play"
        else -> {
            val start = chosen.minOf { it.startMs }
            val end = chosen.maxOf { it.endMs }
            "${chosen.size} ticked · ${formatEventSpan(start, end)}"
        }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = summary,
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.labelSmall,
        )
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            events.forEach { event ->
                EventRow(
                    event = event,
                    compareSlots = compareSlots,
                    selected = event.id in selectedEventIds,
                    onToggle = { onToggleEvent(event) },
                )
            }
        }
    }
}

@Composable
private fun EventRow(
    event: DetectedEvent,
    compareSlots: Map<CompareAlgorithm, CompareSlotState>,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) Color(0xFF24506A) else Color(0xFF18222C),
    ) {
        Row(
            modifier = Modifier.padding(start = 2.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                modifier = Modifier.size(32.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFF59B7F2),
                    uncheckedColor = Color.White.copy(alpha = 0.75f),
                    checkmarkColor = Color(0xFF101720),
                ),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(event.label, color = Color.White, style = MaterialTheme.typography.labelMedium)
                Text(
                    "${formatEventSpan(event.startMs, event.endMs)} · ${event.category.ifBlank { event.id }}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                EVENT_PREVIEW_ALGORITHMS.forEach { algorithm ->
                    val color = algorithmColor(algorithm)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val map = compareSlots[algorithm]?.map
                        if (map != null) {
                            WavEnvelopeChart(
                                map = map,
                                positionMs = 0L,
                                durationMs = map.durationMs,
                                color = color,
                                windowStartMs = event.startMs,
                                windowEndMs = event.endMs,
                                drawCursor = false,
                                modifier = Modifier.width(28.dp).height(22.dp),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .width(28.dp)
                                    .height(22.dp)
                                    .background(Color(0xFF080D13), RoundedCornerShape(3.dp)),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val EVENT_PREVIEW_ALGORITHMS = listOf(
    CompareAlgorithm.A,
    CompareAlgorithm.B,
    CompareAlgorithm.C,
    CompareAlgorithm.D,
)

private fun formatEventSpan(startMs: Long, endMs: Long): String {
    return "${formatEventSeconds(startMs)}–${formatEventSeconds(endMs)} s"
}

private fun formatEventSeconds(timeMs: Long): String {
    val seconds = timeMs.coerceAtLeast(0L) / 1000.0
    return if (seconds >= 60.0) {
        val minutes = (seconds / 60.0).toInt()
        val rest = seconds - minutes * 60.0
        "%d:%04.1f".format(minutes, rest)
    } else {
        "%.1f".format(seconds)
    }
}

@Composable
private fun WavEnvelopeChart(
    map: HapticMapData,
    positionMs: Long,
    durationMs: Long,
    color: Color,
    modifier: Modifier = Modifier,
    windowStartMs: Long? = null,
    windowEndMs: Long? = null,
    drawCursor: Boolean = true,
) {
    val fullDuration = maxOf(durationMs, map.durationMs, 1L)
    val rangeStart = windowStartMs ?: 0L
    val rangeEnd = (windowEndMs ?: fullDuration).coerceAtLeast(rangeStart + 1L)
    val columns = if (windowStartMs == null) 360 else 36
    val bins = remember(map, rangeStart, rangeEnd, columns) {
        cachedWaveformBins(map, rangeStart, rangeEnd, columns)
    }
    Canvas(
        modifier = modifier.background(Color(0xFF080D13), RoundedCornerShape(4.dp)),
    ) {
        val midY = size.height * 0.5f
        val span = (rangeEnd - rangeStart).coerceAtLeast(1L).toFloat()
        drawLine(Color.White.copy(alpha = 0.14f), Offset(0f, midY), Offset(size.width, midY), 1f)

        val barWidth = (size.width / bins.size).coerceAtLeast(1.5f)
        for (column in bins.indices) {
            val value = bins[column]
            if (value <= 0f) continue
            val x = (column + 0.5f) / bins.size * size.width
            val topY = midY - value * size.height * 0.46f
            drawLine(color, Offset(x, midY), Offset(x, topY), strokeWidth = barWidth, cap = StrokeCap.Round)
        }

        if (drawCursor) {
            val cursorX = ((positionMs.coerceAtLeast(0L) - rangeStart).coerceAtLeast(0L) / span * size.width)
                .coerceIn(0f, size.width)
            drawLine(Color.White, Offset(cursorX, 0f), Offset(cursorX, size.height), strokeWidth = 1.5f)
        }
    }
}

private val waveformBinCache = object : LinkedHashMap<String, FloatArray>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean = size > 240
}

private fun cachedWaveformBins(map: HapticMapData, startMs: Long, endMs: Long, columns: Int): FloatArray {
    val key = "${System.identityHashCode(map)}|$startMs|$endMs|$columns"
    synchronized(waveformBinCache) {
        waveformBinCache[key]?.let { return it }
        val bins = buildWaveformBins(map, startMs, endMs, columns)
        waveformBinCache[key] = bins
        return bins
    }
}

private fun buildWaveformBins(map: HapticMapData, startMs: Long, endMs: Long, columns: Int): FloatArray {
    val bins = FloatArray(columns.coerceAtLeast(1))
    val span = (endMs - startMs).coerceAtLeast(1L).toFloat()
    val samples = map.envelope.ifEmpty { map.track }
    for ((timeMs, rawValue) in samples) {
        if (timeMs < startMs || timeMs > endMs) continue
        val column = (((timeMs - startMs) / span) * bins.size).toInt().coerceIn(0, bins.lastIndex)
        val value = rawValue.coerceIn(0, 255) / 255f
        if (value > bins[column]) bins[column] = value
    }
    return bins
}
