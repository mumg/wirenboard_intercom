package net.muratov.intercom.data.provider

import net.muratov.intercom.data.model.RtspStream
import net.muratov.intercom.data.model.StreamSourceConfig
import net.muratov.intercom.data.model.StreamPlaybackEngine

class ConfigStreamDataProvider(
    override val type: String = "config",
) : IntercomProvider {
    override suspend fun resolveStream(source: StreamSourceConfig): RtspStream? {
        val provider = source.provider
        val previewUrl = provider.previewUrl
        return RtspStream(
            id = source.id,
            title = source.title,
            rtspUrl = provider.url,
            playbackEngine = StreamPlaybackEngine.VLC,
            rtspExtras = provider.rtspExtras,
            previewUrl = previewUrl,
            previewReloadPeriodMs = provider.previewReloadPeriodMs,
            previewExtras = provider.previewExtras,
        )
    }
}
