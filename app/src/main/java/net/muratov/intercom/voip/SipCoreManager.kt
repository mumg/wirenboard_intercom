package net.muratov.intercom.voip

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.linphone.core.Account
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.GlobalState
import org.linphone.core.LogLevel
import org.linphone.core.LogCollectionState
import org.linphone.core.MediaDirection
import org.linphone.core.Reason
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

data class SipCredentials(
    val username: String,
    val password: String,
    val domain: String,
    val server: String,
    val port: Int,
    val transport: TransportType,
    val stunServer: String,
    val iceEnabled: Boolean,
    val id: String
)

data class RegistrationSnapshot(
    val state: RegistrationState?,
    val message: String,
    val identity: String
)

data class CallSnapshot(
    val state: Call.State,
    val message: String,
    val remoteAddress: String,
    val isIncoming: Boolean,
    val isReceivingVideo: Boolean,
    val isSendingVideo: Boolean
)

object SipCoreManager {
    interface Listener {
        fun onCoreStarted() {}

        fun onRegistrationChanged(snapshot: RegistrationSnapshot) {}

        fun onCallChanged(snapshot: CallSnapshot?) {}
    }


    private lateinit var appContext: Context

    private lateinit var coreThread: HandlerThread
    private lateinit var coreHandler: Handler
    private lateinit var core: Core
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile
    private var coreReady = false

    private var activeCall: Call? = null
    private var lastCallSnapshot: CallSnapshot? = null
    private var remoteVideoWindow: Any? = null
    private var previewVideoWindow: Any? = null
    private var microphoneAllowed = false
    private var cameraAllowed = false
    private var incomingActivityLaunchRequested = false
    private var inCallActivityLaunchRequested = false
    private var activeCallAccountId: String? = null

    private var pendingCredentials: MutableList<SipCredentials> = mutableListOf()
    private val accountIdByUsername = mutableMapOf<String, String>()

    private val coreListener = object : CoreListenerStub() {
        override fun onGlobalStateChanged(core: Core, state: GlobalState, message: String) {
            if (state == GlobalState.On) {
                coreReady = true
                updateMediaCapabilities(core, microphoneAllowed, cameraAllowed)
                dispatchCoreStarted()
                synchronized(pendingCredentials){
                    pendingCredentials.forEach {
                        registerInternal(it)
                    }
                    pendingCredentials.clear()
                }
            } else if (state == GlobalState.Shutdown || state == GlobalState.Off) {
                coreReady = false
            }
        }

        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState?,
            message: String
        ) {

        }

        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State?,
            message: String
        ) {
            val currentState = state ?: call.state
            val callStillActive = currentState != Call.State.End &&
                    currentState != Call.State.Error &&
                    currentState != Call.State.Released
            activeCall = if (callStillActive) call else null
            activeCallAccountId = if (callStillActive) {
                call.toAddress.username?.let(accountIdByUsername::get)
            } else {
                null
            }

            if (call.dir == Call.Dir.Incoming &&
                (currentState == Call.State.IncomingReceived || currentState == Call.State.IncomingEarlyMedia) &&
                !incomingActivityLaunchRequested
            ) {
                incomingActivityLaunchRequested = true
                launchIncomingCallActivity()
            } else if (!callStillActive) {
                incomingActivityLaunchRequested = false
                inCallActivityLaunchRequested = false
            }

            if (callStillActive && (currentState == Call.State.IncomingEarlyMedia ||
                        currentState == Call.State.Connected ||
                        currentState == Call.State.StreamsRunning)
            ) {
                applyCurrentVideoWindows(core)
            }

            if (!callStillActive) {
                core.nativeVideoWindowId = null
                core.nativePreviewWindowId = null
            }

            val params = call.currentParams
            val direction = params.videoDirection
            val isSendingVideo = direction == MediaDirection.SendRecv || direction == MediaDirection.SendOnly
            val isReceivingVideo = direction == MediaDirection.SendRecv || direction == MediaDirection.RecvOnly
            lastCallSnapshot = CallSnapshot(
                state = currentState,
                message = message,
                remoteAddress = call.remoteAddress.asStringUriOnly(),
                isIncoming = call.dir == Call.Dir.Incoming,
                isReceivingVideo = isReceivingVideo,
                isSendingVideo = isSendingVideo
            )
            dispatchCallChanged(lastCallSnapshot)
        }
    }

    fun initialize(context: Context) {
        if (::coreThread.isInitialized) return

        appContext = context.applicationContext

        Factory.instance().setCacheDir(appContext.cacheDir.absolutePath)
        Factory.instance().setLogCollectionPath(appContext.filesDir.absolutePath)
        Factory.instance().enableLogCollection(LogCollectionState.Enabled)
        Factory.instance().setDebugMode(true, "Linphone")
        Factory.instance().setLoggerDomain("Linphone")
        Factory.instance().enableLogcatLogs(true)
        val loggingService = Factory.instance().getLoggingService()
        loggingService.setDomain("Linphone")
        loggingService.setLogLevel(LogLevel.Trace)

        copyAsset("linphonerc_default", File(appContext.filesDir, "simple_linphonerc_default"))
        copyAsset("linphonerc_factory", File(appContext.filesDir, "simple_linphonerc_factory"))

        coreThread = HandlerThread("SimpleReceiverCore")
        coreThread.start()
        coreHandler = Handler(coreThread.looper)
        coreHandler.post {
            val config = Factory.instance().createConfigWithFactory(
                File(appContext.filesDir, "simple_linphonerc_default").absolutePath,
                File(appContext.filesDir, "simple_linphonerc_factory").absolutePath
            )
            core = Factory.instance().createCoreWithConfig(config, appContext)
            val policy = core.videoActivationPolicy.clone()
            policy.automaticallyInitiate = false
            policy.automaticallyAccept = true
            policy.automaticallyAcceptDirection = MediaDirection.RecvOnly
            core.videoActivationPolicy = policy
            // We handle incoming ringtone ourselves with account-specific MP3 files.
            // Disable every built-in Linphone ringing path to avoid parallel sounds.
            core.setNativeRingingEnabled(false)
            core.setVibrationOnIncomingCallEnabled(false)
            core.setRing(null)
            core.isVideoDisplayEnabled = true
            core.config.setBool("sip", "incoming_calls_early_media", true)
            core.config.setBool("misc", "real_early_media", true)
            core.config.setInt("sound", "disable_ringing", 1)
            core.ringDuringIncomingEarlyMedia = false
            core.isAutoIterateEnabled = true
            core.addListener(coreListener)
            core.start()
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }


    fun register(credentials: SipCredentials) {
        if (!coreReady){
            synchronized(pendingCredentials){
                pendingCredentials.add(credentials)
            }
        }else {
            onCoreThread { core ->
                registerInternal(credentials)
            }
        }
    }

    fun updatePermissions(hasMicrophone: Boolean, hasCamera: Boolean) {
        microphoneAllowed = hasMicrophone
        cameraAllowed = hasCamera
        onCoreThread { core ->
            updateMediaCapabilities(core, hasMicrophone, hasCamera)
            applyCurrentVideoWindows(core)
        }
    }

    fun attachVideoWindows(remoteWindow: Any?, previewWindow: Any?) {
        remoteVideoWindow = remoteWindow
        previewVideoWindow = previewWindow
        onCoreThread(::applyCurrentVideoWindows)
    }

    fun answerIncomingCall() {
        onCoreThread { core ->
            val call = findCurrentManagedCall(core) ?: return@onCoreThread
            val params = core.createCallParams(call)
            if (params == null) {
                call.accept()
                return@onCoreThread
            }

            params.isMicEnabled = microphoneAllowed
            params.isVideoEnabled = true
            params.videoDirection = MediaDirection.RecvOnly


            updateMediaCapabilities(core, microphoneAllowed, false)
            call.acceptWithParams(params)
        }
    }

    fun terminateCurrentCall() {
        onCoreThread { core ->
            val call = findCurrentManagedCall(core) ?: return@onCoreThread
            if (call.dir == Call.Dir.Incoming &&
                (call.state == Call.State.IncomingReceived || call.state == Call.State.IncomingEarlyMedia)
            ) {
                call.decline(Reason.Declined)
            } else {
                call.terminate()
            }
        }
    }

    fun getCurrentCallSnapshot(): CallSnapshot? {
        return lastCallSnapshot
    }

    fun getCurrentCallAccountId(): String? {
        return activeCallAccountId
    }

    private fun findCurrentManagedCall(core: Core): Call? {
        return activeCall
            ?: core.currentCall
            ?: core.calls.firstOrNull { call ->
                call.state != Call.State.End &&
                    call.state != Call.State.Error &&
                    call.state != Call.State.Released
            }
    }


    private fun registerInternal(credentials: SipCredentials) {
        accountIdByUsername[credentials.username] = credentials.id
        val identityAddress = Factory.instance().createAddress(
            "sip:${credentials.username}@${credentials.domain}"
        ) ?: run {
            dispatchRegistrationError("Invalid identity address")
            return
        }
        val serverAddress = Factory.instance().createAddress(
            normalizeServerAddress(credentials.server.ifBlank { credentials.domain })
        ) ?: run {
            dispatchRegistrationError("Invalid registrar/proxy address")
            return
        }
        serverAddress.transport = credentials.transport
        serverAddress.setPort(credentials.port)

        val authInfo = Factory.instance().createAuthInfo(
            credentials.username,
            null,
            credentials.password,
            null,
            null,
            credentials.domain
        )
        val accountParams = core.createAccountParams()
        accountParams.identityAddress = identityAddress
        accountParams.serverAddress = serverAddress
        accountParams.isRegisterEnabled = true
        accountParams.pushNotificationAllowed = false
        accountParams.idkey = credentials.id
        accountParams.natPolicy = createNatPolicy(credentials)

        val account = core.createAccount(accountParams)
        core.addAuthInfo(authInfo)
        core.addAccount(account)
    }

    private fun createNatPolicy(credentials: SipCredentials) = core.createNatPolicy().apply {
        setIceEnabled(credentials.iceEnabled)
        val stunServer = credentials.stunServer.trim()
        setStunEnabled(stunServer.isNotEmpty())
        setStunServer(stunServer.ifEmpty { null })
    }


    private fun applyCurrentVideoWindows(core: Core) {
        val call = activeCall ?: core.currentCall
        if (call == null) {
            core.nativeVideoWindowId = null
            core.nativePreviewWindowId = null
            return
        }

        core.nativeVideoWindowId = remoteVideoWindow
        val params = call.currentParams
        val direction = params.videoDirection
        val isSendingVideo = direction == MediaDirection.SendRecv || direction == MediaDirection.SendOnly
        core.nativePreviewWindowId = if (cameraAllowed && isSendingVideo) {
            previewVideoWindow
        } else {
            null
        }
    }

    private fun updateMediaCapabilities(core: Core, hasMicrophone: Boolean, hasCamera: Boolean) {
        core.isMicEnabled = hasMicrophone
        core.isVideoCaptureEnabled = hasCamera
        core.isVideoDisplayEnabled = true
    }

    private fun onCoreThread(block: (Core) -> Unit) {
        if (!::coreHandler.isInitialized || !::core.isInitialized) return
        coreHandler.post {
            block(core)
        }
    }

    private fun dispatchCoreStarted() {
        mainHandler.post {
            listeners.forEach { it.onCoreStarted() }
        }
    }

    private fun dispatchRegistrationChanged(snapshot: RegistrationSnapshot) {
        mainHandler.post {
            listeners.forEach { it.onRegistrationChanged(snapshot) }
        }
    }

    private fun dispatchCallChanged(snapshot: CallSnapshot?) {
        mainHandler.post {
            listeners.forEach { it.onCallChanged(snapshot) }
        }
    }

    private fun dispatchRegistrationError(message: String) {

    }

    private fun copyAsset(assetName: String, targetFile: File) {
        appContext.assets.open(assetName).use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun normalizeServerAddress(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith("sip:") || trimmed.startsWith("sips:")) {
            trimmed
        } else {
            "sip:$trimmed"
        }
    }

    private fun launchIncomingCallActivity() {
        mainHandler.post {
            val intent = Intent(appContext, IncomingCallActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            appContext.startActivity(intent)
        }
    }

}
