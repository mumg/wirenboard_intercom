package net.muratov.intercom.data.provider

import net.muratov.intercom.data.model.ProviderOpenAction
import net.muratov.intercom.data.model.SipAccountConfig
import net.muratov.intercom.data.model.SipAccountSourceConfig
import net.muratov.intercom.data.model.SipTransport
import net.muratov.intercom.provider.myhome.MyHomeAccessControl
import net.muratov.intercom.provider.myhome.MyHomeProviderService

class ProptechSipAccountDataProvider(
    private val providerService: MyHomeProviderService,
) : IntercomProvider {
    override val type: String = "proptech"

    override fun canOpen(action: ProviderOpenAction): Boolean {
        return action.providerType == type &&
            action.targetId.isNotBlank() &&
            action.extras["placeId"]?.toLongOrNull() != null
    }

    override suspend fun resolveSipAccount(source: SipAccountSourceConfig): SipAccountConfig? {
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
            openAction = ProviderOpenAction(
                providerType = type,
                targetId = accessControl.id.toString(),
                extras = mapOf("placeId" to placeId.toString()),
            ),
        )
    }

    override suspend fun open(action: ProviderOpenAction): Boolean {
        if (!canOpen(action)) return false
        val placeId = action.extras["placeId"]?.toLongOrNull() ?: return false
        val accessControlId = action.targetId.toLongOrNull() ?: return false
        return providerService.executeAccessControlAction(placeId, accessControlId).status
    }

    private fun selectAccessControl(
        source: SipAccountSourceConfig,
        accessControls: List<MyHomeAccessControl>,
    ): MyHomeAccessControl? {
        return accessControls.firstOrNull { control ->
            control.name.equals(source.provider.title, ignoreCase = true)
        } ?: accessControls.firstOrNull { control ->
            control.id.toString() == source.id
        } ?: accessControls.firstOrNull()
    }
}
