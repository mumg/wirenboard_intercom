package net.muratov.intercom.data.repository

import android.content.Context
import net.muratov.intercom.data.model.AppConfig
import net.muratov.intercom.data.model.SipAccountProviderConfig
import net.muratov.intercom.data.model.SipAccountSourceConfig
import net.muratov.intercom.data.model.SipTransport
import net.muratov.intercom.data.model.StreamProviderConfig
import net.muratov.intercom.data.model.StreamSourceConfig
import net.muratov.intercom.provider.myhome.MyHomeProptechConfig
import org.json.JSONArray
import org.json.JSONObject

class AppConfigLoader(
    private val context: Context,
) {
    fun load(fileName: String = CONFIG_FILE_NAME): AppConfig {
        return runCatching {
            val rawJson = context.assets.open(fileName).bufferedReader().use { it.readText() }
            parseConfig(JSONObject(rawJson))
        }.getOrDefault(defaultConfig())
    }

    private fun parseConfig(root: JSONObject): AppConfig {
        return AppConfig(
            webViewUrl = root.optString("webViewUrl", "about:blank"),
            streams = root.optJSONArray("streams").toStreamSources(),
            sipAccounts = root.optJSONArray("sipAccounts").toSipAccountSources(),
            myHomeProptech = root.optJSONArray("providers").toMyHomeProptechConfig(),
        )
    }

    private fun JSONArray?.toStreamSources(): List<StreamSourceConfig> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            optJSONObject(index)
        }.mapNotNull { item ->
            item ?: return@mapNotNull null
            val id = item.optString("id")
            val title = item.optString("title")
            val provider = item.optJSONObject("provider").toStreamProviderConfig()
            if (id.isBlank() || title.isBlank() || provider == null) {
                null
            } else {
                StreamSourceConfig(id = id, title = title, provider = provider)
            }
        }
    }

    private fun JSONArray?.toSipAccountSources(): List<SipAccountSourceConfig> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            optJSONObject(index)
        }.mapNotNull { item ->
            item ?: return@mapNotNull null
            val id = item.optString("id")
            val provider = item.optJSONObject("provider").toSipAccountProviderConfig()
            if (id.isBlank() || provider == null) {
                null
            } else {
                SipAccountSourceConfig(id = id, provider = provider)
            }
        }
    }

    private fun defaultConfig(): AppConfig {
        return AppConfig(
            webViewUrl = "about:blank",
            streams = listOf(
                StreamSourceConfig(
                    id = "cam-1",
                    title = "Entrance",
                    provider = StreamProviderConfig(type = "config", url = "rtsp://192.168.1.10:554/stream1"),
                ),
                StreamSourceConfig(
                    id = "cam-2",
                    title = "Warehouse",
                    provider = StreamProviderConfig(type = "config", url = "rtsp://192.168.1.11:554/stream1"),
                ),
            ),
            sipAccounts = listOf(
                SipAccountSourceConfig(
                    id = "main-office",
                    provider = SipAccountProviderConfig(
                        type = "config",
                        title = "Main Office",
                        username = "1001",
                        password = "change-me",
                        domain = "sip.office.local",
                        port = 5061,
                        transport = SipTransport.TLS,
                    ),
                ),
                SipAccountSourceConfig(
                    id = "warehouse",
                    provider = SipAccountProviderConfig(
                        type = "config",
                        title = "Warehouse PBX",
                        username = "2001",
                        password = "change-me",
                        domain = "sip.warehouse.local",
                    ),
                ),
            ),
            myHomeProptech = MyHomeProptechConfig(),
        )
    }

    private fun JSONArray?.toMyHomeProptechConfig(): MyHomeProptechConfig {
        val provider = List(this?.length() ?: 0) { index -> this?.optJSONObject(index) }
            .firstOrNull { item -> item?.optString("type") == "proptech" }
            ?: return MyHomeProptechConfig()
        return MyHomeProptechConfig(
            enabled = true,
            baseUrl = provider.optString("baseUrl", "https://myhome.proptech.ru"),
            phone = provider.optString("phone"),
            installationId = provider.optString("installationId", "intercom-android"),
        )
    }

    private fun JSONObject?.toStreamProviderConfig(): StreamProviderConfig? {
        if (this == null) return null
        val type = optString("type").normalizeStreamProviderType()
        val url = optString("url").takeIf { it.isNotBlank() }
        if (type.isBlank() || url == null) return null
        return StreamProviderConfig(
            type = type,
            url = url,
            rtspExtras = optJSONObject("rtspExtras").toStringMap(),
            previewUrl = optString("previewUrl").takeIf { it.isNotBlank() },
            previewReloadPeriodMs = optLong("previewReloadPeriod").takeIf { has("previewReloadPeriod") && it > 0L },
            previewExtras = optJSONObject("previewExtras").toStringMap(),
            cameraId = opt("cameraId")?.toString()?.takeIf { it.isNotBlank() },
        )
    }

    private fun JSONObject?.toSipAccountProviderConfig(): SipAccountProviderConfig? {
        if (this == null) return null
        val type = optString("type")
        if (type.isBlank()) return null
        return SipAccountProviderConfig(
            type = type,
            title = optString("title"),
            displayName = optString("displayName"),
            username = optString("username"),
            password = optString("password"),
            domain = optString("domain"),
            port = optInt("port", 5060),
            transport = optString("transport").toSipTransport(),
        )
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return keys().asSequence().associateWith { key -> opt(key)?.toString().orEmpty() }
    }

    private fun String.toSipTransport(): SipTransport {
        return when (uppercase()) {
            "TCP" -> SipTransport.TCP
            "TLS" -> SipTransport.TLS
            else -> SipTransport.UDP
        }
    }

    private fun String.normalizeStreamProviderType(): String {
        return when (lowercase()) {
            "rtsp" -> "config"
            else -> this
        }
    }

    companion object {
        private const val CONFIG_FILE_NAME = "app_config.json"
    }
}
