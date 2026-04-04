package net.muratov.intercom.data.provider

import net.muratov.intercom.data.model.ProviderOpenAction
import net.muratov.intercom.data.model.SipAccountConfig
import net.muratov.intercom.data.model.SipIncomingPreview
import net.muratov.intercom.data.model.SipAccountSourceConfig
import net.muratov.intercom.data.model.SipTransport
import net.muratov.intercom.data.model.StreamPlaybackEngine
import net.muratov.intercom.logging.IntercomFileLogger
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
        if (state.tokens == null) {
            IntercomFileLogger.w(TAG, "resolveSipAccount skipped sourceId=${source.id}: tokens are null")
            return null
        }
        val placeId = state.selectedPlaceId ?: run {
            IntercomFileLogger.w(TAG, "resolveSipAccount skipped sourceId=${source.id}: selectedPlaceId is null")
            return null
        }
        IntercomFileLogger.i(
            TAG,
            "resolveSipAccount started sourceId=${source.id} title=${source.provider.title} placeId=$placeId",
        )
        val accessControls = providerService.getPlaceAccessControls(placeId)
        val accessControl = selectAccessControl(source, accessControls) ?: run {
            IntercomFileLogger.w(
                TAG,
                "resolveSipAccount failed sourceId=${source.id}: access control not found among ${accessControls.size} entries",
            )
            return null
        }
        IntercomFileLogger.i(
            TAG,
            "Selected accessControl id=${accessControl.id} name=${accessControl.name} externalCameraId=${accessControl.externalCameraId}",
        )
        val cameraResources = runCatching {
            providerService.getPlaceCameras(placeId) + providerService.getPlacePublicCameras(placeId)
        }.onFailure { error ->
            IntercomFileLogger.w(TAG, "Failed to load camera resources for placeId=$placeId", error)
        }.getOrDefault(emptyList())
        IntercomFileLogger.d(TAG, "Loaded cameraResources count=${cameraResources.size} for placeId=$placeId")
        val camera = selectCamera(source, cameraResources, accessControl)
        IntercomFileLogger.d(
            TAG,
            "Selected camera id=${camera?.id} title=${camera?.title} matchedByExternalCamera=${camera?.id == accessControl.externalCameraId}",
        )
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
            incomingPreviewHeaders = mapOf("Authorization" to state.tokens.authorizationHeader),
            incomingPreviewPlaybackEngine = StreamPlaybackEngine.EXO_PLAYER,
            openAction = ProviderOpenAction(
                providerType = type,
                targetId = accessControl.id.toString(),
                extras = mapOf("placeId" to placeId.toString()),
            ),
        )
    }

    override suspend fun resolveSipIncomingPreview(
        source: SipAccountSourceConfig,
        account: SipAccountConfig,
    ): SipIncomingPreview? {
        val state = providerService.state.value
        if (state.tokens == null) {
            IntercomFileLogger.w(TAG, "resolveSipIncomingPreview skipped accountId=${account.id}: tokens are null")
            return null
        }
        val placeId = state.selectedPlaceId ?: run {
            IntercomFileLogger.w(TAG, "resolveSipIncomingPreview skipped accountId=${account.id}: selectedPlaceId is null")
            return null
        }
        IntercomFileLogger.i(
            TAG,
            "resolveSipIncomingPreview started accountId=${account.id} sourceId=${source.id} placeId=$placeId",
        )
        val accessControls = providerService.getPlaceAccessControls(placeId)
        val accessControl = selectAccessControl(source, accessControls) ?: run {
            IntercomFileLogger.w(
                TAG,
                "resolveSipIncomingPreview failed accountId=${account.id}: access control not found among ${accessControls.size} entries",
            )
            return null
        }
        IntercomFileLogger.i(
            TAG,
            "resolveSipIncomingPreview selected accessControl id=${accessControl.id} name=${accessControl.name} externalCameraId=${accessControl.externalCameraId}",
        )
        val cameraResources = runCatching {
            providerService.getPlaceCameras(placeId) + providerService.getPlacePublicCameras(placeId)
        }.onFailure { error ->
            IntercomFileLogger.w(TAG, "resolveSipIncomingPreview failed to load camera resources for placeId=$placeId", error)
        }.getOrDefault(emptyList())
        val camera = selectCamera(source, cameraResources, accessControl)
        IntercomFileLogger.d(
            TAG,
            "resolveSipIncomingPreview selected camera id=${camera?.id} title=${camera?.title} matchedByExternalCamera=${camera?.id == accessControl.externalCameraId}",
        )
        val forpostRtspUrl = accessControl.externalCameraId
            ?.let { externalCameraId ->
                IntercomFileLogger.i(TAG, "Requesting proptech RTSP URL for externalCameraId=$externalCameraId")
                runCatching { providerService.getForpostCameraVideoUrl(externalCameraId) }
                    .onSuccess { url ->
                        IntercomFileLogger.i(
                            TAG,
                            "Received proptech RTSP URL for externalCameraId=$externalCameraId url=${url ?: "<null>"}",
                        )
                    }
                    .onFailure { error ->
                        IntercomFileLogger.w(
                            TAG,
                            "Failed to receive proptech RTSP URL for externalCameraId=$externalCameraId",
                            error,
                        )
                    }
                    .getOrNull()
            }
        val cameraRtspUrl = camera?.extractRtspUrl()
        if (forpostRtspUrl.isNullOrBlank()) {
            IntercomFileLogger.i(
                TAG,
                "Using camera resource RTSP fallback sourceId=${source.id} cameraId=${camera?.id} url=${cameraRtspUrl ?: "<null>"}",
            )
        }
        val rtspUrl = forpostRtspUrl ?: cameraRtspUrl
        IntercomFileLogger.i(
            TAG,
            "Resolved live incoming preview RTSP url for accountId=${account.id} sourceId=${source.id} url=${rtspUrl ?: "<null>"}",
        )
        val nonBlankRtspUrl = rtspUrl?.takeIf { it.isNotBlank() } ?: return null
        return SipIncomingPreview(
            rtspUrl = nonBlankRtspUrl,
            headers = mapOf("Authorization" to state.tokens.authorizationHeader),
            playbackEngine = StreamPlaybackEngine.EXO_PLAYER,
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
        private const val TAG = "ProptechSipAccount"
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
