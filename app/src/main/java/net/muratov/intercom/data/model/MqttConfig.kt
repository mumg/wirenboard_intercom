package net.muratov.intercom.data.model

data class MqttConfig(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val clientId: String = "",
    val topicPrefix: String = "intercom",
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && clientId.isNotBlank()
}
