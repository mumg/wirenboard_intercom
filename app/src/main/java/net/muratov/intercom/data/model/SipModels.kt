package net.muratov.intercom.data.model

enum class SipTransport {
    UDP,
    TCP,
    TLS,
}

enum class SipRegistrationStatus {
    Idle,
    Progress,
    Ok,
    Failed,
    Cleared,
}

data class SipAccountConfig(
    val id: String,
    val title: String,
    val username: String,
    val password: String,
    val domain: String,
    val port: Int = 5060,
    val transport: SipTransport = SipTransport.UDP,
    val displayName: String = title,
    val stunServer: String = "",
    val iceEnabled: Boolean = false,
    val ringtoneAsset: String? = null,
    val incomingPreviewRtspUrl: String? = null,
    val incomingPreviewHeaders: Map<String, String> = emptyMap(),
    val incomingPreviewPlaybackEngine: StreamPlaybackEngine = StreamPlaybackEngine.EXO_PLAYER,
    val openAction: ProviderOpenAction? = null,
)

data class SipAccountState(
    val config: SipAccountConfig,
    val status: SipRegistrationStatus = SipRegistrationStatus.Idle,
    val message: String = "",
)

enum class CallDirection {
    Incoming,
    Outgoing,
}

data class CallSession(
    val callId: String,
    val remoteDisplayName: String,
    val remoteAddress: String,
    val providerTitle: String? = null,
    val hasVideo: Boolean,
    val direction: CallDirection,
    val stateLabel: String,
    val openAction: ProviderOpenAction? = null,
)
