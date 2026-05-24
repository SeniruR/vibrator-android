package com.example.haptictester.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class VideoFullscreenActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_POSITION = "extra_position"
        const val EXTRA_PLAYING = "extra_playing"

        fun createIntent(context: Context, uri: Uri, position: Long, playing: Boolean): Intent {
            return Intent(context, VideoFullscreenActivity::class.java).apply {
                putExtra(EXTRA_URI, uri.toString())
                putExtra(EXTRA_POSITION, position)
                putExtra(EXTRA_PLAYING, playing)
            }
        }
    }

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val uriStr = intent.getStringExtra(EXTRA_URI)
        val uri = uriStr?.let { Uri.parse(it) }
        val position = intent.getLongExtra(EXTRA_POSITION, 0L)
        val playing = intent.getBooleanExtra(EXTRA_PLAYING, false)

        val playerView = PlayerView(this)
        playerView.setBackgroundColor(Color.BLACK)
        playerView.keepScreenOn = true
        setContentView(playerView)

        // Keep the screen on while fullscreen video plays
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Prefer using shared player if available so vibrations/state remain consistent
        player = PlayerHolder.player ?: run {
            val p = ExoPlayer.Builder(this).build()
            if (uri != null) {
                p.setMediaItem(MediaItem.fromUri(uri))
                p.prepare()
                p.seekTo(position)
                p.playWhenReady = playing
            }
            p
        }

        PlayerHolder.player = player
        playerView.player = player
        playerView.useController = true

        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onStop() {
        super.onStop()
        player?.let {
            val pos = it.currentPosition
            val isPlaying = it.isPlaying
            // Detach player from this view but do not release shared player
            setResult(RESULT_OK, Intent().apply {
                putExtra(EXTRA_POSITION, pos)
                putExtra(EXTRA_PLAYING, isPlaying)
            })
            // Do not release if this is the shared player; main screen manages lifecycle
            player = null
        }
        finish()
    }
}
