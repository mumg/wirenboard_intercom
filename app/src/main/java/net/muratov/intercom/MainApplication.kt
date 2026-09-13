package net.muratov.intercom

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.muratov.intercom.logging.IntercomFileLogger
import net.muratov.intercom.data.provider.ConfigSipAccountDataProvider
import net.muratov.intercom.data.provider.ConfigStreamDataProvider
import net.muratov.intercom.data.provider.IntercomProvider
import net.muratov.intercom.data.provider.ProptechSipAccountDataProvider
import net.muratov.intercom.data.provider.ProptechStreamDataProvider
import net.muratov.intercom.data.repository.AppConfigLoadResult
import net.muratov.intercom.data.repository.AppConfigLoader
import net.muratov.intercom.data.repository.ProptechPlaceCatalog
import net.muratov.intercom.data.repository.SipAccountRepository
import net.muratov.intercom.data.repository.StreamRepository
import net.muratov.intercom.data.model.AppConfig
import net.muratov.intercom.data.model.MqttConfig
import net.muratov.intercom.data.model.ProviderOpenAction
import net.muratov.intercom.data.model.SipTransport
import net.muratov.intercom.mqtt.MqttCallStateService
import net.muratov.intercom.provider.myhome.MyHomeProptechService
import net.muratov.intercom.provider.myhome.MyHomeProviderService
import net.muratov.intercom.voip.SipCoreManager
import net.muratov.intercom.voip.SipCredentials
import org.linphone.core.TransportType
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val processName = currentProcessName()
        IntercomFileLogger.i("MainApplication", "onCreate process=$processName package=$packageName")
        if (!isMainProcess(processName)) {
            IntercomFileLogger.i("MainApplication", "Skipping app initialization in non-main process=$processName")
            return
        }
        AppCrashRestarter.install(this)
        SipCoreManager.initialize(this)
        reloadAppContainer()
    }

    fun reloadAppContainer() {
        if (::appContainer.isInitialized) {
            appContainer.dispose()
        }
        val configResult = AppConfigLoader(this).load()
        val logFilePath = File(configResult.filePath).parentFile
            ?.resolve("intercom.log")
            ?.absolutePath
            .orEmpty()
        IntercomFileLogger.setLogFilePath(logFilePath)
        val config = (configResult as? AppConfigLoadResult.Success)?.config ?: AppConfig()
        val isConfigValid = configResult is AppConfigLoadResult.Success
        val configFilePath = configResult.filePath
        val configErrorMessage = when (configResult) {
            is AppConfigLoadResult.Success -> null
            is AppConfigLoadResult.Missing -> "Необходима конфигурация для приложения"
            is AppConfigLoadResult.Invalid -> "Необходима конфигурация для приложения\n${configResult.errorMessage}"
        }
        val hasProptechConsumers = isConfigValid && (
            config.streams.any { it.provider.type == "proptech" } ||
                config.sipAccounts.any { it.provider.type == "proptech" }
            )
        val myHomeProviderService = MyHomeProptechService(
            this,
            config.myHomeProptech.copy(enabled = config.myHomeProptech.enabled && hasProptechConsumers),
        )
        val proptechPlaceCatalog = ProptechPlaceCatalog(myHomeProviderService)
        val providers: List<IntercomProvider> = listOf(
            ConfigStreamDataProvider("config"),
            ConfigSipAccountDataProvider(),
            ProptechStreamDataProvider(myHomeProviderService, proptechPlaceCatalog),
            ProptechSipAccountDataProvider(myHomeProviderService, proptechPlaceCatalog),
        )
        val sipAccountRepository = SipAccountRepository(
            sources = config.sipAccounts,
            providers = providers,
        )
        val mqttCallStateService = config.mqtt
            .takeIf(MqttConfig::isConfigured)
            ?.let { mqttConfig ->
                MqttCallStateService(
                    context = this,
                    config = mqttConfig,
                    sipAccountRepository = sipAccountRepository,
                )
            }
        appContainer = AppContainer(
            isConfigValid = isConfigValid,
            configFilePath = configFilePath,
            configErrorMessage = configErrorMessage,
            webViewUrl = config.webViewUrl,
            streamRepository = StreamRepository(
                sources = config.streams,
                providers = providers,
            ),
            sipAccountRepository = sipAccountRepository,
            myHomeProviderService = myHomeProviderService,
            proptechPlaceCatalog = proptechPlaceCatalog,
            proptechWizardRequired = hasProptechConsumers,
            providers = providers,
            mqttCallStateService = mqttCallStateService,
        )
        appContainer.initialize()
    }

    private fun isMainProcess(processName: String?): Boolean {
        return processName == null || processName == packageName
    }

    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }

        val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val pid = Process.myPid()
        return activityManager.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
    }
}

data class AppContainer(
    val isConfigValid: Boolean,
    val configFilePath: String,
    val configErrorMessage: String? = null,
    val webViewUrl: String,
    val streamRepository: StreamRepository,
    val sipAccountRepository: SipAccountRepository,
    val myHomeProviderService: MyHomeProviderService,
    private val proptechPlaceCatalog: ProptechPlaceCatalog,
    val proptechWizardRequired: Boolean,
    private val providers: List<IntercomProvider>,
    private val mqttCallStateService: MqttCallStateService? = null,
) {
    private companion object {
        const val INITIALIZATION_RETRY_DELAY_MS = 5_000L
    }

    private val initializationStarted = AtomicBoolean(false)
    private val runtimeServicesStarted = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _isInitialized = MutableStateFlow(false)

    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    fun restartRegistration() {
        if (!isConfigValid) return
        myHomeProviderService.start()
    }

    fun canOpen(action: ProviderOpenAction): Boolean {
        return providers.any { provider ->
            provider.type == action.providerType && provider.canOpen(action)
        }
    }

    fun initialize() {
        if (!isConfigValid) return
        if (!initializationStarted.compareAndSet(false, true)) return

        scope.launch {
            myHomeProviderService.state
                .map { state ->
                    val tokens = state.tokens
                    val placeId = state.selectedPlaceId
                    if (tokens != null && placeId != null) {
                        ProviderSession(
                            placeId = placeId,
                            authorizationHeader = tokens.authorizationHeader,
                        )
                    } else {
                        null
                    }
                }
                .distinctUntilChanged()
                .collectLatest { session ->
                    if (session == null && proptechWizardRequired) {
                        _isInitialized.value = false
                        return@collectLatest
                    }
                    initializeUntilSuccessful(session)
                }
        }
        if (proptechWizardRequired) {
            runCatching {
                myHomeProviderService.start()
            }.onFailure { error ->
                Log.e("AppContainer", "Failed to start Proptech registration", error)
            }
        }
    }

    fun dispose() {
        mqttCallStateService?.stop()
        scope.cancel()
    }

    suspend fun open(action: ProviderOpenAction): Boolean {
        for (provider in providers) {
            if (provider.type != action.providerType) continue
            if (provider.open(action)) return true
        }
        return false
    }

    private suspend fun initializeUntilSuccessful(session: ProviderSession?) {
        while (true) {
            val initialized = try {
                initializeConfiguration(session)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e("AppContainer", "Unexpected initialization failure", error)
                false
            }

            if (initialized) {
                startRuntimeServicesIfNeeded()
                return
            }

            Log.w(
                "AppContainer",
                "Initialization incomplete; retrying in ${INITIALIZATION_RETRY_DELAY_MS}ms",
            )
            delay(INITIALIZATION_RETRY_DELAY_MS)
        }
    }

    private suspend fun initializeConfiguration(session: ProviderSession?): Boolean {
        _isInitialized.value = false
        if (session == null) {
            proptechPlaceCatalog.clear()
            if (proptechWizardRequired) {
                return false
            }
        } else {
            val initialized = runCatching {
                proptechPlaceCatalog.initialize(session.placeId)
            }.onFailure { error ->
                proptechPlaceCatalog.clear()
                Log.e("AppContainer", "Failed to initialize Proptech place data", error)
            }.isSuccess
            if (!initialized) {
                return false
            }
        }

        // Both repositories consume the same completed catalog snapshot. Keeping
        // this sequence in one coroutine prevents overlapping configuration work.
        sipAccountRepository.refresh()
        streamRepository.refresh()
        _isInitialized.value = true
        return true
    }

    private fun startRuntimeServicesIfNeeded() {
        if (!runtimeServicesStarted.compareAndSet(false, true)) return
        mqttCallStateService?.start()
        scope.launch {
            sipAccountRepository.accounts.collectLatest { accounts ->
                Log.d("AppContainer", "Starting SIP with ${accounts.size} accounts")
                accounts.forEach {
                    SipCoreManager.register(SipCredentials(
                        username = it.username,
                        password = it.password,
                        domain = it.domain,
                        server = it.domain,
                        port = it.port,
                        transport = it.transport.toLinphoneTransportType(),
                        stunServer = it.stunServer,
                        iceEnabled = it.iceEnabled,
                        id = it.id,
                    ))
                }
            }
        }
    }

    private data class ProviderSession(
        val placeId: Long,
        val authorizationHeader: String,
    )

    private fun SipTransport.toLinphoneTransportType(): TransportType {
        return when (this) {
            SipTransport.TCP -> TransportType.Tcp
            SipTransport.TLS -> TransportType.Tls
            SipTransport.UDP -> TransportType.Udp
        }
    }
}
