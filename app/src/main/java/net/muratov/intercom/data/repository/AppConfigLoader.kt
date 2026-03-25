package net.muratov.intercom.data.repository

import android.content.Context
import net.muratov.intercom.data.model.AppConfig
import net.muratov.intercom.data.model.RtspStream
import net.muratov.intercom.data.model.SipAccountConfig
import net.muratov.intercom.data.model.SipTransport
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
            streams = root.optJSONArray("streams").toRtspStreams(),
            sipAccounts = root.optJSONArray("sipAccounts").toSipAccounts(),
        )
    }

    private fun JSONArray?.toRtspStreams(): List<RtspStream> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            optJSONObject(index)
        }.mapNotNull { item ->
            item ?: return@mapNotNull null
            val id = item.optString("id")
            val title = item.optString("title")
            val url = item.optString("url")
            if (id.isBlank() || title.isBlank() || url.isBlank()) {
                null
            } else {
                RtspStream(id = id, title = title, url = url)
            }
        }
    }

    private fun JSONArray?.toSipAccounts(): List<SipAccountConfig> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            optJSONObject(index)
        }.mapNotNull { item ->
            item ?: return@mapNotNull null
            val id = item.optString("id")
            val title = item.optString("title")
            val username = item.optString("username")
            val password = item.optString("password")
            val domain = item.optString("domain")
            if (id.isBlank() || title.isBlank() || username.isBlank() || password.isBlank() || domain.isBlank()) {
                null
            } else {
                SipAccountConfig(
                    id = id,
                    title = title,
                    username = username,
                    password = password,
                    domain = domain,
                    port = item.optInt("port", 5060),
                    transport = item.optString("transport").toSipTransport(),
                    displayName = item.optString("displayName", title),
                )
            }
        }
    }

    private fun defaultConfig(): AppConfig {
        return AppConfig(
            webViewUrl = "about:blank",
            streams = listOf(
                RtspStream("cam-1", "Entrance", "rtsp://192.168.1.10:554/stream1"),
                RtspStream("cam-2", "Warehouse", "rtsp://192.168.1.11:554/stream1"),
                RtspStream("cam-3", "Reception", "rtsp://192.168.1.12:554/stream1"),
                RtspStream("cam-4", "Parking", "rtsp://192.168.1.13:554/stream1"),
            ),
            sipAccounts = listOf(
                SipAccountConfig(
                    id = "main-office",
                    title = "Main Office",
                    username = "1001",
                    password = "change-me",
                    domain = "sip.office.local",
                    port = 5061,
                    transport = SipTransport.TLS,
                ),
                SipAccountConfig(
                    id = "warehouse",
                    title = "Warehouse PBX",
                    username = "2001",
                    password = "change-me",
                    domain = "sip.warehouse.local",
                ),
            ),
        )
    }

    private fun String.toSipTransport(): SipTransport {
        return when (uppercase()) {
            "TCP" -> SipTransport.TCP
            "TLS" -> SipTransport.TLS
            else -> SipTransport.UDP
        }
    }

    companion object {
        private const val CONFIG_FILE_NAME = "app_config.json"
    }
}
