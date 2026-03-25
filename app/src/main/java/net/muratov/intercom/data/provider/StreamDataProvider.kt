package net.muratov.intercom.data.provider

import net.muratov.intercom.data.model.RtspStream
import net.muratov.intercom.data.model.StreamSourceConfig

interface StreamDataProvider {
    val type: String

    suspend fun resolve(source: StreamSourceConfig): RtspStream?
}
