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
import net.muratov.intercom.data.model.SipAccountConfig
import net.muratov.intercom.data.model.SipAccountSourceConfig
import net.muratov.intercom.data.provider.SipAccountDataProvider
import net.muratov.intercom.provider.myhome.MyHomeProviderService

class SipAccountRepository(
    private val sources: List<SipAccountSourceConfig>,
    private val providers: List<SipAccountDataProvider>,
    myHomeProviderService: MyHomeProviderService,
) {
    companion object {
        private const val TAG = "SipAccountRepository"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _accounts = MutableStateFlow<List<SipAccountConfig>>(emptyList())

    val accounts: StateFlow<List<SipAccountConfig>> = _accounts.asStateFlow()

    init {
        scope.launch { refresh() }
        scope.launch {
            myHomeProviderService.state.collectLatest {
                refresh()
            }
        }
    }

    suspend fun refresh() {
        _accounts.value = sources.mapNotNull { source ->
            runCatching {
                providers.firstOrNull { it.type == source.provider.type }?.resolve(source)
            }.onFailure { error ->
                Log.w(TAG, "Unable to resolve SIP account ${source.id}", error)
            }.getOrNull()
        }
    }
}
