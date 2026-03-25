package net.muratov.intercom.data.model

data class AppConfig(
    val webViewUrl: String = "about:blank",
    val streams: List<RtspStream> = emptyList(),
    val sipAccounts: List<SipAccountConfig> = emptyList(),
)
