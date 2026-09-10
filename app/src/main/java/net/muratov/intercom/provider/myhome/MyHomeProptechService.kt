package net.muratov.intercom.provider.myhome

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.muratov.intercom.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

class MyHomeProptechService(
    private val context: Context,
    private val config: MyHomeProptechConfig,
) : MyHomeProviderService {

    companion object {
        private const val TAG = "MyHomeProptechService"
        private const val HTTP_TAG = "IntercomHttpProptech"
        private const val PROPTECH_APP_ID = "erth"
        private const val PROPTECH_APP_VERSION_NAME = "9.10.0"
        private const val PROPTECH_APP_VERSION_CODE = "91000020"
        private const val PROPTECH_PASSWORD_HASH_PREFIX = "DigitalHomeNTKpassword"
        private const val PROPTECH_AUTH_SECRET = "789sdgHJs678wertv34712376"
        private const val PREFS_NAME = "proptech_auth"
        private const val KEY_TOKEN_TYPE = "token_type"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_IN = "expires_in"
        private const val KEY_REFRESH_EXPIRES_IN = "refresh_expires_in"
        private const val KEY_OPERATOR_ID = "operator_id"
        private const val KEY_OPERATOR_NAME = "operator_name"
        private const val KEY_SELECTED_PLACE_ID = "selected_place_id"
        private const val KEY_LOGIN = "login"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val baseUrl: String = config.baseUrl.trimEnd('/')
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val restoredSession = restoreSession()
    private val _state = MutableStateFlow(
        if (config.enabled && restoredSession != null) {
            MyHomeProviderState(
                status = MyHomeAuthStatus.Authorized,
                tokens = restoredSession.tokens,
                selectedPlaceId = restoredSession.selectedPlaceId,
                message = "",
            )
        } else if (config.enabled) {
            MyHomeProviderState(status = MyHomeAuthStatus.Idle)
        } else {
            MyHomeProviderState(status = MyHomeAuthStatus.Disabled)
        },
    )
    override val state: StateFlow<MyHomeProviderState> = _state.asStateFlow()

    private var selectedContext: MyHomeLoginContext? = null
    private var tokens: MyHomeTokens? = restoredSession?.tokens
    private val authenticationInProgress = AtomicBoolean(false)

    override fun start() {
        if (!config.enabled || (!config.hasPasswordCredentials && config.phone.isBlank())) {
            _state.value = MyHomeProviderState(
                status = MyHomeAuthStatus.Disabled,
                message = "Укажите phone либо accountId и password в конфиге Proptech",
            )
            return
        }

        if (_state.value.status == MyHomeAuthStatus.Authorized ||
            _state.value.status == MyHomeAuthStatus.WaitingForCode ||
            _state.value.status == MyHomeAuthStatus.SelectingContext ||
            _state.value.status == MyHomeAuthStatus.RequestingCode ||
            _state.value.status == MyHomeAuthStatus.Authorizing
        ) {
            return
        }
        if (!authenticationInProgress.compareAndSet(false, true)) return

        scope.launch {
            try {
                if (config.hasPasswordCredentials) {
                    authorizeWithPassword()
                    return@launch
                }

                _state.value = _state.value.copy(
                    status = MyHomeAuthStatus.RequestingCode,
                    message = "Запрашиваем код подтверждения",
                )

                runCatching {
                    val contexts = getLoginContexts(config.phone)
                    when {
                        contexts.isEmpty() -> error("Для номера не найден подходящий контекст авторизации")
                        contexts.size == 1 -> beginPhoneConfirmation(contexts.first())
                        else -> {
                            _state.value = MyHomeProviderState(
                                status = MyHomeAuthStatus.SelectingContext,
                                contextSelectionPrompt = MyHomeContextSelectionPrompt(
                                    phone = config.phone,
                                    contexts = contexts,
                                ),
                                message = "Выберите адрес для авторизации",
                            )
                        }
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Unable to start verification flow", error)
                    _state.value = _state.value.copy(
                        status = MyHomeAuthStatus.Error,
                        message = error.message.orEmpty(),
                    )
                }
            } finally {
                authenticationInProgress.set(false)
            }
        }
    }

    override fun selectLoginContext(context: MyHomeLoginContext) {
        if (!config.enabled) return
        val availableContexts = _state.value.contextSelectionPrompt?.contexts.orEmpty()
        scope.launch {
            _state.value = _state.value.copy(
                status = MyHomeAuthStatus.RequestingCode,
                contextSelectionPrompt = null,
                message = "Запрашиваем код подтверждения",
            )

            runCatching {
                beginPhoneConfirmation(context)
            }.onFailure { error ->
                Log.e(TAG, "Unable to start verification for selected context", error)
                _state.value = if (availableContexts.isNotEmpty()) {
                    MyHomeProviderState(
                        status = MyHomeAuthStatus.SelectingContext,
                        contextSelectionPrompt = MyHomeContextSelectionPrompt(
                            phone = config.phone,
                            contexts = availableContexts,
                        ),
                        message = error.message.orEmpty(),
                    )
                } else {
                    _state.value.copy(
                        status = MyHomeAuthStatus.Error,
                        message = error.message.orEmpty(),
                    )
                }
            }
        }
    }

    override fun submitVerificationCode(code: String, confirmationSecret: String) {
        val context = selectedContext ?: return
        if (code.isBlank()) {
            _state.value = _state.value.copy(message = "Код не может быть пустым")
            return
        }

        scope.launch {
            _state.value = _state.value.copy(
                status = MyHomeAuthStatus.Authorizing,
                message = "Проверяем код",
            )

            runCatching {
                val issuedTokens = confirmAuth(
                    loginContext = context,
                    login = config.phone,
                    confirmation = code,
                    confirmationSecret = confirmationSecret,
                )
                completeAuthorization(issuedTokens, context.placeId, config.phone)
            }.onFailure { error ->
                Log.e(TAG, "Unable to confirm verification code", error)
                _state.value = _state.value.copy(
                    status = MyHomeAuthStatus.WaitingForCode,
                    message = error.message ?: "Не удалось подтвердить код",
                )
            }
        }
    }

    override fun dismissVerificationPrompt() {
        selectedContext = null
        _state.value = _state.value.copy(
            contextSelectionPrompt = null,
            verificationPrompt = null,
            message = "",
            status = if (tokens != null) MyHomeAuthStatus.Authorized else MyHomeAuthStatus.Idle,
        )
    }

    override suspend fun getOperators(): List<MyHomeOperator> {
        val json = requestJson(
            path = "/public/v1/operators",
            method = "GET",
        ).asObject()
        return json.optJSONArray("data").toList().mapNotNull { item ->
            item as? JSONObject ?: return@mapNotNull null
            MyHomeOperator(
                id = item.optInt("id"),
                displayName = item.optString("dispName"),
                authUrl = item.optString("authUrl"),
                infoUrl = item.optString("infoUrl"),
            )
        }
    }

    override suspend fun getLoginContexts(login: String): List<MyHomeLoginContext> {
        val targetLogin = login.ifBlank {
            if (config.hasPasswordCredentials) config.accountId else config.phone
        }
        require(targetLogin.isNotBlank()) { "Proptech login is not configured" }
        val encodedLogin = targetLogin.urlEncode()
        val response = requestJson(
            path = "/auth/v2/login/$encodedLogin",
            method = "GET",
            expectedCodes = setOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_MULT_CHOICE),
        )
        val array = response.asArray()
        return array.toList().mapNotNull { item ->
            item as? JSONObject ?: return@mapNotNull null
            MyHomeLoginContext(
                operatorId = item.optInt("operatorId"),
                subscriberId = item.opt("subscriberId")?.toString().orEmpty(),
                accountId = item.optString("accountId"),
                placeId = item.optLong("placeId"),
                address = item.optString("address"),
                profileId = item.optString("profileId"),
            )
        }
    }

    override suspend fun registerSubscriberNotifications(pushToken: String): Boolean {
        val body = JSONObject().apply {
            put("appVersionCode", BuildConfig.VERSION_CODE)
            put("installationId", defaultInstallationId())
            put("appId", 1)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("platform", "ANDROID")
            put("pushToken", pushToken)
            put("isDevelop", BuildConfig.DEBUG)
            put("deviceManufacturer", Build.MANUFACTURER)
            put("deviceModelName", Build.MODEL)
            put("osVersion", Build.VERSION.RELEASE)
            put("deviceId", defaultInstallationId())
        }
        requestJson(
            path = "/rest/v1/subscriberNotifications",
            method = "POST",
            body = body.toString(),
            bearerToken = requireTokens().authorizationHeader,
        )
        return true
    }

    override suspend fun getSubscriberPlaces(placeId: Long?): List<MyHomeSubscriberPlace> {
        val query = placeId?.let { "?placeId=$it" }.orEmpty()
        val json = requestJson(
            path = "/rest/v3/subscriber-places$query",
            method = "GET",
            bearerToken = requireTokens().authorizationHeader,
        ).asObject()
        return json.optJSONArray("data").toList().mapNotNull { item ->
            item as? JSONObject ?: return@mapNotNull null
            val place = item.optJSONObject("place")
            val address = place?.optJSONObject("address")?.optString("visibleAddress")
                ?: place?.optJSONObject("address")?.optString("groupName").orEmpty()
            MyHomeSubscriberPlace(
                id = item.optLong("id"),
                placeId = place?.optLong("id") ?: 0L,
                address = address,
                provider = item.optString("provider"),
                blocked = item.optBoolean("blocked"),
            )
        }
    }

    override suspend fun getSubscriberProfile(): MyHomeSubscriberProfile? {
        val json = requestJson(
            path = "/rest/v1/subscribers/profiles",
            method = "GET",
            bearerToken = requireTokens().authorizationHeader,
        ).asObject()
        val data = json.optJSONObject("data") ?: return null
        val subscriber = data.optJSONObject("subscriber")
        return MyHomeSubscriberProfile(
            subscriberId = subscriber?.optLong("id"),
            subscriberName = subscriber?.optString("name"),
            pushUserId = data.optString("pushUserId"),
        )
    }

    override suspend fun getPlaceAccessControls(placeId: Long): List<MyHomeAccessControl> {
        val json = requestJson(
            path = "/rest/v1/places/$placeId/accesscontrols",
            method = "GET",
            bearerToken = requireTokens().authorizationHeader,
        ).asObject()
        return json.optJSONArray("data").toList().mapNotNull { item ->
            item as? JSONObject ?: return@mapNotNull null
            MyHomeAccessControl(
                id = item.optLong("id"),
                operatorId = item.optInt("operatorId"),
                name = item.optString("name"),
                type = item.optString("type"),
                externalCameraId = item.optString("externalCameraId").takeIf { it.isNotBlank() },
                allowOpen = item.optBoolean("allowOpen"),
                allowVideo = item.optBoolean("allowVideo"),
                previewAvailable = item.optBoolean("previewAvailable"),
            )
        }
    }

    override suspend fun getAccessControlVideoSnapshot(placeId: Long, accessControlId: Long): ByteArray {
        return requestBytes(
            path = "/rest/v1/places/$placeId/accesscontrols/$accessControlId/videosnapshots",
            bearerToken = requireTokens().authorizationHeader,
        )
    }

    override suspend fun executeAccessControlAction(
        placeId: Long,
        accessControlId: Long,
        action: String,
    ): MyHomeAccessControlActionResult {
        val json = requestJson(
            path = "/rest/v1/places/$placeId/accesscontrols/$accessControlId/actions",
            method = "POST",
            body = JSONObject.quote(action),
            bearerToken = requireTokens().authorizationHeader,
        ).asObject()
        val data = json.optJSONObject("data") ?: JSONObject()
        return MyHomeAccessControlActionResult(
            status = data.optBoolean("status"),
            errorCode = data.optString("errorCode").takeIf { it.isNotBlank() },
            errorMessage = data.optString("errorMessage").takeIf { it.isNotBlank() },
        )
    }

    override suspend fun registerSipDevice(placeId: Long, accessControlId: Long): MyHomeSipDevice {
        val json = requestJson(
            path = "/rest/v1/places/$placeId/accesscontrols/$accessControlId/sipdevices",
            method = "POST",
            body = JSONObject().put("installationId", defaultInstallationId()).toString(),
            bearerToken = requireTokens().authorizationHeader,
        ).asObject()
        val data = json.optJSONObject("data") ?: error("SIP device payload is empty")
        return MyHomeSipDevice(
            id = data.optString("id"),
            realm = data.optString("realm"),
            login = data.optString("login"),
            password = data.optString("password"),
        )
    }

    override suspend fun getPlaceCameras(placeId: Long): List<MyHomeCameraResource> {
        return getCameraResources("/rest/v1/places/$placeId/cameras")
    }

    override suspend fun getPlacePublicCameras(placeId: Long): List<MyHomeCameraResource> {
        return getCameraResources("/rest/v2/places/$placeId/public/cameras")
    }

    override suspend fun getForpostCameraVideoUrl(externalCameraId: String): String? {
        if (externalCameraId.isBlank()) return null
        val json = requestJson(
            path = "/rest/v1/forpost/cameras/$externalCameraId/video?LightStream=0&Format=H264",
            method = "GET",
            bearerToken = requireTokens().authorizationHeader,
        ).asObject()
        return json.optJSONObject("data")
            ?.optString("URL")
            ?.takeIf { it.isNotBlank() }
    }

    override suspend fun getAvailableStompFeatures(): String? {
        return requestJson(
            path = "/rest/v1/stomp/available-features",
            method = "GET",
            bearerToken = requireTokens().authorizationHeader,
        ).rawText
    }

    override suspend fun getPlaceFinance(placeId: Long): MyHomeFinanceInfo? {
        val raw = requestJson(
            path = "/api/mh-payment/mobile/v1/finance?placeId=$placeId",
            method = "GET",
            bearerToken = requireTokens().authorizationHeader,
        ).rawText
        return MyHomeFinanceInfo(rawJson = raw)
    }

    private suspend fun getCameraResources(path: String): List<MyHomeCameraResource> {
        val json = requestJson(
            path = path,
            method = "GET",
            bearerToken = requireTokens().authorizationHeader,
        ).asObject()
        val result = json.optJSONArray("data").toList().mapNotNull { item ->
            item as? JSONObject ?: return@mapNotNull null
            MyHomeCameraResource(
                id = item.opt("id")?.toString().orEmpty(),
                title = item.optString("name").ifBlank { item.optString("title") },
                rawJson = item.toString(),
            )
        }
        return result
    }

    private suspend fun startPhoneConfirmation(loginContext: MyHomeLoginContext) {
        val body = JSONObject().apply {
            put("operatorId", loginContext.operatorId)
            put("accountId", loginContext.accountId)
            put("address", loginContext.address)
            put("profileId", loginContext.profileId)
            put("subscriberId", loginContext.subscriberId)
            put("placeId", loginContext.placeId)
        }
        requestJson(
            path = "/auth/v2/confirmation/${config.phone.urlEncode()}",
            method = "POST",
            body = body.toString(),
        )
    }

    private suspend fun confirmAuth(
        loginContext: MyHomeLoginContext,
        login: String,
        confirmation: String,
        confirmationSecret: String,
    ): MyHomeTokens {
        val confirm2 = confirmationSecret.takeIf { it.isNotBlank() } ?: confirmation
        val body = JSONObject().apply {
            put("operatorId", loginContext.operatorId)
            put("login", login)
            put("accountId", loginContext.accountId)
            put("profileId", loginContext.profileId)
            put("confirm1", confirmation)
            put("confirm2", confirm2)
            put("subscriberId", loginContext.subscriberId)
        }
        val json = requestJson(
            path = "/auth/v3/auth/${login.urlEncode()}/confirmation",
            method = "POST",
            body = body.toString(),
        ).asObject()
        return MyHomeTokens(
            tokenType = json.optString("tokenType", "Bearer"),
            accessToken = json.optString("accessToken"),
            refreshToken = json.optString("refreshToken"),
            expiresIn = json.optLongOrNull("expiresIn"),
            refreshExpiresIn = json.optLongOrNull("refreshExpiresIn"),
            operatorId = json.optIntOrNull("operatorId"),
            operatorName = json.optString("operatorName").takeIf { it.isNotBlank() },
        )
    }

    private suspend fun authorizeWithPassword() {
        _state.value = MyHomeProviderState(
            status = MyHomeAuthStatus.Authorizing,
            message = "Авторизация по номеру аккаунта",
        )

        runCatching {
            val login = config.accountId.trim()
            val issuedTokens = authenticateWithPassword(login, config.password)
            tokens = issuedTokens
            val placeId = try {
                getSubscriberPlaces().firstOrNull()?.placeId
                    ?: error("Для аккаунта не найден доступный адрес")
            } catch (error: Throwable) {
                tokens = null
                throw error
            }
            completeAuthorization(issuedTokens, placeId, login)
        }.onFailure { error ->
            Log.e(TAG, "Unable to authorize with Proptech account credentials", error)
            _state.value = MyHomeProviderState(
                status = MyHomeAuthStatus.Error,
                message = error.message ?: "Не удалось авторизоваться по номеру аккаунта",
            )
        }
    }

    private suspend fun authenticateWithPassword(login: String, password: String): MyHomeTokens {
        val now = Date()
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(now)
        val compactTimestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(now)
        val hash1 = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest(
                password.toByteArray(Charset.forName("ISO-8859-1")),
            ),
            Base64.NO_WRAP,
        )
        val hash2Source = buildString {
            append(PROPTECH_PASSWORD_HASH_PREFIX)
            append(login)
            append(password)
            append(compactTimestamp)
            append(PROPTECH_AUTH_SECRET)
        }
        val hash2 = MessageDigest.getInstance("MD5")
            .digest(hash2Source.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val body = JSONObject().apply {
            put("login", login)
            put("timestamp", timestamp)
            put("hash1", hash1)
            put("hash2", hash2)
        }
        val json = requestJson(
            path = "/auth/v2/auth/${login.urlEncode()}/password",
            method = "POST",
            body = body.toString(),
        ).asObject()
        return MyHomeTokens(
            tokenType = json.optString("tokenType", "Bearer"),
            accessToken = json.optString("accessToken"),
            refreshToken = json.optString("refreshToken"),
            expiresIn = json.optLongOrNull("expiresIn"),
            refreshExpiresIn = json.optLongOrNull("refreshExpiresIn"),
            operatorId = json.optIntOrNull("operatorId"),
            operatorName = json.optString("operatorName").takeIf { it.isNotBlank() },
        ).also { issuedTokens ->
            require(issuedTokens.accessToken.isNotBlank()) { "Сервер не вернул токен авторизации" }
        }
    }

    private fun completeAuthorization(
        issuedTokens: MyHomeTokens,
        placeId: Long,
        login: String,
    ) {
        tokens = issuedTokens
        persistSession(issuedTokens, placeId, login)
        _state.value = MyHomeProviderState(
            status = MyHomeAuthStatus.Authorized,
            contextSelectionPrompt = null,
            verificationPrompt = null,
            tokens = issuedTokens,
            selectedPlaceId = placeId,
            message = "Авторизация выполнена",
        )
    }

    private suspend fun beginPhoneConfirmation(loginContext: MyHomeLoginContext) {
        selectedContext = loginContext
        startPhoneConfirmation(loginContext)
        _state.value = MyHomeProviderState(
            status = MyHomeAuthStatus.WaitingForCode,
            verificationPrompt = MyHomeVerificationPrompt(
                phone = config.phone,
                address = loginContext.address,
                placeId = loginContext.placeId,
            ),
            selectedPlaceId = loginContext.placeId,
            message = "Код отправлен",
        )
    }

    private fun requireTokens(): MyHomeTokens {
        return tokens ?: error("Provider is not authorized yet")
    }

    private suspend fun requestJson(
        path: String,
        method: String,
        body: String? = null,
        bearerToken: String? = null,
        expectedCodes: Set<Int> = setOf(HttpURLConnection.HTTP_OK),
    ): JsonResponse = withContext(Dispatchers.IO) {
        val connection = openConnection(path, method, bearerToken)
        val startNs = System.nanoTime()
        try {
            logHttpRequest(connection, body)
            if (body != null) {
                connection.doOutput = true
                BufferedOutputStream(connection.outputStream).bufferedWriter().use { writer ->
                    writer.write(body)
                }
            }

            val responseCode = connection.responseCode
            val text = connection.readText()
            logHttpResponse(connection, responseCode, text, startNs)
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED && bearerToken != null) {
                clearAuthorizedSession("Сессия Proptech истекла, выполните вход заново")
            }
            if (responseCode !in expectedCodes) {
                throw IllegalStateException("HTTP $responseCode: $text")
            }
            JsonResponse(text)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun requestBytes(
        path: String,
        bearerToken: String? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        val connection = openConnection(path, "GET", bearerToken)
        val startNs = System.nanoTime()
        try {
            logHttpRequest(connection, body = null)
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED && bearerToken != null) {
                clearAuthorizedSession("Сессия Proptech истекла, выполните вход заново")
            }
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorText = connection.readText()
                logHttpResponse(connection, responseCode, errorText, startNs)
                throw IllegalStateException("HTTP $responseCode: $errorText")
            }
            BufferedInputStream(connection.inputStream).use { input ->
                ByteArrayOutputStream().use { output ->
                    input.copyTo(output)
                    output.toByteArray().also { bytes ->
                        logHttpBinaryResponse(connection, responseCode, bytes.size, startNs)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(
        path: String,
        method: String,
        bearerToken: String?,
    ): HttpURLConnection {
        val url = URL("$baseUrl$path")
        val operatorIdHeader = tokens?.operatorId?.takeIf {
            bearerToken != null && !path.startsWith("/rest/v3/subscriber-places")
        }
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json, image/jpeg, */*")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", buildProptechUserAgent())
            bearerToken?.let { setRequestProperty("Authorization", it) }
            operatorIdHeader?.let { setRequestProperty("Operator", it.toString()) }
        }
    }

    private fun HttpURLConnection.readText(): String {
        val stream = errorStream ?: inputStream ?: return ""
        return stream.bufferedReader().use { it.readText() }
    }

    private fun logHttpRequest(
        connection: HttpURLConnection,
        body: String?,
    ) {
        Log.d(
            HTTP_TAG,
            buildString {
                append("REQUEST ")
                append(connection.requestMethod)
                append(' ')
                append(connection.url)
                append('\n')
                append("headers=")
                append(connection.requestProperties.sanitizedHeaders())
                if (!body.isNullOrBlank()) {
                    append('\n')
                    append("body=")
                    append(body.sanitizedJsonForLog())
                }
            },
        )
    }

    private fun logHttpResponse(
        connection: HttpURLConnection,
        responseCode: Int,
        body: String,
        startNs: Long,
    ) {
        Log.d(
            HTTP_TAG,
            buildString {
                append("RESPONSE ")
                append(responseCode)
                append(' ')
                append(connection.requestMethod)
                append(' ')
                append(connection.url)
                append(" in ")
                append((System.nanoTime() - startNs) / 1_000_000)
                append(" ms")
                append('\n')
                append("headers=")
                append(connection.headerFields.sanitizedHeaders())
                if (body.isNotBlank()) {
                    append('\n')
                    append("body=")
                    append(body.sanitizedJsonForLog())
                }
            },
        )
    }

    private fun logHttpBinaryResponse(
        connection: HttpURLConnection,
        responseCode: Int,
        byteCount: Int,
        startNs: Long,
    ) {
        Log.d(
            HTTP_TAG,
            buildString {
                append("RESPONSE ")
                append(responseCode)
                append(' ')
                append(connection.requestMethod)
                append(' ')
                append(connection.url)
                append(" in ")
                append((System.nanoTime() - startNs) / 1_000_000)
                append(" ms")
                append('\n')
                append("headers=")
                append(connection.headerFields.sanitizedHeaders())
                append('\n')
                append("binaryBytes=")
                append(byteCount)
            },
        )
    }

    private fun defaultInstallationId(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
        return androidId ?: config.installationId.ifBlank { "android-id-unavailable" }
    }

    private fun buildProptechUserAgent(): String {
        val operatorId = tokens?.operatorId?.toString() ?: "null"
        val placeId = _state.value.selectedPlaceId?.toString() ?: "null"
        val installationId = defaultInstallationId()
        return buildString {
            append(Build.MANUFACTURER)
            append(' ')
            append(Build.MODEL)
            append(" | Android ")
            append(Build.VERSION.RELEASE ?: "unknown")
            append(" | ")
            append(PROPTECH_APP_ID)
            append(" | ")
            append(PROPTECH_APP_VERSION_NAME)
            append(" (")
            append(PROPTECH_APP_VERSION_CODE)
            append(") | | ")
            append(operatorId)
            append(" | ")
            append(installationId)
            append(" | ")
            append(placeId)
        }
    }

    private fun persistSession(tokens: MyHomeTokens, selectedPlaceId: Long, login: String) {
        preferences.edit()
            .putString(KEY_TOKEN_TYPE, tokens.tokenType)
            .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            .putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
            .putLong(KEY_EXPIRES_IN, tokens.expiresIn ?: -1L)
            .putLong(KEY_REFRESH_EXPIRES_IN, tokens.refreshExpiresIn ?: -1L)
            .putInt(KEY_OPERATOR_ID, tokens.operatorId ?: -1)
            .putString(KEY_OPERATOR_NAME, tokens.operatorName.orEmpty())
            .putLong(KEY_SELECTED_PLACE_ID, selectedPlaceId)
            .putString(KEY_LOGIN, login)
            .apply()
    }

    private fun restoreSession(): PersistedSession? {
        if (!config.enabled) return null
        val accessToken = preferences.getString(KEY_ACCESS_TOKEN, null).orEmpty()
        val refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null).orEmpty()
        val tokenType = preferences.getString(KEY_TOKEN_TYPE, null).orEmpty()
        val selectedPlaceId = preferences.getLong(KEY_SELECTED_PLACE_ID, -1L)
        val persistedLogin = preferences.getString(KEY_LOGIN, null).orEmpty()
        val configuredLogin = if (config.hasPasswordCredentials) config.accountId else config.phone
        if (accessToken.isBlank() || refreshToken.isBlank() || tokenType.isBlank() || selectedPlaceId <= 0L) {
            return null
        }
        if (config.hasPasswordCredentials && !persistedLogin.equals(configuredLogin, ignoreCase = true)) {
            return null
        }
        if (persistedLogin.isNotBlank() &&
            configuredLogin.isNotBlank() &&
            !persistedLogin.equals(configuredLogin, ignoreCase = true)
        ) {
            return null
        }
        return PersistedSession(
            tokens = MyHomeTokens(
                tokenType = tokenType,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresIn = preferences.getLong(KEY_EXPIRES_IN, -1L).takeIf { it > 0L },
                refreshExpiresIn = preferences.getLong(KEY_REFRESH_EXPIRES_IN, -1L).takeIf { it > 0L },
                operatorId = preferences.getInt(KEY_OPERATOR_ID, -1).takeIf { it >= 0 },
                operatorName = preferences.getString(KEY_OPERATOR_NAME, null)?.takeIf { it.isNotBlank() },
            ),
            selectedPlaceId = selectedPlaceId,
        )
    }

    private fun clearAuthorizedSession(message: String = "") {
        tokens = null
        selectedContext = null
        preferences.edit().clear().apply()
        _state.value = MyHomeProviderState(
            status = if (config.enabled) MyHomeAuthStatus.Idle else MyHomeAuthStatus.Disabled,
            message = message,
        )
        if (config.enabled && config.hasPasswordCredentials) {
            // Configured credentials can renew an expired session without any
            // user interaction. The SMS branch intentionally stays manual.
            start()
        }
    }
}

private data class PersistedSession(
    val tokens: MyHomeTokens,
    val selectedPlaceId: Long,
)

private data class JsonResponse(
    val rawText: String,
) {
    fun asObject(): JSONObject = JSONObject(rawText.ifBlank { "{}" })
    fun asArray(): JSONArray = JSONArray(rawText.ifBlank { "[]" })
}

private fun JSONArray?.toList(): List<Any?> {
    if (this == null) return emptyList()
    return List(length()) { index -> opt(index) }
}

private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

private fun String.sanitizedJsonForLog(): String {
    return runCatching {
        when {
            trimStart().startsWith("{") -> JSONObject(this).sanitizedForLog().toString()
            trimStart().startsWith("[") -> JSONArray(this).sanitizedForLog().toString()
            else -> this
        }
    }.getOrDefault("<invalid JSON omitted>")
}

private fun JSONObject.sanitizedForLog(): JSONObject {
    val sanitized = JSONObject()
    keys().forEach { key ->
        val value = opt(key)
        sanitized.put(
            key,
            if (key.lowercase() in SENSITIVE_JSON_KEYS) "<redacted>" else value.sanitizedForLog(),
        )
    }
    return sanitized
}

private fun JSONArray.sanitizedForLog(): JSONArray {
    val sanitized = JSONArray()
    for (index in 0 until length()) {
        sanitized.put(opt(index).sanitizedForLog())
    }
    return sanitized
}

private fun Any?.sanitizedForLog(): Any? = when (this) {
    is JSONObject -> sanitizedForLog()
    is JSONArray -> sanitizedForLog()
    else -> this
}

private val SENSITIVE_JSON_KEYS = setOf(
    "password",
    "hash1",
    "hash2",
    "confirm1",
    "confirm2",
    "accesstoken",
    "refreshtoken",
)

private fun JSONObject.optLongOrNull(key: String): Long? {
    return if (isNull(key)) null else optLong(key)
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    return if (isNull(key)) null else optInt(key)
}

private fun Map<String, List<String>?>.sanitizedHeaders(): Map<String, List<String>> {
    return entries.associate { (key, values) ->
        val safeKey = key.orEmpty()
        val safeValues = values.orEmpty().map { value ->
            when {
                safeKey.equals("Authorization", ignoreCase = true) -> "<redacted>"
                safeKey.equals("Cookie", ignoreCase = true) -> "<redacted>"
                safeKey.equals("Set-Cookie", ignoreCase = true) -> "<redacted>"
                else -> value
            }
        }
        safeKey to safeValues
    }
}
