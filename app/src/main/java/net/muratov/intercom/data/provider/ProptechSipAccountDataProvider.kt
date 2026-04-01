package net.muratov.intercom.data.provider

import net.muratov.intercom.data.model.ProviderOpenAction
import net.muratov.intercom.data.model.SipAccountConfig
import net.muratov.intercom.data.model.SipAccountSourceConfig
import net.muratov.intercom.data.model.SipTransport
import net.muratov.intercom.data.model.StreamPlaybackEngine
import net.muratov.intercom.provider.myhome.MyHomeAccessControl
import net.muratov.intercom.provider.myhome.MyHomeCameraResource
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
        val cameraResources = runCatching {
            providerService.getPlaceCameras(placeId) + providerService.getPlacePublicCameras(placeId)
        }.getOrDefault(emptyList())
        val camera = selectCamera(source, cameraResources, accessControl)
        val rtspUrl = accessControl.externalCameraId
            ?.let { externalCameraId ->
                runCatching { providerService.getForpostCameraVideoUrl(externalCameraId) }.getOrNull()
            }
            ?: camera?.extractRtspUrl()
        val sipDevice = providerService.registerSipDevice(placeId, accessControl.id)
        val title = source.provider.title.ifBlank { accessControl.name }
        val username = source.provider.username.ifBlank { sipDevice.login }
        val password = source.provider.password.ifBlank { sipDevice.password }
        val domain = source.provider.domain.ifBlank { sipDevice.realm }
        return SipAccountConfig(
            id = source.id,
            title = title,
            username = username,
            password = password,
            domain = domain,
            port = source.provider.port,
            transport = source.provider.transport.takeIf { it != SipTransport.UDP } ?: SipTransport.UDP,
            displayName = source.provider.displayName.ifBlank { title },
            stunServer = source.provider.stunServer.ifBlank { PROPTECH_STUN_SERVER },
            iceEnabled = source.provider.iceEnabled ?: PROPTECH_ICE_ENABLED,
            ringtoneAsset = source.provider.ringtoneAsset,
            incomingPreviewRtspUrl = rtspUrl,
            incomingPreviewHeaders = mapOf("Authorization" to state.tokens.authorizationHeader),
            incomingPreviewPlaybackEngine = StreamPlaybackEngine.EXO_PLAYER,
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

    private fun selectCamera(
        source: SipAccountSourceConfig,
        cameras: List<MyHomeCameraResource>,
        accessControl: MyHomeAccessControl,
    ): MyHomeCameraResource? {
        return cameras.firstOrNull { camera ->
            source.provider.title.isNotBlank() && camera.title.equals(source.provider.title, ignoreCase = true)
        } ?: cameras.firstOrNull { camera ->
            accessControl.externalCameraId != null && camera.id == accessControl.externalCameraId
        } ?: cameras.firstOrNull { camera ->
            camera.id == source.id
        } ?: cameras.singleOrNull()
    }

    private companion object {
        private const val PROPTECH_STUN_SERVER = "stun.sipnet.ru:3478"
        private const val PROPTECH_ICE_ENABLED = true
    }
}

private fun MyHomeCameraResource.extractRtspUrl(): String? {
    return org.json.JSONObject(rawJson)
        .let { json ->
            listOf("rtspUrl", "rtsp_url", "streamUrl", "url")
                .firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
        }
}
