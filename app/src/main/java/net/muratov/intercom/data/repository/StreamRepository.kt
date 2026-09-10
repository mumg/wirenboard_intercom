package net.muratov.intercom.data.repository

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.muratov.intercom.data.model.RtspStream
import net.muratov.intercom.data.model.StreamSourceConfig
import net.muratov.intercom.data.provider.IntercomProvider

class StreamRepository(
    private val sources: List<StreamSourceConfig>,
    private val providers: List<IntercomProvider>,
) {
    companion object {
        private const val TAG = "StreamRepository"
    }

    private val _streams = MutableStateFlow<List<RtspStream>>(emptyList())

    val streams: StateFlow<List<RtspStream>> = _streams.asStateFlow()

    suspend fun refresh() {
        _streams.value = sources.mapNotNull { source ->
            runCatching {
                resolveSourceConfiguration(source)
            }.onFailure { error ->
                Log.w(TAG, "Unable to resolve stream ${source.id}", error)
            }.getOrNull()
        }
    }

    suspend fun resolveStream(streamId: String): RtspStream? {
        val source = sources.firstOrNull { it.id == streamId } ?: return null
        return runCatching {
            resolveSource(source)
        }.onFailure { error ->
            Log.w(TAG, "Unable to resolve fullscreen stream ${source.id}", error)
        }.getOrNull()
    }

    private suspend fun resolveSource(source: StreamSourceConfig): RtspStream? {
        for (provider in providers) {
            if (provider.type != source.provider.type) continue
            val resolved = provider.resolveStream(source)
            if (resolved != null) return resolved
        }
        return null
    }

    private suspend fun resolveSourceConfiguration(source: StreamSourceConfig): RtspStream? {
        for (provider in providers) {
            if (provider.type != source.provider.type) continue
            val resolved = provider.resolveStreamConfiguration(source)
            if (resolved != null) return resolved
        }
        return null
    }
}
