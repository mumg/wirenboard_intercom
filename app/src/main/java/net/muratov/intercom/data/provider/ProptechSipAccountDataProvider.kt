package net.muratov.intercom.data.provider

import net.muratov.intercom.data.model.SipAccountConfig
import net.muratov.intercom.data.model.SipAccountSourceConfig
import net.muratov.intercom.data.model.SipTransport
import net.muratov.intercom.provider.myhome.MyHomeAccessControl
import net.muratov.intercom.provider.myhome.MyHomeProviderService

class ProptechSipAccountDataProvider(
    private val providerService: MyHomeProviderService,
) : SipAccountDataProvider {
    override val type: String = "proptech"

    override suspend fun resolve(source: SipAccountSourceConfig): SipAccountConfig? {
        val state = providerService.state.value
        if (state.tokens == null) return null
        val placeId = state.selectedPlaceId ?: return null
        val accessControls = providerService.getPlaceAccessControls(placeId)
        val accessControl = selectAccessControl(source, accessControls) ?: return null
        val sipDevice = providerService.registerSipDevice(placeId, accessControl.id)
        val title = source.provider.title.ifBlank { accessControl.name }
        return SipAccountConfig(
            id = source.id,
            title = title,
            username = sipDevice.login,
            password = sipDevice.password,
            domain = sipDevice.realm,
            port = source.provider.port,
            transport = source.provider.transport.takeIf { it != SipTransport.UDP } ?: SipTransport.UDP,
            displayName = source.provider.displayName.ifBlank { title },
        )
    }

    private fun selectAccessControl(
        source: SipAccountSourceConfig,
        accessControls: List<MyHomeAccessControl>,
    ): MyHomeAccessControl? {
        return accessControls.firstOrNull { control ->
            source.provider.accessControlId != null && control.id == source.provider.accessControlId
        } ?: accessControls.firstOrNull { control ->
            control.name.equals(source.provider.title, ignoreCase = true)
        } ?: accessControls.firstOrNull { control ->
            control.id.toString() == source.id
        } ?: accessControls.firstOrNull()
    }
}
