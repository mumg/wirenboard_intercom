package net.muratov.intercom.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import net.muratov.intercom.AppContainer
import net.muratov.intercom.data.model.CallSession
import net.muratov.intercom.data.model.ProviderOpenAction
import net.muratov.intercom.data.model.RtspStream
import net.muratov.intercom.data.model.SipAccountState
import net.muratov.intercom.provider.myhome.MyHomeAuthStatus
import net.muratov.intercom.provider.myhome.MyHomeContextSelectionPrompt
import net.muratov.intercom.provider.myhome.MyHomeLoginContext
import net.muratov.intercom.provider.myhome.MyHomeProviderState
import net.muratov.intercom.provider.myhome.MyHomeVerificationPrompt
import net.muratov.intercom.voip.CallSnapshot
import net.muratov.intercom.voip.SipCoreManager

data class MainUiState(
    val isConfigValid: Boolean = true,
    val configFilePath: String = "",
    val configErrorMessage: String? = null,
    val streams: List<RtspStream> = emptyList(),
    val sipAccounts: List<SipAccountState> = emptyList(),
    val incomingCall: CallSession? = null,
    val activeCall: CallSession? = null,
    val myHomeProviderState: MyHomeProviderState = MyHomeProviderState(),
    val contextSelectionPrompt: MyHomeContextSelectionPrompt? = null,
    val verificationPrompt: MyHomeVerificationPrompt? = null,
    val proptechWizardRequired: Boolean = false,
    val canEnterMainUi: Boolean = true,
    val stopTileVideoPlayback: Boolean = false,
)

class MainViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val stopTileVideoPlayback = MutableStateFlow(isIncomingCallActive(SipCoreManager.getCurrentCallSnapshot()))

    private val sipListener = object : SipCoreManager.Listener {
        override fun onCallChanged(snapshot: CallSnapshot?) {
            stopTileVideoPlayback.value = isIncomingCallActive(snapshot)
        }
    }

    init {
        SipCoreManager.addListener(sipListener)
    }

    val uiState: StateFlow<MainUiState> = combine(
        container.streamRepository.streams,
        container.myHomeProviderService.state,
        stopTileVideoPlayback,
    ) { streams, providerState, shouldStopTileVideoPlayback ->
        MainUiState(
            isConfigValid = container.isConfigValid,
            configFilePath = container.configFilePath,
            configErrorMessage = container.configErrorMessage,
            streams = streams,
            myHomeProviderState = providerState,
            contextSelectionPrompt = providerState.contextSelectionPrompt,
            verificationPrompt = providerState.verificationPrompt,
            proptechWizardRequired = container.proptechWizardRequired,
            canEnterMainUi = !container.proptechWizardRequired || providerState.status == MyHomeAuthStatus.Authorized,
            stopTileVideoPlayback = shouldStopTileVideoPlayback,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )


    fun submitVerificationCode(code: String, confirmationSecret: String) {
        container.myHomeProviderService.submitVerificationCode(code, confirmationSecret)
    }

    fun selectLoginContext(context: MyHomeLoginContext) {
        container.myHomeProviderService.selectLoginContext(context)
    }

    fun dismissVerificationPrompt() {
        container.myHomeProviderService.dismissVerificationPrompt()
    }

    fun startRegistrationIfNeeded() {
        container.startRegistrationIfNeeded()
    }

    fun restartRegistration() {
        container.restartRegistration()
    }

    fun startMainIfNeeded() {
        container.startMainIfNeeded()
    }

    fun canOpen(action: ProviderOpenAction): Boolean {
        return container.canOpen(action)
    }

    fun open(action: ProviderOpenAction) {
        viewModelScope.launch {
            container.open(action)
        }
    }

    suspend fun resolveFullscreenStream(streamId: String): RtspStream? {
        return container.streamRepository.resolveStream(streamId)
    }

    override fun onCleared() {
        SipCoreManager.removeListener(sipListener)
        super.onCleared()
    }

    private fun isIncomingCallActive(snapshot: CallSnapshot?): Boolean {
        if (snapshot == null || !snapshot.isIncoming) return false
        return when (snapshot.state) {
            org.linphone.core.Call.State.End,
            org.linphone.core.Call.State.Error,
            org.linphone.core.Call.State.Released -> false

            else -> true
        }
    }
}

class MainViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(container) as T
    }
}
