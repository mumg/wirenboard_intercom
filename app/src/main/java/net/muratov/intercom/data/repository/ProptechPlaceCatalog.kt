package net.muratov.intercom.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.muratov.intercom.provider.myhome.MyHomeAccessControl
import net.muratov.intercom.provider.myhome.MyHomeCameraResource
import net.muratov.intercom.provider.myhome.MyHomeProviderService

data class ProptechPlaceData(
    val placeId: Long,
    val accessControls: List<MyHomeAccessControl>,
    val cameras: List<MyHomeCameraResource>,
)

/**
 * The single owner of stable Proptech place data.
 *
 * Providers only read an already initialized snapshot. They must not lazily
 * request access controls or camera lists while resolving an individual item.
 */
class ProptechPlaceCatalog(
    private val providerService: MyHomeProviderService,
) {
    private val initializationMutex = Mutex()

    @Volatile
    private var currentData: ProptechPlaceData? = null

    suspend fun initialize(placeId: Long) {
        initializationMutex.withLock {
            if (currentData?.placeId == placeId) return

            val accessControls = providerService.getPlaceAccessControls(placeId)
            val privateCameras = providerService.getPlaceCameras(placeId)
            val publicCameras = providerService.getPlacePublicCameras(placeId)

            currentData = ProptechPlaceData(
                placeId = placeId,
                accessControls = accessControls,
                cameras = privateCameras + publicCameras,
            )
        }
    }

    fun getInitialized(placeId: Long): ProptechPlaceData? {
        return currentData?.takeIf { it.placeId == placeId }
    }

    fun clear() {
        currentData = null
    }
}
