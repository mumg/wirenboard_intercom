package net.muratov.intercom.voip

import android.content.Context
import android.util.Log
import android.view.TextureView
import net.muratov.intercom.data.model.CallDirection
import net.muratov.intercom.data.model.CallSession
import net.muratov.intercom.data.model.SipAccountConfig
import net.muratov.intercom.data.model.SipAccountState
import net.muratov.intercom.data.model.SipRegistrationStatus
import net.muratov.intercom.data.model.SipTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.linphone.core.Account
import org.linphone.core.Call
import org.linphone.core.CallParams
import org.linphone.core.CodecPriorityPolicy
import org.linphone.core.Config
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.PayloadType
import org.linphone.core.RegistrationState
import org.linphone.core.VideoActivationPolicy
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class LinphoneSipService(
    context: Context,
) : SipService {

    companion object {
        private const val TAG = "LinphoneSipService"
        private const val SIP_UNAVAILABLE_MESSAGE = "SIP/video is unavailable on this device"
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val factory = Factory.instance()
    private val started = AtomicBoolean(false)
    private val accountMap = ConcurrentHashMap<String, Account>()
    private var startupFailure: Throwable? = null

    private val _accountStates = MutableStateFlow<List<SipAccountState>>(emptyList())
    override val accountStates: StateFlow<List<SipAccountState>> = _accountStates.asStateFlow()

    private val _incomingCall = MutableStateFlow<CallSession?>(null)
    override val incomingCall: StateFlow<CallSession?> = _incomingCall.asStateFlow()

    private val _activeCall = MutableStateFlow<CallSession?>(null)
    override val activeCall: StateFlow<CallSession?> = _activeCall.asStateFlow()

    private var remoteTextureView: TextureView? = null
    private var currentCall: Call? = null

    private val core: Core by lazy {
        createSafeCore().apply {
            isVideoCaptureEnabled = true
            isVideoDisplayEnabled = true
            configureVideoSafety()
            addListener(coreListener)
        }
    }

    private var iterateTimer: Timer? = null

    override fun start(accounts: List<SipAccountConfig>) {
        _accountStates.value = accounts.map { SipAccountState(config = it) }
        val core = getCoreOrNull()
        if (core == null) {
            markSipUnavailable(accounts)
            return
        }

        if (started.compareAndSet(false, true)) {
            runCatching {
                core.start()
                iterateTimer = Timer("linphone-iterate", true).apply {
                    scheduleAtFixedRate(object : TimerTask() {
                        override fun run() {
                            core.iterate()
                        }
                    }, 0L, 20L)
                }
            }.onFailure { error ->
                started.set(false)
                startupFailure = error
                Log.e(TAG, "Unable to start Linphone core", error)
                markSipUnavailable(accounts)
                return
            }
        }
        registerAccounts(core, accounts)
    }

    override fun answerIncomingCall() {
        getCoreOrNull() ?: return
        val call = currentCall ?: return
        val params: CallParams = core.createCallParams(call) ?: return
        params.isVideoEnabled = true
        call.acceptWithParams(params)
    }

    override fun declineIncomingCall() {
        currentCall?.decline(org.linphone.core.Reason.Declined)
        clearCallState()
    }

    override fun endCurrentCall() {
        currentCall?.terminate()
        clearCallState()
    }

    override fun bindRemoteVideo(textureView: TextureView) {
        val core = getCoreOrNull() ?: return
        remoteTextureView = textureView
        core.nativeVideoWindowId = textureView
        currentCall?.let {
            updateCallStates(it, it.state)
        }
    }

    override fun unbindRemoteVideo(textureView: TextureView) {
        val core = getCoreOrNull() ?: return
        if (remoteTextureView === textureView) {
            remoteTextureView = null
            core.nativeVideoWindowId = null
        }
    }

    private fun registerAccounts(core: Core, accounts: List<SipAccountConfig>) {
        accountMap.values.forEach(core::removeAccount)
        accountMap.clear()
        core.clearAllAuthInfo()

        accounts.forEach { config ->
            val authInfo = factory.createAuthInfo(
                config.username,
                null,
                config.password,
                null,
                null,
                config.domain,
                null,
            )
            core.addAuthInfo(authInfo)

            val identity = factory.createAddress(
                "sip:${config.username}@${config.domain}",
            ) ?: return@forEach
            identity.displayName = config.displayName

            val server = factory.createAddress(
                "sip:${config.domain}:${config.port};transport=${config.transport.linphoneName}",
            ) ?: return@forEach

            val params = core.createAccountParams().apply {
                identityAddress = identity
                serverAddress = server
                isRegisterEnabled = true
            }

            val account = core.createAccount(params)
            accountMap[config.id] = account
            core.addAccount(account)
            if (core.defaultAccount == null) {
                core.defaultAccount = account
            }
        }
    }

    private fun getCoreOrNull(): Core? {
        if (startupFailure != null) return null
        return runCatching { core }
            .onFailure { error ->
                startupFailure = error
                Log.e(TAG, "Linphone core initialization failed", error)
            }
            .getOrNull()
    }

    private fun createSafeCore(): Core {
        val safeConfig = createSafeCoreConfig()
        return runCatching {
            factory.createCoreWithConfig(safeConfig, appContext)
        }.onFailure { error ->
            Log.w(TAG, "Safe Linphone config failed, retrying with default config", error)
        }.getOrElse {
            factory.createCore(null, null, appContext)
        }
    }

    private fun createSafeCoreConfig(): Config {
        return factory.createConfig(null).apply {
            // Ask liblinphone to avoid aggressive codec probing on startup.
            setInt("misc", "dont_check_codecs", 1)
            setInt("misc", "add_missing_video_codecs", 0)
            setInt("misc", "codec_priority_policy", CodecPriorityPolicy.Basic.toInt())
        }
    }

    private fun Core.configureVideoSafety() {
        runCatching {
            setVideoCodecPriorityPolicy(CodecPriorityPolicy.Basic)

            val safeVideoPayloads = getVideoPayloadTypes()
                .filterNot { payload -> payload.mimeType.equals("H265", ignoreCase = true) }
                .filterNot { payload -> payload.mimeType.equals("HEVC", ignoreCase = true) }
                .filter { payload ->
                    val mime = payload.mimeType.uppercase()
                    mime == "H264" || mime == "VP8"
                }

            if (safeVideoPayloads.isNotEmpty()) {
                setVideoPayloadTypes(safeVideoPayloads.toTypedArray())
            }

            val policy = getVideoActivationPolicy()
            policy.automaticallyAccept = true
            policy.automaticallyInitiate = true
            setVideoActivationPolicy(policy)
        }.onFailure { error ->
            Log.w(TAG, "Unable to apply safe video codec policy", error)
        }
    }

    private fun markSipUnavailable(accounts: List<SipAccountConfig>) {
        _accountStates.value = accounts.map { account ->
            SipAccountState(
                config = account,
                status = SipRegistrationStatus.Failed,
                message = SIP_UNAVAILABLE_MESSAGE,
            )
        }
    }

    private fun updateRegistration(account: Account, state: RegistrationState, message: String) {
        val identity = account.params.identityAddress?.username ?: return
        _accountStates.value = _accountStates.value.map { existing ->
            if (existing.config.username != identity) {
                existing
            } else {
                existing.copy(status = state.toUiStatus(), message = message)
            }
        }
    }

    private fun updateCallStates(call: Call, state: Call.State) {
        currentCall = call
        val session = CallSession(
            callId = call.callLog.callId ?: "call",
            remoteDisplayName = call.remoteAddress.displayName ?: call.remoteAddress.username.orEmpty(),
            remoteAddress = call.remoteAddress.asStringUriOnly(),
            hasVideo = call.currentParams.isVideoEnabled || call.remoteParams?.isVideoEnabled == true,
            direction = if (call.dir == Call.Dir.Incoming) CallDirection.Incoming else CallDirection.Outgoing,
            stateLabel = state.name,
        )

        when (state) {
            Call.State.IncomingReceived,
            Call.State.IncomingEarlyMedia,
            Call.State.UpdatedByRemote -> {
                _incomingCall.value = session
                if (remoteTextureView != null) {
                    core.nativeVideoWindowId = remoteTextureView
                }
            }

            Call.State.StreamsRunning,
            Call.State.Connected -> {
                _incomingCall.value = null
                _activeCall.value = session
            }

            Call.State.End,
            Call.State.Error,
            Call.State.Released -> {
                clearCallState()
            }

            else -> {
                if (call.dir == Call.Dir.Outgoing) {
                    _activeCall.value = session
                }
            }
        }
    }

    private fun clearCallState() {
        _incomingCall.value = null
        _activeCall.value = null
        currentCall = null
    }

    private val coreListener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState,
            message: String,
        ) {
            scope.launch {
                updateRegistration(account, state, message)
            }
        }

        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State,
            message: String,
        ) {
            scope.launch {
                updateCallStates(call, state)
            }
        }
    }
}

private fun RegistrationState.toUiStatus(): SipRegistrationStatus = when (this) {
    RegistrationState.Ok -> SipRegistrationStatus.Ok
    RegistrationState.Progress,
    RegistrationState.Refreshing -> SipRegistrationStatus.Progress
    RegistrationState.Cleared -> SipRegistrationStatus.Cleared
    RegistrationState.Failed -> SipRegistrationStatus.Failed
    else -> SipRegistrationStatus.Idle
}

private val SipTransport.linphoneName: String
    get() = when (this) {
        SipTransport.UDP -> "udp"
        SipTransport.TCP -> "tcp"
        SipTransport.TLS -> "tls"
    }
