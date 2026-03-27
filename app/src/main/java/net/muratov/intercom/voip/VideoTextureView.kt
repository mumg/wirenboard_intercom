package net.muratov.intercom.voip

import android.content.Context
import android.util.AttributeSet
import org.linphone.mediastream.video.capture.CaptureTextureView

class VideoTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : CaptureTextureView(context, attrs, defStyleAttr)
