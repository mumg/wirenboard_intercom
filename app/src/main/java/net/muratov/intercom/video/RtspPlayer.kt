package net.muratov.intercom.video

import android.content.Context
import android.net.Uri
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

@Composable
fun RtspPlayer(
    url: String,
    muted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            RtspPlayerView(context).apply {
                play(url, muted)
            }
        },
        update = { view ->
            view.play(url, muted)
        },
    )
}

private class RtspPlayerView(context: Context) : VLCVideoLayout(context) {
    private val libVlc = LibVLC(context, arrayListOf("--no-drop-late-frames", "--no-skip-frames"))
    private val mediaPlayer = MediaPlayer(libVlc)
    private var currentUrl: String? = null
    private var currentMuted: Boolean? = null

    init {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        // Use TextureView instead of SurfaceView so RTSP previews can coexist with WebView
        // in the same Compose layout without surface Z-order conflicts.
        mediaPlayer.attachViews(this, null, false, true)
        mediaPlayer.setEventListener { event ->
            if (currentMuted == true && event.type in setOf(MediaPlayer.Event.Playing, MediaPlayer.Event.ESAdded)) {
                mediaPlayer.setVolume(0)
                mediaPlayer.setAudioTrack(-1)
            }
        }
    }

    fun play(url: String, muted: Boolean) {
        if (currentUrl == url && currentMuted == muted && mediaPlayer.isPlaying) {
            return
        }

        currentMuted = muted
        mediaPlayer.setVolume(if (muted) 0 else 100)

        if (currentUrl == url && mediaPlayer.isPlaying) {
            if (muted) {
                mediaPlayer.setAudioTrack(-1)
            }
            return
        }

        currentUrl = url
        mediaPlayer.stop()
        mediaPlayer.media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=200")
            addOption(":live-caching=150")
            addOption(":rtsp-tcp")
            if (muted) {
                addOption(":no-audio")
            }
        }
        mediaPlayer.setVolume(if (muted) 0 else 100)
        mediaPlayer.play()
        if (muted) {
            mediaPlayer.setAudioTrack(-1)
        }
    }

    override fun onDetachedFromWindow() {
        mediaPlayer.stop()
        mediaPlayer.detachViews()
        mediaPlayer.release()
        libVlc.release()
        super.onDetachedFromWindow()
    }
}
