package net.muratov.intercom.video

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
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

    fun release()
}

private class ExoPlaybackView(
    context: Context,
) : PlayerView(context), StreamPlaybackView {
    private val exoPlayer = ExoPlayer.Builder(context).build()
    private var currentUrl: String? = null
    private var currentHeaders: Map<String, String> = emptyMap()
    private var currentMuted: Boolean? = null

    init {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        setPlayer(exoPlayer)
    }

    override fun asView(): View = this

    override fun play(url: String, headers: Map<String, String>, muted: Boolean) {
        if (currentMuted != muted) {
            currentMuted = muted
            exoPlayer.volume = if (muted) 0f else 1f
        }
        if (currentUrl == url && currentHeaders == headers) return

        currentUrl = url
        currentHeaders = headers

        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        val mediaItem = MediaItem.fromUri(Uri.parse(url))
        val mediaSource = createExoMediaSource(context, mediaItem, headers)
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    override fun release() {
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

    init {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        mediaPlayer.attachViews(this, null, false, true)
        attached = true
        mediaPlayer.setEventListener { event ->
            if (currentMuted == true &&
                (event.type == MediaPlayer.Event.Playing || event.type == MediaPlayer.Event.ESAdded)
            ) {
                mediaPlayer.setVolume(0)
                mediaPlayer.setAudioTrack(-1)
            }
        }
    }

    override fun asView(): View = this

    override fun play(url: String, headers: Map<String, String>, muted: Boolean) {
        if (currentMuted != muted) {
            currentMuted = muted
            mediaPlayer.setVolume(if (muted) 0 else 100)
            if (muted) {
                mediaPlayer.setAudioTrack(-1)
            }
        }
        if (currentUrl == url && currentHeaders == headers) return

        currentUrl = url
        currentHeaders = headers

        mediaPlayer.stop()

        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=150")
            addOption(":rtsp-tcp")
            if (muted) {
                addOption(":no-audio")
            }
        }
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
        mediaPlayer.setVolume(if (muted) 0 else 100)
        if (muted) {
            mediaPlayer.setAudioTrack(-1)
        }
    }

    override fun release() {
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
        .createMediaSource(mediaItem)
}

private const val TAG_STREAM_PLAYER = -71324501
