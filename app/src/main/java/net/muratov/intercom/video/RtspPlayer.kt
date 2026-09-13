package net.muratov.intercom.video

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import net.muratov.intercom.BuildConfig
import net.muratov.intercom.data.model.StreamPlaybackEngine
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

@Composable
fun RtspPlayer(
    url: String,
    playbackEngine: StreamPlaybackEngine,
    headers: Map<String, String> = emptyMap(),
    muted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            createPlaybackView(context, playbackEngine).apply {
                play(url, headers, muted)
            }.asView()
        },
        update = { view ->
            (view.getTag(TAG_STREAM_PLAYER) as? StreamPlaybackView)?.play(url, headers, muted)
        },
        onRelease = { view ->
            (view.getTag(TAG_STREAM_PLAYER) as? StreamPlaybackView)?.release()
        },
    )
}

fun createRtspPlaybackView(
    context: Context,
    playbackEngine: StreamPlaybackEngine,
): View {
    return createPlaybackView(context, playbackEngine).asView()
}

fun playRtspOnView(
    view: View,
    url: String,
    headers: Map<String, String> = emptyMap(),
    muted: Boolean = false,
) {
    (view.getTag(TAG_STREAM_PLAYER) as? StreamPlaybackView)?.play(url, headers, muted)
}

fun setRtspPlaybackCallbacks(
    view: View,
    onPlaybackStarted: (() -> Unit)?,
    onNewUrlRequired: (() -> Unit)?,
) {
    (view.getTag(TAG_STREAM_PLAYER) as? StreamPlaybackView)?.setPlaybackCallbacks(
        onPlaybackStarted = onPlaybackStarted,
        onNewUrlRequired = onNewUrlRequired,
    )
}

fun releaseRtspPlaybackView(view: View) {
    (view.getTag(TAG_STREAM_PLAYER) as? StreamPlaybackView)?.release()
}

private fun createPlaybackView(
    context: Context,
    playbackEngine: StreamPlaybackEngine,
): StreamPlaybackView {
    val playbackView = when (playbackEngine) {
        StreamPlaybackEngine.VLC -> VlcPlaybackView(context)
        StreamPlaybackEngine.EXO_PLAYER -> ExoPlaybackView(context)
    }
    playbackView.asView().setTag(TAG_STREAM_PLAYER, playbackView)
    return playbackView
}

private interface StreamPlaybackView {
    fun asView(): View

    fun play(url: String, headers: Map<String, String>, muted: Boolean)

    fun setPlaybackCallbacks(
        onPlaybackStarted: (() -> Unit)?,
        onNewUrlRequired: (() -> Unit)?,
    )

    fun release()
}

private class ExoPlaybackView(
    context: Context,
) : PlayerView(context), StreamPlaybackView {
    private val exoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 0,
                    /* maxBufferMs = */ 0,
                    /* bufferForPlaybackMs = */ 0,
                    /* bufferForPlaybackAfterRebufferMs = */ 0,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build(),
        )
        .build()
    private var currentUrl: String? = null
    private var currentHeaders: Map<String, String> = emptyMap()
    private var currentMuted: Boolean? = null
    private var released = false
    private var reconnectScheduled = false
    private var onPlaybackStarted: (() -> Unit)? = null
    private var onNewUrlRequired: (() -> Unit)? = null
    private val reconnectRunnable = Runnable {
        reconnectScheduled = false
        if (!released && !currentUrl.isNullOrBlank()) {
            val newUrlCallback = onNewUrlRequired
            if (newUrlCallback == null) {
                startPlayback()
            } else {
                currentUrl = null
                currentHeaders = emptyMap()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                newUrlCallback()
            }
        }
    }
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    cancelReconnect()
                    onPlaybackStarted?.invoke()
                }
                Player.STATE_BUFFERING -> scheduleReconnect(CONNECTION_TIMEOUT_MS, replacePending = false)
                Player.STATE_ENDED -> scheduleReconnect(RECONNECT_DELAY_MS)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "ExoPlayer stream failed; reconnect scheduled", error)
            scheduleReconnect(RECONNECT_DELAY_MS)
        }
    }

    init {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        exoPlayer.addListener(playerListener)
        setPlayer(exoPlayer)
    }

    override fun asView(): View = this

    override fun play(url: String, headers: Map<String, String>, muted: Boolean) {
        if (currentMuted != muted) {
            currentMuted = muted
            exoPlayer.volume = if (muted) 0f else 1f
        }
        if (currentUrl == url && currentHeaders == headers) return

        // A blank URL is used while a fresh short-lived provider URL is being
        // requested. Creating the player now lets player initialization run in
        // parallel with that request without ever playing the previous URL.
        if (url.isBlank()) {
            cancelReconnect()
            if (currentUrl != null) {
                currentUrl = null
                currentHeaders = emptyMap()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
            return
        }

        currentUrl = url
        currentHeaders = headers
        startPlayback()
    }

    override fun setPlaybackCallbacks(
        onPlaybackStarted: (() -> Unit)?,
        onNewUrlRequired: (() -> Unit)?,
    ) {
        this.onPlaybackStarted = onPlaybackStarted
        this.onNewUrlRequired = onNewUrlRequired
    }

    private fun startPlayback() {
        val url = currentUrl?.takeIf { it.isNotBlank() } ?: return
        cancelReconnect()

        try {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()

            val mediaItem = MediaItem.fromUri(Uri.parse(url))
            val mediaSource = createExoMediaSource(context, mediaItem, currentHeaders)
            exoPlayer.setMediaSource(mediaSource)
            scheduleReconnect(CONNECTION_TIMEOUT_MS)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } catch (error: Exception) {
            Log.w(TAG, "Unable to start ExoPlayer stream; reconnect scheduled", error)
            scheduleReconnect(RECONNECT_DELAY_MS)
        }
    }

    private fun scheduleReconnect(delayMs: Long, replacePending: Boolean = true) {
        if (released || currentUrl.isNullOrBlank()) return
        if (reconnectScheduled && !replacePending) return
        removeCallbacks(reconnectRunnable)
        reconnectScheduled = true
        postDelayed(reconnectRunnable, delayMs)
    }

    private fun cancelReconnect() {
        removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
    }

    override fun release() {
        released = true
        cancelReconnect()
        onPlaybackStarted = null
        onNewUrlRequired = null
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
    }
}

private class VlcPlaybackView(
    context: Context,
) : VLCVideoLayout(context), StreamPlaybackView {
    private val libVlc = LibVLC(
        context,
        arrayListOf(
            "--network-caching=150",
            "--rtsp-tcp",
            "--no-video-title-show",
        ),
    )
    private val mediaPlayer = MediaPlayer(libVlc)
    private var currentUrl: String? = null
    private var currentHeaders: Map<String, String> = emptyMap()
    private var currentMuted: Boolean? = null
    private var attached = false
    private var released = false
    private var reconnectScheduled = false
    private var onPlaybackStarted: (() -> Unit)? = null
    private var onNewUrlRequired: (() -> Unit)? = null
    private val reconnectRunnable = Runnable {
        reconnectScheduled = false
        if (!released && !currentUrl.isNullOrBlank()) {
            val newUrlCallback = onNewUrlRequired
            if (newUrlCallback == null) {
                startPlayback()
            } else {
                currentUrl = null
                currentHeaders = emptyMap()
                mediaPlayer.stop()
                newUrlCallback()
            }
        }
    }

    init {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        mediaPlayer.attachViews(this, null, false, true)
        attached = true
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    cancelReconnect()
                    applyVolume()
                    onPlaybackStarted?.invoke()
                }

                MediaPlayer.Event.ESAdded -> applyVolume()
                MediaPlayer.Event.Buffering -> scheduleReconnect(CONNECTION_TIMEOUT_MS, replacePending = false)
                MediaPlayer.Event.EndReached,
                MediaPlayer.Event.EncounteredError,
                -> scheduleReconnect(RECONNECT_DELAY_MS)
            }
        }
    }

    override fun asView(): View = this

    override fun play(url: String, headers: Map<String, String>, muted: Boolean) {
        if (currentMuted != muted) {
            currentMuted = muted
            applyVolume()
        }
        if (currentUrl == url && currentHeaders == headers) return

        currentUrl = url
        currentHeaders = headers
        if (url.isBlank()) {
            cancelReconnect()
            mediaPlayer.stop()
            return
        }
        startPlayback()
    }

    override fun setPlaybackCallbacks(
        onPlaybackStarted: (() -> Unit)?,
        onNewUrlRequired: (() -> Unit)?,
    ) {
        this.onPlaybackStarted = onPlaybackStarted
        this.onNewUrlRequired = onNewUrlRequired
    }

    private fun startPlayback() {
        val url = currentUrl?.takeIf { it.isNotBlank() } ?: return
        cancelReconnect()

        try {
            mediaPlayer.stop()
            val media = Media(libVlc, Uri.parse(url)).apply {
                setHWDecoderEnabled(true, false)
                addOption(":network-caching=150")
                addOption(":rtsp-tcp")
                if (currentMuted == true) {
                    addOption(":no-audio")
                }
            }
            mediaPlayer.media = media
            media.release()
            scheduleReconnect(CONNECTION_TIMEOUT_MS)
            mediaPlayer.play()
            applyVolume()
        } catch (error: Exception) {
            Log.w(TAG, "Unable to start VLC stream; reconnect scheduled", error)
            scheduleReconnect(RECONNECT_DELAY_MS)
        }
    }

    private fun applyVolume() {
        val muted = currentMuted == true
        mediaPlayer.setVolume(if (muted) 0 else 100)
        if (muted) {
            mediaPlayer.setAudioTrack(-1)
        }
    }

    private fun scheduleReconnect(delayMs: Long, replacePending: Boolean = true) {
        if (released || currentUrl.isNullOrBlank()) return
        if (reconnectScheduled && !replacePending) return
        removeCallbacks(reconnectRunnable)
        reconnectScheduled = true
        postDelayed(reconnectRunnable, delayMs)
    }

    private fun cancelReconnect() {
        removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
    }

    override fun release() {
        released = true
        cancelReconnect()
        onPlaybackStarted = null
        onNewUrlRequired = null
        mediaPlayer.setEventListener(null)
        mediaPlayer.stop()
        if (attached) {
            mediaPlayer.detachViews()
            attached = false
        }
        mediaPlayer.release()
        libVlc.release()
    }
}

private fun createExoMediaSource(
    context: Context,
    mediaItem: MediaItem,
    headers: Map<String, String>,
) = if (mediaItem.localConfiguration?.uri?.scheme?.equals("rtsp", ignoreCase = true) == true) {
    RtspMediaSource.Factory()
        .setForceUseRtpTcp(true)
        .setUserAgent("Intercom/${BuildConfig.VERSION_NAME}")
        .setDebugLoggingEnabled(BuildConfig.DEBUG)
        .createMediaSource(mediaItem)
} else {
    val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("Intercom/${BuildConfig.VERSION_NAME}")
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(headers)
    DefaultMediaSourceFactory(context)
        .setDataSourceFactory(httpDataSourceFactory)
        .setLiveTargetOffsetMs(0)
        .setLiveMinOffsetMs(0)
        .createMediaSource(mediaItem)
}

private const val TAG = "RtspPlayer"
private const val TAG_STREAM_PLAYER = -71324501
private const val RECONNECT_DELAY_MS = 5_000L
private const val CONNECTION_TIMEOUT_MS = 15_000L
