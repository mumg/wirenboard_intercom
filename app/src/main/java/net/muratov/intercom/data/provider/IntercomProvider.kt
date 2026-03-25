package net.muratov.intercom.data.provider

import net.muratov.intercom.data.model.ProviderOpenAction
import net.muratov.intercom.data.model.RtspStream
import net.muratov.intercom.data.model.SipAccountConfig
import net.muratov.intercom.data.model.SipAccountSourceConfig
import net.muratov.intercom.data.model.StreamSourceConfig

interface IntercomProvider {
    val type: String

    suspend fun resolveStream(source: StreamSourceConfig): RtspStream? = null

    suspend fun resolveSipAccount(source: SipAccountSourceConfig): SipAccountConfig? = null

    fun canOpen(action: ProviderOpenAction): Boolean = false

    suspend fun open(action: ProviderOpenAction): Boolean = false
}
