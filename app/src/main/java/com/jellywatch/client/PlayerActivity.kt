package com.jellywatch.client

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var api: JellyfinApi
    private lateinit var jellyItem: JellyItem
    private lateinit var playSessionId: String
    private var usingDirectFallback = false
    private var hasReportedStart = false
    private val reporter = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private val progressReporter = object : Runnable {
        override fun run() {
            report("Progress")
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val session = Session(
            server = requireNotNull(intent.getStringExtra(EXTRA_SERVER)),
            token = requireNotNull(intent.getStringExtra(EXTRA_TOKEN)),
            userId = requireNotNull(intent.getStringExtra(EXTRA_USER_ID)),
            userName = intent.getStringExtra(EXTRA_USER_NAME).orEmpty(),
            deviceId = requireNotNull(intent.getStringExtra(EXTRA_DEVICE_ID))
        )
        jellyItem = JellyItem(
            id = requireNotNull(intent.getStringExtra(EXTRA_ITEM_ID)),
            name = intent.getStringExtra(EXTRA_ITEM_NAME).orEmpty(),
            type = intent.getStringExtra(EXTRA_ITEM_TYPE).orEmpty(),
            overview = "",
            seriesName = null,
            productionYear = null,
            indexNumber = null,
            parentIndexNumber = null,
            runtimeTicks = intent.getLongExtra(EXTRA_RUNTIME, 0L),
            playbackTicks = intent.getLongExtra(EXTRA_POSITION, 0L),
            playedPercentage = 0.0,
            primaryImageTag = null,
            backdropImageTag = null,
            mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE),
            mediaSourceId = intent.getStringExtra(EXTRA_MEDIA_SOURCE_ID)
        )
        api = JellyfinApi(session)
        playSessionId = UUID.randomUUID().toString().replace("-", "")
        setContentView(createPlayerView())
        initializePlayer()
    }

    private fun createPlayerView(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val playerView = PlayerView(this).apply {
            id = PLAYER_VIEW_ID
            useController = true
            controllerShowTimeoutMs = 3_500
            controllerAutoShow = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.BLACK)
        }
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val close = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            contentDescription = "Back"
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(190, 22, 19, 25))
                shape = GradientDrawable.OVAL
            }
            stateListAnimator = null
            setOnClickListener { finish() }
        }
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            close.visibility = visibility
        })
        close.visibility = if (playerView.isControllerFullyVisible) View.VISIBLE else View.GONE
        root.addView(close, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.START).apply {
            topMargin = dp(18)
            leftMargin = dp(20)
        })

        return root
    }

    private fun initializePlayer() {
        val view = findViewById<PlayerView>(PLAYER_VIEW_ID)
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            view.player = exoPlayer
            exoPlayer.setMediaItem(MediaItem.fromUri(api.playbackUrl(jellyItem, playSessionId)))
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        if (!hasReportedStart) {
                            hasReportedStart = true
                            report("")
                        }
                        handler.removeCallbacks(progressReporter)
                        handler.postDelayed(progressReporter, 10_000)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    handlePlaybackError(exoPlayer, error)
                }
            })
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    private fun handlePlaybackError(exoPlayer: ExoPlayer, error: PlaybackException) {
        val httpError = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
        if (!usingDirectFallback) {
            usingDirectFallback = true
            val reason = httpError?.responseCode?.let { "HTTP $it" } ?: error.errorCodeName
            Toast.makeText(this, "Transcoding unavailable ($reason). Trying the original file…", Toast.LENGTH_LONG).show()
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.setMediaItem(MediaItem.fromUri(api.directPlaybackUrl(jellyItem)))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            return
        }
        val message = if (httpError != null) {
            "Jellyfin returned HTTP ${httpError.responseCode}. Check server permissions and transcoding settings."
        } else {
            "Playback failed: ${error.errorCodeName}"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onStop() {
        handler.removeCallbacks(progressReporter)
        report("Stopped")
        player?.release()
        player = null
        super.onStop()
    }

    override fun onDestroy() {
        reporter.shutdown()
        super.onDestroy()
    }

    private fun report(event: String) {
        val positionTicks = jellyItem.playbackTicks + (player?.currentPosition ?: 0L) * 10_000L
        reporter.execute {
            api.reportPlayback(event, jellyItem.id, playSessionId, positionTicks)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val PLAYER_VIEW_ID = 0x4A57
        private const val EXTRA_SERVER = "server"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_USER_ID = "userId"
        private const val EXTRA_USER_NAME = "userName"
        private const val EXTRA_DEVICE_ID = "deviceId"
        private const val EXTRA_ITEM_ID = "itemId"
        private const val EXTRA_ITEM_NAME = "itemName"
        private const val EXTRA_ITEM_TYPE = "itemType"
        private const val EXTRA_MEDIA_TYPE = "mediaType"
        private const val EXTRA_MEDIA_SOURCE_ID = "mediaSourceId"
        private const val EXTRA_RUNTIME = "runtime"
        private const val EXTRA_POSITION = "position"

        fun start(context: Context, session: Session, item: JellyItem) {
            context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_SERVER, session.server)
                putExtra(EXTRA_TOKEN, session.token)
                putExtra(EXTRA_USER_ID, session.userId)
                putExtra(EXTRA_USER_NAME, session.userName)
                putExtra(EXTRA_DEVICE_ID, session.deviceId)
                putExtra(EXTRA_ITEM_ID, item.id)
                putExtra(EXTRA_ITEM_NAME, item.name)
                putExtra(EXTRA_ITEM_TYPE, item.type)
                putExtra(EXTRA_MEDIA_TYPE, item.mediaType)
                putExtra(EXTRA_MEDIA_SOURCE_ID, item.mediaSourceId)
                putExtra(EXTRA_RUNTIME, item.runtimeTicks)
                putExtra(EXTRA_POSITION, item.playbackTicks)
            })
        }
    }
}
