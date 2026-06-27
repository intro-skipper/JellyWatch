package com.jellywatch.client

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
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
    private var usingTranscodeFallback = false
    private var hasReportedStart = false
    private val reporter = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var controlsOverlay: View
    private lateinit var playPause: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var progressTrack: FrameLayout
    private lateinit var progressFill: View
    private lateinit var resizeModeToggle: ImageButton
    private lateinit var segmentSkipButton: TextView
    private lateinit var segmentSkipSpacer: Space
    private var controlsVisible = true
    private var isScrubbing = false
    private var stretchVideo = false
    private var shouldPlayWhenActive = true
    private var mediaSegments = emptyList<MediaSegment>()
    private var currentSegment: MediaSegment? = null

    private val progressReporter = object : Runnable {
        override fun run() {
            report("Progress")
            handler.postDelayed(this, 10_000)
        }
    }
    private val controlsHider = Runnable { setControlsVisible(false) }
    private val controlsProgressUpdater = object : Runnable {
        override fun run() {
            updateControls()
            handler.postDelayed(this, 500)
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
            seriesId = null,
            seasonId = null,
            parentId = null,
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
        loadMediaSegments()
    }

    override fun onStart() {
        super.onStart()
        if (player == null) initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = shouldPlayWhenActive
        showControls()
        handler.removeCallbacks(controlsProgressUpdater)
        handler.post(controlsProgressUpdater)
    }

    override fun onPause() {
        player?.pause()
        handler.removeCallbacks(progressReporter)
        handler.removeCallbacks(controlsProgressUpdater)
        handler.removeCallbacks(controlsHider)
        super.onPause()
    }

    private fun createPlayerView(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val isRoundScreen = resources.configuration.isScreenRound
        val progressSideInset = if (isRoundScreen) dp(34) else dp(18)
        val progressBottomInset = if (isRoundScreen) dp(32) else dp(14)
        val seekBottomInset = (progressBottomInset - dp(16)).coerceAtLeast(0)
        val controlsTopInset = dp(60)
        val controlsBottomInset = progressBottomInset + dp(6)
        val playerView = PlayerView(this).apply {
            id = PLAYER_VIEW_ID
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.BLACK)
            setOnClickListener { toggleControls() }
        }
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.setOnClickListener { toggleControls() }

        controlsOverlay = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(130, 0, 0, 0), Color.TRANSPARENT)
            )
            setOnClickListener { toggleControls() }
        }
        root.addView(controlsOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val close = controlButton(R.drawable.ic_back, "Back", dp(42)).apply {
            setOnClickListener { finish() }
        }
        (controlsOverlay as FrameLayout).addView(close, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP or Gravity.START).apply {
            topMargin = dp(18)
            leftMargin = dp(18)
        })

        resizeModeToggle = controlButton(R.drawable.ic_fit_screen, "Video sizing: fit", dp(46)).apply {
            setOnClickListener {
                toggleResizeMode()
                showControls()
            }
        }
        (controlsOverlay as FrameLayout).addView(resizeModeToggle, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(18)
            rightMargin = dp(18)
        })

        val controlRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        val rewind = textControlButton("-10", "Back 10 seconds", dp(46)).apply {
            setOnClickListener {
                seekBy(-10_000)
                showControls()
            }
        }
        playPause = controlButton(R.drawable.ic_pause, "Pause", dp(50)).apply {
            setOnClickListener {
                togglePlayback()
                showControls()
            }
        }
        val forward = textControlButton("+10", "Forward 10 seconds", dp(46)).apply {
            setOnClickListener {
                seekBy(10_000)
                showControls()
            }
        }
        controlRow.addView(rewind, LinearLayout.LayoutParams(dp(46), dp(46)))
        controlRow.addView(playPause, LinearLayout.LayoutParams(dp(50), dp(50)).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
        })
        controlRow.addView(forward, LinearLayout.LayoutParams(dp(46), dp(46)))
        val controlsCenterArea = FrameLayout(this)
        segmentSkipButton = textPillButton("Skip segment", "Skip segment").apply {
            visibility = View.GONE
            setOnClickListener {
                skipCurrentSegment()
            }
        }
        segmentSkipSpacer = Space(this).apply { visibility = View.GONE }
        val centerStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(Space(this@PlayerActivity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(controlRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(segmentSkipSpacer, LinearLayout.LayoutParams(1, 0, 1f))
            addView(segmentSkipButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(Space(this@PlayerActivity), LinearLayout.LayoutParams(1, 0, 1f))
        }
        controlsCenterArea.addView(centerStack, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        (controlsOverlay as FrameLayout).addView(controlsCenterArea, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = controlsTopInset
            bottomMargin = controlsBottomInset
        })

        progressTrack = FrameLayout(this).apply {
            background = roundedBar(Color.argb(150, 255, 255, 255), dp(3))
        }
        progressFill = View(this).apply {
            background = roundedBar(Color.rgb(119, 226, 207), dp(3))
        }
        progressTrack.addView(progressFill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        (controlsOverlay as FrameLayout).addView(progressTrack, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6), Gravity.BOTTOM).apply {
            leftMargin = progressSideInset
            rightMargin = progressSideInset
            bottomMargin = progressBottomInset
        })

        seekBar = SeekBar(this).apply {
            max = SEEK_BAR_MAX
            progressTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            thumbTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        updateProgressBar(progress / SEEK_BAR_MAX.toFloat())
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    isScrubbing = true
                    showControls()
                    handler.removeCallbacks(controlsHider)
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    isScrubbing = false
                    seekToProgress(bar.progress)
                    showControls()
                }
            })
        }
        (controlsOverlay as FrameLayout).addView(seekBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44), Gravity.BOTTOM).apply {
            leftMargin = progressSideInset - dp(8)
            rightMargin = progressSideInset - dp(8)
            bottomMargin = seekBottomInset
        })

        return root
    }

    private fun initializePlayer() {
        usingTranscodeFallback = false
        hasReportedStart = false
        val view = findViewById<PlayerView>(PLAYER_VIEW_ID)
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            view.player = exoPlayer
            exoPlayer.setMediaItem(MediaItem.fromUri(api.directPlaybackUrl(jellyItem)), resumePositionMs())
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
                    updateControls()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateControls()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    updateControls()
                }

                override fun onPlayerError(error: PlaybackException) {
                    handlePlaybackError(exoPlayer, error)
                }
            })
            exoPlayer.prepare()
            exoPlayer.playWhenReady = shouldPlayWhenActive
        }
        handler.removeCallbacks(controlsProgressUpdater)
        handler.post(controlsProgressUpdater)
    }

    private fun handlePlaybackError(exoPlayer: ExoPlayer, error: PlaybackException) {
        val httpError = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
        if (!usingTranscodeFallback) {
            usingTranscodeFallback = true
            val reason = httpError?.responseCode?.let { "HTTP $it" } ?: error.errorCodeName
            Toast.makeText(this, "Direct playback unavailable ($reason). Trying transcoding…", Toast.LENGTH_LONG).show()
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.setMediaItem(MediaItem.fromUri(api.playbackUrl(jellyItem, playSessionId)), resumePositionMs())
            exoPlayer.prepare()
            exoPlayer.playWhenReady = shouldPlayWhenActive
            return
        }
        val message = if (httpError != null) {
            "Jellyfin returned HTTP ${httpError.responseCode}. Direct playback and transcoding both failed."
        } else {
            "Playback failed: ${error.errorCodeName}"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onStop() {
        handler.removeCallbacks(progressReporter)
        handler.removeCallbacks(controlsProgressUpdater)
        handler.removeCallbacks(controlsHider)
        report("Stopped")
        player?.let { exoPlayer ->
            jellyItem = jellyItem.copy(
                playbackTicks = exoPlayer.currentPosition * 10_000L
            )
            findViewById<PlayerView>(PLAYER_VIEW_ID).player = null
            exoPlayer.release()
        }
        player = null
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        reporter.shutdown()
        super.onDestroy()
    }

    private fun report(event: String) {
        val exoPlayer = player
        val positionTicks = (exoPlayer?.currentPosition ?: resumePositionMs()) * 10_000L
        val isPaused = exoPlayer?.playWhenReady != true
        val playMethod = if (usingTranscodeFallback) "Transcode" else "DirectPlay"
        val mediaSourceId = jellyItem.mediaSourceId ?: jellyItem.id
        reporter.execute {
            api.reportPlayback(event, jellyItem.id, playSessionId, positionTicks, isPaused, playMethod, mediaSourceId)
        }
    }

    private fun loadMediaSegments() {
        reporter.execute {
            val segments = runCatching { api.mediaSegments(jellyItem.id) }
                .onFailure { Log.w(TAG, "Could not load media segments for ${jellyItem.id}", it) }
                .getOrDefault(emptyList())
            Log.d(TAG, "Loaded ${segments.size} media segments for ${jellyItem.name}")
            handler.post {
                mediaSegments = segments
                updateControls()
            }
        }
    }

    private fun controlButton(drawableRes: Int, description: String, size: Int) = ImageButton(this).apply {
        setImageResource(drawableRes)
        contentDescription = description
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(9), dp(9), dp(9), dp(9))
        background = GradientDrawable().apply {
            setColor(Color.argb(205, 22, 19, 25))
            shape = GradientDrawable.OVAL
        }
        stateListAnimator = null
        minimumWidth = size
        minimumHeight = size
    }

    private fun textControlButton(label: String, description: String, size: Int) = TextView(this).apply {
        text = label
        contentDescription = description
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = GradientDrawable().apply {
            setColor(Color.argb(205, 22, 19, 25))
            shape = GradientDrawable.OVAL
        }
        minWidth = size
        minHeight = size
        isClickable = true
        isFocusable = true
    }

    private fun textPillButton(label: String, description: String) = TextView(this).apply {
        text = label
        contentDescription = description
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        setPadding(dp(16), 0, dp(16), 0)
        background = GradientDrawable().apply {
            setColor(Color.argb(220, 22, 19, 25))
            cornerRadius = dp(19).toFloat()
        }
        isClickable = true
        isFocusable = true
    }

    private fun roundedBar(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun toggleControls() {
        setControlsVisible(!controlsVisible)
    }

    private fun showControls() {
        setControlsVisible(true)
    }

    private fun toggleResizeMode() {
        stretchVideo = !stretchVideo
        val playerView = findViewById<PlayerView>(PLAYER_VIEW_ID)
        playerView.resizeMode = if (stretchVideo) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        updateResizeModeToggle()
    }

    private fun updateResizeModeToggle() {
        if (!::resizeModeToggle.isInitialized) return
        resizeModeToggle.setImageResource(if (stretchVideo) R.drawable.ic_stretch_screen else R.drawable.ic_fit_screen)
        resizeModeToggle.contentDescription = if (stretchVideo) {
            "Video sizing: stretch"
        } else {
            "Video sizing: fit"
        }
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        controlsOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        handler.removeCallbacks(controlsHider)
        if (visible && player?.playWhenReady == true && !isScrubbing) {
            handler.postDelayed(controlsHider, CONTROLS_TIMEOUT_MS)
        }
    }

    private fun togglePlayback() {
        val exoPlayer = player ?: return
        if (exoPlayer.playWhenReady) {
            shouldPlayWhenActive = false
            exoPlayer.pause()
        } else {
            shouldPlayWhenActive = true
            exoPlayer.playWhenReady = true
        }
        updateControls()
    }

    private fun seekBy(deltaMs: Long) {
        val exoPlayer = player ?: return
        val duration = exoPlayer.duration
        val upperBound = if (duration != C.TIME_UNSET && duration > 0) duration else Long.MAX_VALUE
        exoPlayer.seekTo((exoPlayer.currentPosition + deltaMs).coerceIn(0L, upperBound))
        updateControls()
    }

    private fun seekToProgress(progress: Int) {
        val exoPlayer = player ?: return
        val duration = exoPlayer.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val position = duration * progress / SEEK_BAR_MAX
        exoPlayer.seekTo(position)
        updateControls()
    }

    private fun skipCurrentSegment() {
        val segment = currentSegment ?: return
        val exoPlayer = player ?: return
        handler.removeCallbacks(controlsHider)
        exoPlayer.seekTo(segment.endTicks / 10_000L)
        setControlsVisible(false)
        currentSegment = null
    }

    private fun updateControls() {
        if (!::playPause.isInitialized || !::seekBar.isInitialized || !::progressFill.isInitialized) return
        val exoPlayer = player
        val wantsPlayback = exoPlayer?.playWhenReady == true
        playPause.setImageResource(if (wantsPlayback) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        playPause.contentDescription = if (wantsPlayback) "Pause" else "Play"
        val currentTicks = (exoPlayer?.currentPosition ?: 0L) * 10_000L
        updateSegmentSkipButton(currentTicks)
        val duration = exoPlayer?.duration ?: C.TIME_UNSET
        val canSeek = duration != C.TIME_UNSET && duration > 0
        seekBar.isEnabled = canSeek
        if (canSeek && !isScrubbing) {
            val currentPosition = exoPlayer?.currentPosition ?: 0L
            val progress = ((currentPosition.toDouble() / duration) * SEEK_BAR_MAX).roundToInt().coerceIn(0, SEEK_BAR_MAX)
            seekBar.progress = progress
            updateProgressBar(progress / SEEK_BAR_MAX.toFloat())
        } else if (!canSeek) {
            val runtimeMs = jellyItem.runtimeTicks / 10_000L
            val fallbackProgress = if (runtimeMs > 0L) {
                ((jellyItem.playbackTicks / 10_000.0) / runtimeMs).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }
            updateProgressBar(fallbackProgress)
        }
    }

    private fun updateSegmentSkipButton(positionTicks: Long) {
        if (!::segmentSkipButton.isInitialized) return
        val segment = mediaSegments.firstOrNull { segment ->
            positionTicks >= segment.startTicks && positionTicks < segment.endTicks
        }
        currentSegment = segment
        if (segment == null) {
            segmentSkipButton.visibility = View.GONE
            segmentSkipSpacer.visibility = View.GONE
            return
        }
        segmentSkipButton.text = segment.label
        segmentSkipButton.contentDescription = segment.label
        segmentSkipSpacer.visibility = View.VISIBLE
        if (segmentSkipButton.visibility != View.VISIBLE) {
            segmentSkipButton.visibility = View.VISIBLE
            showControls()
        }
    }

    private fun updateProgressBar(progress: Float) {
        if (!::progressTrack.isInitialized || !::progressFill.isInitialized) return
        progressTrack.post {
            val width = (progressTrack.width * progress.coerceIn(0f, 1f)).roundToInt()
            progressFill.layoutParams = progressFill.layoutParams.apply {
                this.width = width
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun resumePositionMs() = (jellyItem.playbackTicks / 10_000L).coerceAtLeast(0L)

    companion object {
        private const val PLAYER_VIEW_ID = 0x4A57
        private const val CONTROLS_TIMEOUT_MS = 3_500L
        private const val SEEK_BAR_MAX = 1000
        private const val TAG = "JellyWatchPlayer"
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
