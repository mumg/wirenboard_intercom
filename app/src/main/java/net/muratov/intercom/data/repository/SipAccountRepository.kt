package net.muratov.intercom.data.repository

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.muratov.intercom.data.model.SipAccountConfig
import net.muratov.intercom.data.model.SipIncomingPreview
import net.muratov.intercom.data.model.SipAccountSourceConfig
import net.muratov.intercom.data.provider.IntercomProvider

class SipAccountRepository(
    private val sources: List<SipAccountSourceConfig>,
    private val providers: List<IntercomProvider>,
) {
    companion object {
        private const val TAG = "SipAccountRepository"
    }

    private val _accounts = MutableStateFlow<List<SipAccountConfig>>(emptyList())

    val accounts: StateFlow<List<SipAccountConfig>> = _accounts.asStateFlow()

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

    suspend fun resolveIncomingPreview(accountId: String): SipIncomingPreview? {
        val source = sources.firstOrNull { it.id == accountId } ?: run {
            Log.w(TAG, "resolveIncomingPreview(): source not found for accountId=$accountId")
            return null
        }
        val account = _accounts.value.firstOrNull { it.id == accountId } ?: run {
            Log.w(TAG, "resolveIncomingPreview(): account not found for accountId=$accountId")
            return null
        }
        for (provider in providers) {
            if (provider.type != source.provider.type) continue
            val preview = runCatching {
                provider.resolveSipIncomingPreview(source, account)
            }.onFailure { error ->
                Log.w(TAG, "resolveIncomingPreview(): provider=${provider.type} failed for accountId=$accountId", error)
            }.getOrNull()
            if (preview != null) {
                Log.d(TAG, "resolveIncomingPreview(): resolved preview for accountId=$accountId provider=${provider.type}")
                return preview
            }
        }
        Log.d(TAG, "resolveIncomingPreview(): no preview for accountId=$accountId")
        return null
    }
}
