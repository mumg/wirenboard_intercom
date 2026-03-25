package net.muratov.intercom.provider.myhome

import kotlinx.coroutines.flow.StateFlow

interface MyHomeProviderService {
    val baseUrl: String
    val state: StateFlow<MyHomeProviderState>

    fun start()
    fun selectLoginContext(context: MyHomeLoginContext)
    fun submitVerificationCode(code: String, confirmationSecret: String)
    fun dismissVerificationPrompt()

    suspend fun getOperators(): List<MyHomeOperator>
    suspend fun getLoginContextsByPhone(phone: String = ""): List<MyHomeLoginContext>
    suspend fun registerSubscriberNotifications(pushToken: String): Boolean
    suspend fun getSubscriberPlaces(placeId: Long? = null): List<MyHomeSubscriberPlace>
    suspend fun getSubscriberProfile(): MyHomeSubscriberProfile?
    suspend fun getPlaceAccessControls(placeId: Long): List<MyHomeAccessControl>
    suspend fun getAccessControlVideoSnapshot(placeId: Long, accessControlId: Long): ByteArray
    suspend fun executeAccessControlAction(
        placeId: Long,
        accessControlId: Long,
        action: String = "accessControlOpen",
    ): MyHomeAccessControlActionResult

    suspend fun registerSipDevice(placeId: Long, accessControlId: Long): MyHomeSipDevice
    suspend fun getPlaceCameras(placeId: Long): List<MyHomeCameraResource>
    suspend fun getPlacePublicCameras(placeId: Long): List<MyHomeCameraResource>
    suspend fun getAvailableStompFeatures(): String?
    suspend fun getPlaceFinance(placeId: Long): MyHomeFinanceInfo?
}
