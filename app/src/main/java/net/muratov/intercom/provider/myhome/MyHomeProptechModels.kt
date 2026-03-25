package net.muratov.intercom.provider.myhome

data class MyHomeProptechConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "https://myhome.proptech.ru",
    val phone: String = "",
    val installationId: String = "intercom-android",
)

enum class MyHomeAuthStatus {
    Disabled,
    Idle,
    SelectingContext,
    RequestingCode,
    WaitingForCode,
    Authorizing,
    Authorized,
    Error,
}

data class MyHomeContextSelectionPrompt(
    val phone: String,
    val contexts: List<MyHomeLoginContext>,
    val message: String = "Выберите адрес для авторизации",
)

data class MyHomeVerificationPrompt(
    val phone: String,
    val address: String,
    val placeId: Long,
    val message: String = "Введите код подтверждения",
)

data class MyHomeTokens(
    val tokenType: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long? = null,
    val refreshExpiresIn: Long? = null,
    val operatorId: Int? = null,
    val operatorName: String? = null,
) {
    val authorizationHeader: String
        get() = "$tokenType $accessToken"
}

data class MyHomeProviderState(
    val status: MyHomeAuthStatus = MyHomeAuthStatus.Disabled,
    val contextSelectionPrompt: MyHomeContextSelectionPrompt? = null,
    val verificationPrompt: MyHomeVerificationPrompt? = null,
    val tokens: MyHomeTokens? = null,
    val selectedPlaceId: Long? = null,
    val message: String = "",
)

data class MyHomeOperator(
    val id: Int,
    val displayName: String,
    val authUrl: String,
    val infoUrl: String,
)

data class MyHomeLoginContext(
    val operatorId: Int,
    val subscriberId: String,
    val accountId: String,
    val placeId: Long,
    val address: String,
    val profileId: String,
)

data class MyHomeSubscriberPlace(
    val id: Long,
    val placeId: Long,
    val address: String,
    val provider: String,
    val blocked: Boolean,
)

data class MyHomeSubscriberProfile(
    val subscriberId: Long?,
    val subscriberName: String?,
    val pushUserId: String?,
)

data class MyHomeAccessControl(
    val id: Long,
    val operatorId: Int?,
    val name: String,
    val type: String?,
    val externalCameraId: String?,
    val allowOpen: Boolean,
    val allowVideo: Boolean,
    val previewAvailable: Boolean,
)

data class MyHomeAccessControlActionResult(
    val status: Boolean,
    val errorCode: String?,
    val errorMessage: String?,
)

data class MyHomeSipDevice(
    val id: String,
    val realm: String,
    val login: String,
    val password: String,
)

data class MyHomeCameraResource(
    val id: String,
    val title: String,
    val rawJson: String,
)

data class MyHomeFinanceInfo(
    val rawJson: String,
)
