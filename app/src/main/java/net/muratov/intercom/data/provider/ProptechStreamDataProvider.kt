package net.muratov.intercom.data.provider

import net.muratov.intercom.data.model.ProviderOpenAction
import net.muratov.intercom.data.model.RtspStream
import net.muratov.intercom.data.model.StreamSourceConfig
import net.muratov.intercom.provider.myhome.MyHomeAccessControl
import net.muratov.intercom.provider.myhome.MyHomeCameraResource
import net.muratov.intercom.provider.myhome.MyHomeProviderService
import org.json.JSONObject

class ProptechStreamDataProvider(
    private val providerService: MyHomeProviderService,
) : IntercomProvider {
    override val type: String = "proptech"

    override fun canOpen(action: ProviderOpenAction): Boolean {
        return action.providerType == type &&
            action.targetId.isNotBlank() &&
            action.extras["placeId"]?.toLongOrNull() != null
    }

    override suspend fun resolveStream(source: StreamSourceConfig): RtspStream? {
        val state = providerService.state.value
        val tokens = state.tokens ?: return null
        val placeId = state.selectedPlaceId ?: return null

        val accessControls = providerService.getPlaceAccessControls(placeId)
        val accessControl = selectAccessControl(source, accessControls)
        val cameraResources = runCatching {
            providerService.getPlaceCameras(placeId) + providerService.getPlacePublicCameras(placeId)
        }.getOrDefault(emptyList())
        val camera = selectCamera(source, cameraResources, accessControl)

        val previewUrl = accessControl?.let {
            "${providerService.baseUrl}/rest/v1/places/$placeId/accesscontrols/${it.id}/videosnapshots"
        }
        val rtspUrl = accessControl?.externalCameraId
            ?.let { externalCameraId ->
                runCatching { providerService.getForpostCameraVideoUrl(externalCameraId) }.getOrNull()
            }
            ?: camera?.extractRtspUrl()
        if (rtspUrl.isNullOrBlank()) return null

        return RtspStream(
            id = source.id,
            title = source.title,
            rtspUrl = rtspUrl,
            rtspExtras = mapOf("Authorization" to tokens.authorizationHeader),
            previewUrl = previewUrl,
            previewReloadPeriodMs = source.provider.previewReloadPeriodMs ?: 15_000L,
            previewExtras = mapOf("Authorization" to tokens.authorizationHeader),
            openAction = accessControl?.let {
                ProviderOpenAction(
                    providerType = type,
                    targetId = it.id.toString(),
                    extras = mapOf("placeId" to placeId.toString()),
                )
            },
        )
    }

    override suspend fun open(action: ProviderOpenAction): Boolean {
        if (!canOpen(action)) return false
        val placeId = action.extras["placeId"]?.toLongOrNull() ?: return false
        val accessControlId = action.targetId.toLongOrNull() ?: return false
        return providerService.executeAccessControlAction(placeId, accessControlId).status
    }

    private fun selectAccessControl(
        source: StreamSourceConfig,
        accessControls: List<MyHomeAccessControl>,
    ): MyHomeAccessControl? {
        return accessControls.firstOrNull { control ->
            control.name.equals(source.title, ignoreCase = true)
        } ?: accessControls.firstOrNull { control ->
            control.id.toString() == source.id
        } ?: accessControls.firstOrNull { it.allowVideo || it.previewAvailable }
    }

    private fun selectCamera(
        source: StreamSourceConfig,
        cameras: List<MyHomeCameraResource>,
        accessControl: MyHomeAccessControl?,
    ): MyHomeCameraResource? {
        return cameras.firstOrNull { camera ->
            camera.matchesSelector(source.provider.url)
        } ?: cameras.firstOrNull { camera ->
            source.provider.cameraId != null && camera.id == source.provider.cameraId
        } ?: cameras.firstOrNull { camera ->
            accessControl?.externalCameraId != null && camera.id == accessControl.externalCameraId
        } ?: cameras.firstOrNull { camera ->
            camera.title.equals(source.title, ignoreCase = true)
        } ?: cameras.firstOrNull { camera ->
            camera.id == source.id
        } ?: cameras.singleOrNull()
    }
}

private fun MyHomeCameraResource.extractRtspUrl(): String? {
    val json = JSONObject(rawJson)
    return listOf("rtspUrl", "rtsp_url", "streamUrl", "url")
        .firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
}

private fun MyHomeCameraResource.matchesSelector(selector: String): Boolean {
    return id.equals(selector, ignoreCase = true) ||
        title.equals(selector, ignoreCase = true) ||
        extractRtspUrl().equals(selector, ignoreCase = true)
}
