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
import net.muratov.intercom.data.provider.ProptechSipAccountDataProvider
import net.muratov.intercom.data.provider.ProptechStreamDataProvider
import net.muratov.intercom.data.repository.AppConfigLoader
import net.muratov.intercom.data.repository.SipAccountRepository
import net.muratov.intercom.data.repository.StreamRepository
import net.muratov.intercom.provider.myhome.MyHomeProptechService
import net.muratov.intercom.provider.myhome.MyHomeProviderService
import net.muratov.intercom.voip.SafeSipService
import net.muratov.intercom.voip.SipService
import java.util.concurrent.atomic.AtomicBoolean

class MainApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val config = AppConfigLoader(this).load()
        val hasProptechConsumers = config.streams.any { it.provider.type == "proptech" } ||
            config.sipAccounts.any { it.provider.type == "proptech" }
        val myHomeProviderService = MyHomeProptechService(
            this,
            config.myHomeProptech.copy(enabled = config.myHomeProptech.enabled && hasProptechConsumers),
        )
        appContainer = AppContainer(
            webViewUrl = config.webViewUrl,
            streamRepository = StreamRepository(
                sources = config.streams,
                providers = listOf(
                    ConfigStreamDataProvider("config"),
                    ConfigStreamDataProvider("rtsp"),
                    ProptechStreamDataProvider(myHomeProviderService),
                ),
                myHomeProviderService = myHomeProviderService,
            ),
            sipAccountRepository = SipAccountRepository(
                sources = config.sipAccounts,
                providers = listOf(
                    ConfigSipAccountDataProvider(),
                    ProptechSipAccountDataProvider(myHomeProviderService),
                ),
                myHomeProviderService = myHomeProviderService,
            ),
            myHomeProviderService = myHomeProviderService,
            sipService = SafeSipService(this),
            proptechWizardRequired = hasProptechConsumers,
        )
    }
}

data class AppContainer(
    val webViewUrl: String,
    val streamRepository: StreamRepository,
    val sipAccountRepository: SipAccountRepository,
    val myHomeProviderService: MyHomeProviderService,
    val sipService: SipService,
    val proptechWizardRequired: Boolean,
) {
    private val registrationStarted = AtomicBoolean(false)
    private val mainStarted = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun startRegistrationIfNeeded() {
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
        myHomeProviderService.start()
    }

    fun startMainIfNeeded() {
        if (mainStarted.compareAndSet(false, true)) {
            runCatching {
                scope.launch {
                    sipAccountRepository.accounts.collectLatest { accounts ->
                        sipService.start(accounts)
                    }
                }
            }.onFailure { error ->
                mainStarted.set(false)
                Log.e("AppContainer", "Failed to start SIP service", error)
            }
        }
    }
}
