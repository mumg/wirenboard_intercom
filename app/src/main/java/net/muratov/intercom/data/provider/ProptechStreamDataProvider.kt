package net.muratov.intercom.data.provider

import net.muratov.intercom.data.model.RtspStream
import net.muratov.intercom.data.model.StreamSourceConfig
import net.muratov.intercom.provider.myhome.MyHomeAccessControl
import net.muratov.intercom.provider.myhome.MyHomeCameraResource
import net.muratov.intercom.provider.myhome.MyHomeProviderService
import org.json.JSONObject

class ProptechStreamDataProvider(
    private val providerService: MyHomeProviderService,
) : StreamDataProvider {
    override val type: String = "proptech"

    override suspend fun resolve(source: StreamSourceConfig): RtspStream? {
        val state = providerService.state.value
        val tokens = state.tokens ?: return null
        val placeId = state.selectedPlaceId ?: return null

        val accessControls = providerService.getPlaceAccessControls(placeId)
        val accessControl = selectAccessControl(source, accessControls)
        val cameraResources = runCatching {
            providerService.getPlaceCameras(placeId) + providerService.getPlacePublicCameras(placeId)
        }.getOrDefault(emptyList())
        val camera = selectCamera(source, cameraResources)

        val previewUrl = accessControl?.let {
            "${providerService.baseUrl}/rest/v1/places/$placeId/accesscontrols/${it.id}/videosnapshots"
        }
        val rtspUrl = camera?.extractRtspUrl()
        if (previewUrl == null && rtspUrl == null) return null

        return RtspStream(
            id = source.id,
            title = source.title,
            rtspUrl = rtspUrl,
            rtspExtras = buildMap {
                if (rtspUrl != null) {
                    put("Authorization", tokens.authorizationHeader)
                }
            },
            previewUrl = previewUrl,
            previewReloadPeriodMs = source.provider.previewReloadPeriodMs ?: 15_000L,
            previewExtras = mapOf("Authorization" to tokens.authorizationHeader),
        )
    }

    private fun selectAccessControl(
        source: StreamSourceConfig,
        accessControls: List<MyHomeAccessControl>,
    ): MyHomeAccessControl? {
        return accessControls.firstOrNull { control ->
            source.provider.accessControlId != null && control.id == source.provider.accessControlId
        } ?: accessControls.firstOrNull { control ->
            control.name.equals(source.title, ignoreCase = true)
        } ?: accessControls.firstOrNull { control ->
            control.id.toString() == source.id
        } ?: accessControls.firstOrNull { it.allowVideo || it.previewAvailable }
    }

    private fun selectCamera(
        source: StreamSourceConfig,
        cameras: List<MyHomeCameraResource>,
    ): MyHomeCameraResource? {
        return cameras.firstOrNull { camera ->
            source.provider.cameraId != null && camera.id == source.provider.cameraId
        } ?: cameras.firstOrNull { camera ->
            camera.title.equals(source.title, ignoreCase = true)
        } ?: cameras.firstOrNull { camera ->
            camera.id == source.id
        }
    }
}

private fun MyHomeCameraResource.extractRtspUrl(): String? {
    val json = JSONObject(rawJson)
    return listOf("rtspUrl", "rtsp_url", "streamUrl", "url")
        .firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
}
