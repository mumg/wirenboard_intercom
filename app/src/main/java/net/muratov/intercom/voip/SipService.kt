package net.muratov.intercom.voip

import android.view.TextureView
import net.muratov.intercom.data.model.CallSession
import net.muratov.intercom.data.model.SipAccountConfig
import net.muratov.intercom.data.model.SipAccountState
import kotlinx.coroutines.flow.StateFlow

interface SipService {
    val accountStates: StateFlow<List<SipAccountState>>
    val incomingCall: StateFlow<CallSession?>
    val activeCall: StateFlow<CallSession?>

    fun start(accounts: List<SipAccountConfig>)
    fun answerIncomingCall()
    fun declineIncomingCall()
    fun endCurrentCall()
    fun bindRemoteVideo(textureView: TextureView)
    fun unbindRemoteVideo(textureView: TextureView)
}
