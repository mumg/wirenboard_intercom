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
import net.muratov.intercom.data.provider.IntercomProvider
import net.muratov.intercom.provider.myhome.MyHomeProviderService

class SipAccountRepository(
    private val sources: List<SipAccountSourceConfig>,
    private val providers: List<IntercomProvider>,
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
        val resolvedAccounts = sources.mapNotNull { source ->
            runCatching {
                var resolved: SipAccountConfig? = null
                for (provider in providers) {
                    if (provider.type != source.provider.type) continue
                    resolved = provider.resolveSipAccount(source)
                    if (resolved != null) break
                }
                resolved
            }.onFailure { error ->
                Log.w(TAG, "Unable to resolve SIP account ${source.id}", error)
            }.getOrNull()
        }
        Log.d(TAG, "refresh(): sources=${sources.size} resolved=${resolvedAccounts.size}")
        _accounts.value = resolvedAccounts
    }
}
