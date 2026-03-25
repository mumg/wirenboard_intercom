package net.muratov.intercom.data.provider

import net.muratov.intercom.data.model.SipAccountConfig
import net.muratov.intercom.data.model.SipAccountSourceConfig

class ConfigSipAccountDataProvider : IntercomProvider {
    override val type: String = "config"

    override suspend fun resolveSipAccount(source: SipAccountSourceConfig): SipAccountConfig? {
        val provider = source.provider
        if (provider.username.isBlank() || provider.password.isBlank() || provider.domain.isBlank()) {
            return null
        }
        val title = provider.title.ifBlank { source.id }
        return SipAccountConfig(
            id = source.id,
            title = title,
            username = provider.username,
            password = provider.password,
            domain = provider.domain,
            port = provider.port,
            transport = provider.transport,
            displayName = provider.displayName.ifBlank { title },
        )
    }
}
