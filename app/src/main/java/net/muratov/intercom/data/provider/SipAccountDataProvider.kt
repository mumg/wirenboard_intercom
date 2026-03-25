package net.muratov.intercom.data.provider

import net.muratov.intercom.data.model.SipAccountConfig
import net.muratov.intercom.data.model.SipAccountSourceConfig

interface SipAccountDataProvider {
    val type: String

    suspend fun resolve(source: SipAccountSourceConfig): SipAccountConfig?
}
