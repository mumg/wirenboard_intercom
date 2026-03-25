package net.muratov.intercom.data.model

data class ProviderOpenAction(
    val providerType: String,
    val targetId: String,
    val extras: Map<String, String> = emptyMap(),
)
