package net.muratov.intercom.data.model

enum class StreamPlaybackEngine {
    VLC,
    EXO_PLAYER,
}

data class RtspStream(
    val id: String,
    val title: String,
    val rtspUrl: String,
    val playbackEngine: StreamPlaybackEngine = StreamPlaybackEngine.VLC,
    val rtspExtras: Map<String, String> = emptyMap(),
    val previewUrl: String? = null,
    val previewReloadPeriodMs: Long? = null,
    val previewExtras: Map<String, String> = emptyMap(),
    val openAction: ProviderOpenAction? = null,
)
