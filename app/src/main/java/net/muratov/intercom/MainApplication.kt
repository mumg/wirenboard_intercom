package net.muratov.intercom

import android.app.Application
import android.util.Log
import net.muratov.intercom.data.repository.AppConfigLoader
import net.muratov.intercom.data.repository.SipAccountRepository
import net.muratov.intercom.data.repository.StreamRepository
import net.muratov.intercom.voip.SafeSipService
import net.muratov.intercom.voip.SipService
import java.util.concurrent.atomic.AtomicBoolean

class MainApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val config = AppConfigLoader(this).load()
        appContainer = AppContainer(
            webViewUrl = config.webViewUrl,
            streamRepository = StreamRepository(config.streams),
            sipAccountRepository = SipAccountRepository(config.sipAccounts),
            sipService = SafeSipService(this),
        )
    }
}

data class AppContainer(
    val webViewUrl: String,
    val streamRepository: StreamRepository,
    val sipAccountRepository: SipAccountRepository,
    val sipService: SipService,
) {
    private val started = AtomicBoolean(false)

    fun startIfNeeded() {
        if (started.compareAndSet(false, true)) {
            runCatching {
                sipService.start(sipAccountRepository.accounts.value)
            }.onFailure { error ->
                started.set(false)
                Log.e("AppContainer", "Failed to start SIP service", error)
            }
        }
    }
}
