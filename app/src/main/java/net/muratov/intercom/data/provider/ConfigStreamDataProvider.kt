package net.muratov.intercom.data.provider

import net.muratov.intercom.data.model.RtspStream
import net.muratov.intercom.data.model.StreamSourceConfig

class ConfigStreamDataProvider(
    override val type: String = "rtsp",
) : StreamDataProvider {
    override suspend fun resolve(source: StreamSourceConfig): RtspStream? {
        val provider = source.provider
        val rtspUrl = provider.rtspUrl ?: provider.url
        val previewUrl = provider.previewUrl
        if (rtspUrl.isNullOrBlank() && previewUrl.isNullOrBlank()) return null
        return RtspStream(
            id = source.id,
            title = source.title,
            rtspUrl = rtspUrl,
            rtspExtras = provider.rtspExtras,
            previewUrl = previewUrl,
            previewReloadPeriodMs = provider.previewReloadPeriodMs,
            previewExtras = provider.previewExtras,
        )
    }
}
