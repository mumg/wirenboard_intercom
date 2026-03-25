package net.muratov.intercom.data.repository

import net.muratov.intercom.data.model.RtspStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StreamRepository(
    initialStreams: List<RtspStream>,
) {
    private val _streams = MutableStateFlow(initialStreams)

    val streams: StateFlow<List<RtspStream>> = _streams.asStateFlow()
}
