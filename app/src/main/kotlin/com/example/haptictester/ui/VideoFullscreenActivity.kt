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

    private var playerView: PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val uriStr = intent.getStringExtra(EXTRA_URI)
        val uri = uriStr?.let { Uri.parse(it) }
        val position = intent.getLongExtra(EXTRA_POSITION, 0L)
        val playing = intent.getBooleanExtra(EXTRA_PLAYING, false)

        val view = PlayerView(this)
        playerView = view
        view.setBackgroundColor(Color.BLACK)
        view.keepScreenOn = true
        setContentView(view)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val player = PlayerHolder.player ?: run {
            ExoPlayer.Builder(this).build().apply {
                if (uri != null) {
                    setMediaItem(MediaItem.fromUri(uri))
                    prepare()
                    seekTo(position)
                    playWhenReady = playing
                }
            }
        }

        PlayerHolder.player = player
        view.player = player
        view.useController = true

        if (PlayerHolder.player === player && uri != null && player.mediaItemCount == 0) {
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.seekTo(position)
            player.playWhenReady = playing
        }

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onStop() {
        val player = PlayerHolder.player
        if (player != null) {
            setResult(
                RESULT_OK,
                Intent().apply {
                    putExtra(EXTRA_POSITION, player.currentPosition)
                    putExtra(EXTRA_PLAYING, player.isPlaying)
                },
            )
        }
        playerView?.player = null
        super.onStop()
        finish()
    }
}
