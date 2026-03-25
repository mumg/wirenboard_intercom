package net.muratov.intercom.voip

import android.content.Context
import android.util.Log
import android.view.TextureView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.muratov.intercom.data.model.CallSession
import net.muratov.intercom.data.model.SipAccountConfig
import net.muratov.intercom.data.model.SipAccountState
import net.muratov.intercom.data.model.SipRegistrationStatus

class SafeSipService(
    private val appContext: Context,
) : SipService {

    companion object {
        private const val TAG = "SafeSipService"
        private const val SIP_UNAVAILABLE_MESSAGE = "SIP/video is unavailable on this device"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var delegate: SipService? = null
    private var startupFailed = false

    private val _accountStates = MutableStateFlow<List<SipAccountState>>(emptyList())
    override val accountStates: StateFlow<List<SipAccountState>> = _accountStates.asStateFlow()

    private val _incomingCall = MutableStateFlow<CallSession?>(null)
    override val incomingCall: StateFlow<CallSession?> = _incomingCall.asStateFlow()

    private val _activeCall = MutableStateFlow<CallSession?>(null)
    override val activeCall: StateFlow<CallSession?> = _activeCall.asStateFlow()

    override fun start(accounts: List<SipAccountConfig>) {
        _accountStates.value = accounts.map { SipAccountState(config = it) }
        if (startupFailed) {
            markSipUnavailable(accounts)
            return
        }

        val service = runCatching { getOrCreateDelegate() }
            .onFailure { error ->
                startupFailed = true
                Log.e(TAG, "Unable to create SIP delegate", error)
                markSipUnavailable(accounts)
            }
            .getOrNull() ?: return

        runCatching {
            service.start(accounts)
        }.onFailure { error ->
            startupFailed = true
            Log.e(TAG, "Unable to start SIP delegate", error)
            markSipUnavailable(accounts)
        }
    }

    override fun answerIncomingCall() {
        delegate?.answerIncomingCall()
    }

    override fun declineIncomingCall() {
        delegate?.declineIncomingCall()
    }

    override fun endCurrentCall() {
        delegate?.endCurrentCall()
    }

    override fun bindRemoteVideo(textureView: TextureView) {
        delegate?.bindRemoteVideo(textureView)
    }

    override fun unbindRemoteVideo(textureView: TextureView) {
        delegate?.unbindRemoteVideo(textureView)
    }

    @Synchronized
    private fun getOrCreateDelegate(): SipService {
        delegate?.let { return it }

        val service = LinphoneSipService(appContext)
        scope.launch {
            service.accountStates.collectLatest { _accountStates.value = it }
        }
        scope.launch {
            service.incomingCall.collectLatest { _incomingCall.value = it }
        }
        scope.launch {
            service.activeCall.collectLatest { _activeCall.value = it }
        }
        delegate = service
        return service
    }

    private fun markSipUnavailable(accounts: List<SipAccountConfig>) {
        _incomingCall.value = null
        _activeCall.value = null
        _accountStates.value = accounts.map { account ->
            SipAccountState(
                config = account,
                status = SipRegistrationStatus.Failed,
                message = SIP_UNAVAILABLE_MESSAGE,
            )
        }
    }
}
