package net.muratov.intercom.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import net.muratov.intercom.AppContainer
import net.muratov.intercom.data.model.CallSession
import net.muratov.intercom.data.model.RtspStream
import net.muratov.intercom.data.model.SipAccountState

data class MainUiState(
    val streams: List<RtspStream> = emptyList(),
    val sipAccounts: List<SipAccountState> = emptyList(),
    val incomingCall: CallSession? = null,
    val activeCall: CallSession? = null,
)

class MainViewModel(
    private val container: AppContainer,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = combine(
        container.streamRepository.streams,
        container.sipService.accountStates,
        container.sipService.incomingCall,
        container.sipService.activeCall,
    ) { streams, accounts, incomingCall, activeCall ->
        MainUiState(
            streams = streams,
            sipAccounts = accounts,
            incomingCall = incomingCall,
            activeCall = activeCall,
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
}

class MainViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(container) as T
    }
}
