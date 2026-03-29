package net.muratov.intercom.mqtt

import android.content.Context
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.muratov.intercom.data.model.MqttConfig
import net.muratov.intercom.data.repository.SipAccountRepository
import net.muratov.intercom.voip.CallSnapshot
import net.muratov.intercom.voip.SipCoreManager
import org.json.JSONObject
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class MqttCallStateService(
    context: Context,
    private val config: MqttConfig,
    private val sipAccountRepository: SipAccountRepository,
) : SipCoreManager.Listener {
    companion object {
        private const val TAG = "MqttCallStateService"
    }

    private val appContext = context.applicationContext
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientLock = Any()
    private val resolvedClientId = config.clientId

    @Volatile
    private var client: MqttAsyncClient? = null

    @Volatile
    private var accountTitlesById: Map<String, String> = emptyMap()

    fun start() {
        if (!config.isConfigured) return
        if (!started.compareAndSet(false, true)) return

        SipCoreManager.addListener(this)
        scope.launch {
            sipAccountRepository.accounts.collectLatest { accounts ->
                accountTitlesById = accounts.associate { it.id to it.title }
            }
        }
        scope.launch {
            publishCallState(snapshot = null, accountId = null)
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        SipCoreManager.removeListener(this)
        val currentClient = synchronized(clientLock) {
            client.also { client = null }
        }
        scope.launch {
            runCatching {
                currentClient?.disconnect()
            }
            runCatching {
                currentClient?.close()
            }
        }
        scope.cancel()
    }

    override fun onCallChanged(snapshot: CallSnapshot?) {
        if (!started.get()) return
        val accountId = SipCoreManager.getCurrentCallAccountId()
        scope.launch {
            publishCallState(snapshot, accountId)
        }
    }

    private suspend fun publishCallState(snapshot: CallSnapshot?, accountId: String?) {
        val mqttClient = ensureConnected() ?: return
        val providerTitle = accountId?.let(accountTitlesById::get).orEmpty()
        val stateValue = snapshot.toMqttStateCode()
        val callerNumber = snapshot?.remoteAddress?.let(::extractCallerNumber).orEmpty()

        publish(mqttClient, topic("controls/state"), stateValue)
        publish(mqttClient, topic("controls/number"), callerNumber)
        publish(mqttClient, topic("controls/provider"), providerTitle)
    }

    private suspend fun ensureConnected(): MqttAsyncClient? = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext null

        val mqttClient = synchronized(clientLock) {
            val existing = client
            if (existing != null) {
                existing
            } else {
                val created = MqttAsyncClient(
                    config.serverUrl,
                    resolvedClientId,
                    MemoryPersistence(),
                )
                client = created
                created
            }
        }

        if (mqttClient.isConnected) {
            return@withContext mqttClient
        }

        runCatching {
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
                if (config.username.isNotBlank()) {
                    userName = config.username
                }
                if (config.password.isNotBlank()) {
                    password = config.password.toCharArray()
                }
            }
            mqttClient.connect(options).waitForCompletion()
            publishDeviceMeta(mqttClient)
            mqttClient
        }.onFailure { error ->
            val message = if (error is MqttException) {
                "Failed to connect to MQTT broker ${config.serverUrl} reason=${error.reasonCode}"
            } else {
                "Failed to connect to MQTT broker ${config.serverUrl}"
            }
            Log.w(TAG, message, error)
        }.getOrNull()
    }

    private fun publish(client: MqttAsyncClient, topic: String, value: String) {
        runCatching {
            val message = MqttMessage(value.toByteArray(StandardCharsets.UTF_8)).apply {
                qos = 1
                isRetained = true
            }
            client.publish(topic, message).waitForCompletion()
        }.onFailure { error ->
            Log.w(TAG, "Failed to publish MQTT topic=$topic", error)
        }
    }

    private fun topic(suffix: String): String {
        val base = "/devices/intercom-$resolvedClientId"
        return if (suffix.isBlank()) base else "$base/$suffix"
    }

    private fun extractCallerNumber(address: String): String {
        return address
            .substringAfter("sip:", address)
            .substringAfter("sips:", address)
            .substringBefore("@")
            .ifBlank { address }
    }

    private fun publishDeviceMeta(client: MqttAsyncClient) {
        publish(client, topic(""), "")
        publish(
            client,
            topic("meta"),
            JSONObject()
                .put("driver", "intercom")
                .put("title", JSONObject().put("en", "Intercom $resolvedClientId"))
                .toString(),
        )
        publish(client, topic("meta/driver"), "intercom")
        publish(client, topic("meta/name"), "Intercom $resolvedClientId")

        publish(
            client,
            topic("controls/state/meta"),
            JSONObject()
                .put(
                    "enum",
                    JSONObject()
                        .put("1", JSONObject().put("en", "Idle"))
                        .put("2", JSONObject().put("en", "Ringing"))
                        .put("3", JSONObject().put("en", "Connected")),
                )
                .put("order", 1)
                .put("readonly", true)
                .put("title", JSONObject().put("en", "Call state"))
                .put("type", "value")
                .toString(),
        )
        publish(client, topic("controls/state/meta/order"), "1")
        publish(client, topic("controls/state/meta/readonly"), "1")
        publish(client, topic("controls/state/meta/type"), "value")

        publish(
            client,
            topic("controls/provider/meta"),
            JSONObject()
                .put("order", 2)
                .put("readonly", true)
                .put("title", JSONObject().put("en", "Provider"))
                .put("type", "text")
                .toString(),
        )
        publish(client, topic("controls/provider/meta/order"), "2")
        publish(client, topic("controls/provider/meta/readonly"), "1")
        publish(client, topic("controls/provider/meta/type"), "text")

        publish(
            client,
            topic("controls/number/meta"),
            JSONObject()
                .put("order", 3)
                .put("readonly", true)
                .put("title", JSONObject().put("en", "Number"))
                .put("type", "text")
                .toString(),
        )
        publish(client, topic("controls/number/meta/order"), "3")
        publish(client, topic("controls/number/meta/readonly"), "1")
        publish(client, topic("controls/number/meta/type"), "text")
    }

    private fun CallSnapshot?.toMqttStateCode(): String {
        val snapshot = this ?: return "1"
        return when (snapshot.state) {
            org.linphone.core.Call.State.IncomingReceived,
            org.linphone.core.Call.State.IncomingEarlyMedia -> "2"

            org.linphone.core.Call.State.Connected,
            org.linphone.core.Call.State.StreamsRunning -> "3"

            else -> "1"
        }
    }
}
