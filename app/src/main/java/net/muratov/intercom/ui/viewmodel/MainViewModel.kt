package net.muratov.intercom.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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

data class MainUiState(
    val streams: List<RtspStream> = emptyList(),
    val sipAccounts: List<SipAccountState> = emptyList(),
    val incomingCall: CallSession? = null,
    val activeCall: CallSession? = null,
    val myHomeProviderState: MyHomeProviderState = MyHomeProviderState(),
    val contextSelectionPrompt: MyHomeContextSelectionPrompt? = null,
    val verificationPrompt: MyHomeVerificationPrompt? = null,
    val proptechWizardRequired: Boolean = false,
    val canEnterMainUi: Boolean = true,
)

class MainViewModel(
    private val container: AppContainer,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = combine(
        container.streamRepository.streams,
        container.sipService.accountStates,
        container.sipService.incomingCall,
        container.sipService.activeCall,
        container.myHomeProviderService.state,
    ) { streams, accounts, incomingCall, activeCall, providerState ->
        MainUiState(
            streams = streams,
            sipAccounts = accounts,
            incomingCall = incomingCall,
            activeCall = activeCall,
            myHomeProviderState = providerState,
            contextSelectionPrompt = providerState.contextSelectionPrompt,
            verificationPrompt = providerState.verificationPrompt,
            proptechWizardRequired = container.proptechWizardRequired,
            canEnterMainUi = !container.proptechWizardRequired || providerState.status == MyHomeAuthStatus.Authorized,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    fun answerIncomingCall() {
        container.sipService.answerIncomingCall()
    }

    fun rejectIncomingCall() {
        container.sipService.declineIncomingCall()
    }

    fun endActiveCall() {
        container.sipService.endCurrentCall()
    }

    fun submitVerificationCode(code: String) {
        container.myHomeProviderService.submitVerificationCode(code)
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
}

class MainViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(container) as T
    }
}
