package net.muratov.intercom.data.repository

import net.muratov.intercom.data.model.SipAccountConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SipAccountRepository(
    initialAccounts: List<SipAccountConfig>,
) {
    private val _accounts = MutableStateFlow(initialAccounts)

    val accounts: StateFlow<List<SipAccountConfig>> = _accounts.asStateFlow()
}
