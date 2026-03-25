package net.muratov.intercom.data.model

data class RtspStream(
    val id: String,
    val title: String,
    val rtspUrl: String? = null,
    val rtspExtras: Map<String, String> = emptyMap(),
    val previewUrl: String? = null,
    val previewReloadPeriodMs: Long? = null,
    val previewExtras: Map<String, String> = emptyMap(),
    val openAction: ProviderOpenAction? = null,
)
