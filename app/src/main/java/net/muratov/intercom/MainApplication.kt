package net.muratov.intercom

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.muratov.intercom.data.provider.ConfigSipAccountDataProvider
import net.muratov.intercom.data.provider.ConfigStreamDataProvider
import net.muratov.intercom.data.provider.IntercomProvider
import net.muratov.intercom.data.provider.ProptechSipAccountDataProvider
import net.muratov.intercom.data.provider.ProptechStreamDataProvider
import net.muratov.intercom.data.repository.AppConfigLoadResult
import net.muratov.intercom.data.repository.AppConfigLoader
import net.muratov.intercom.data.repository.SipAccountRepository
import net.muratov.intercom.data.repository.StreamRepository
import net.muratov.intercom.data.model.AppConfig
import net.muratov.intercom.data.model.ProviderOpenAction
import net.muratov.intercom.provider.myhome.MyHomeProptechService
import net.muratov.intercom.provider.myhome.MyHomeProviderService
import net.muratov.intercom.voip.SipCoreManager
import net.muratov.intercom.voip.SipCredentials
import org.linphone.core.TransportType
import java.util.concurrent.atomic.AtomicBoolean

class MainApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppCrashRestarter.install(this)
        SipCoreManager.initialize(this)
        val configResult = AppConfigLoader(this).load()
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
        val providers: List<IntercomProvider> = listOf(
            ConfigStreamDataProvider("config"),
            ConfigSipAccountDataProvider(),
            ProptechStreamDataProvider(myHomeProviderService),
            ProptechSipAccountDataProvider(myHomeProviderService),
        )
        appContainer = AppContainer(
            isConfigValid = isConfigValid,
            configFilePath = configFilePath,
            configErrorMessage = configErrorMessage,
            webViewUrl = config.webViewUrl,
            streamRepository = StreamRepository(
                sources = config.streams,
                providers = providers,
                myHomeProviderService = myHomeProviderService,
            ),
            sipAccountRepository = SipAccountRepository(
                sources = config.sipAccounts,
                providers = providers,
                myHomeProviderService = myHomeProviderService,
            ),
            myHomeProviderService = myHomeProviderService,
            proptechWizardRequired = hasProptechConsumers,
            providers = providers,
        )
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
    val proptechWizardRequired: Boolean,
    private val providers: List<IntercomProvider>,
) {
    private val registrationStarted = AtomicBoolean(false)
    private val mainStarted = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun startRegistrationIfNeeded() {
        if (!isConfigValid) return
        if (registrationStarted.compareAndSet(false, true)) {
            runCatching {
                myHomeProviderService.start()
            }.onFailure { error ->
                registrationStarted.set(false)
                Log.e("AppContainer", "Failed to start proptech registration", error)
            }
        }
    }

    fun restartRegistration() {
        if (!isConfigValid) return
        myHomeProviderService.start()
    }

    fun canOpen(action: ProviderOpenAction): Boolean {
        return providers.any { provider ->
            provider.type == action.providerType && provider.canOpen(action)
        }
    }

    fun startMainIfNeeded() {
        if (!isConfigValid) return
        if (mainStarted.compareAndSet(false, true)) {
            runCatching {
                scope.launch {
                    sipAccountRepository.accounts.collectLatest { accounts ->
                        Log.d("AppContainer", "Starting SIP with ${accounts.size} accounts")
                        accounts.forEach {
                            SipCoreManager.register(SipCredentials(
                                it.username,
                                it.password,
                                it.domain,
                                it.domain,
                                TransportType.Udp,
                                it.id
                            ))
                        }
                    }
                }
            }.onFailure { error ->
                mainStarted.set(false)
                Log.e("AppContainer", "Failed to start SIP service", error)
            }
        }
    }

    suspend fun open(action: ProviderOpenAction): Boolean {
        for (provider in providers) {
            if (provider.type != action.providerType) continue
            if (provider.open(action)) return true
        }
        return false
    }
}
