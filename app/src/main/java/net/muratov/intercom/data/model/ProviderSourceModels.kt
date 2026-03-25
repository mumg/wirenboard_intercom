package net.muratov.intercom.data.model

data class StreamSourceConfig(
    val id: String,
    val title: String,
    val provider: StreamProviderConfig,
)

data class StreamProviderConfig(
    val type: String,
    val url: String? = null,
    val rtspUrl: String? = null,
    val rtspExtras: Map<String, String> = emptyMap(),
    val previewUrl: String? = null,
    val previewReloadPeriodMs: Long? = null,
    val previewExtras: Map<String, String> = emptyMap(),
    val accessControlId: Long? = null,
    val cameraId: String? = null,
)

data class SipAccountSourceConfig(
    val id: String,
    val provider: SipAccountProviderConfig,
)

data class SipAccountProviderConfig(
    val type: String,
    val title: String = "",
    val displayName: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val port: Int = 5060,
    val transport: SipTransport = SipTransport.UDP,
    val accessControlId: Long? = null,
)
