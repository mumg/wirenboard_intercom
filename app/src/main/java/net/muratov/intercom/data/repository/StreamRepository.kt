package net.muratov.intercom.data.repository

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.muratov.intercom.data.model.RtspStream
import net.muratov.intercom.data.model.StreamSourceConfig
import net.muratov.intercom.data.provider.IntercomProvider
import net.muratov.intercom.provider.myhome.MyHomeProviderService

class StreamRepository(
    private val sources: List<StreamSourceConfig>,
    private val providers: List<IntercomProvider>,
    myHomeProviderService: MyHomeProviderService,
) {
    companion object {
        private const val TAG = "StreamRepository"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _streams = MutableStateFlow<List<RtspStream>>(emptyList())

    val streams: StateFlow<List<RtspStream>> = _streams.asStateFlow()

    init {
        scope.launch { refresh() }
        scope.launch {
            myHomeProviderService.state.collectLatest {
                refresh()
            }
        }
    }

    suspend fun refresh() {
        _streams.value = sources.mapNotNull { source ->
            runCatching {
                var resolved: RtspStream? = null
                for (provider in providers) {
                    if (provider.type != source.provider.type) continue
                    resolved = provider.resolveStream(source)
                    if (resolved != null) break
                }
                resolved
            }.onFailure { error ->
                Log.w(TAG, "Unable to resolve stream ${source.id}", error)
            }.getOrNull()
        }
    }
}
