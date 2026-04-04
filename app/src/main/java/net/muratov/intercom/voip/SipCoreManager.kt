package net.muratov.intercom.voip

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import net.muratov.intercom.logging.IntercomFileLogger
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
import java.util.UUID
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
    private const val TAG = "SipCoreManager"
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
    private val registrationUidBySipAccountId = mutableMapOf<String, String>()
    private val sipAccountIdByRegistrationUid = mutableMapOf<String, String>()

    private val coreListener = object : CoreListenerStub() {
        override fun onGlobalStateChanged(core: Core, state: GlobalState, message: String) {
            IntercomFileLogger.i(
                TAG,
                "onGlobalStateChanged state=$state message=$message coreReadyBefore=$coreReady pendingCredentials=${pendingCredentials.size}",
            )
            if (state == GlobalState.On) {
                coreReady = true
                updateMediaCapabilities(core, microphoneAllowed, cameraAllowed)
                dispatchCoreStarted()
                synchronized(pendingCredentials) {
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
            IntercomFileLogger.i(
                TAG,
                "onAccountRegistrationStateChanged state=$state message=$message idkey=${account.params.idkey} customParam=${account.getCustomParam(ACCOUNT_LINK_CUSTOM_PARAM)}",
            )
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
                resolveSipAccountIdForCall(core, call)
            } else {
                null
            }
            IntercomFileLogger.i(
                TAG,
                "onCallStateChanged state=$currentState message=$message callStillActive=$callStillActive remote=${call.remoteAddress.asStringUriOnly()} to=${call.toAddress.asStringUriOnly()} resolvedAccountId=$activeCallAccountId callsNb=${core.callsNb}",
            )

            if (call.dir == Call.Dir.Incoming &&
                (currentState == Call.State.IncomingReceived || currentState == Call.State.IncomingEarlyMedia) &&
                !incomingActivityLaunchRequested
            ) {
                IntercomFileLogger.i(TAG, "Incoming call detected, scheduling IncomingCallActivity launch")
                incomingActivityLaunchRequested = true
                launchIncomingCallActivity()
            } else if (!callStillActive) {
                IntercomFileLogger.i(TAG, "Call no longer active, resetting launch flags")
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
            IntercomFileLogger.d(
                TAG,
                "Updated call snapshot state=$currentState receivingVideo=$isReceivingVideo sendingVideo=$isSendingVideo accountId=$activeCallAccountId",
            )
            dispatchCallChanged(lastCallSnapshot)
        }
    }

    fun initialize(context: Context) {
        if (::coreThread.isInitialized) return
        IntercomFileLogger.i(TAG, "initialize() starting")

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
            IntercomFileLogger.i(TAG, "Core thread started, creating Linphone core")
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
            IntercomFileLogger.i(TAG, "Linphone core.start() invoked")
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        IntercomFileLogger.d(TAG, "addListener listener=${listener::class.java.name} total=${listeners.size}")
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
        IntercomFileLogger.d(TAG, "removeListener listener=${listener::class.java.name} total=${listeners.size}")
    }


    fun register(credentials: SipCredentials) {
        val registrationUid = ensureRegistrationUid(credentials.id)
        IntercomFileLogger.i(
            TAG,
            "register requested sipAccountId=${credentials.id} username=${credentials.username} domain=${credentials.domain} port=${credentials.port} transport=${credentials.transport} stun=${credentials.stunServer} ice=${credentials.iceEnabled} registrationUid=$registrationUid coreReady=$coreReady",
        )
        if (!coreReady) {
            synchronized(pendingCredentials) {
                pendingCredentials.add(credentials)
                IntercomFileLogger.d(TAG, "Queued credentials pending=${pendingCredentials.size}")
            }
        } else {
            onCoreThread { _ ->
                registerInternal(credentials)
            }
        }
    }

    fun updatePermissions(hasMicrophone: Boolean, hasCamera: Boolean) {
        microphoneAllowed = hasMicrophone
        cameraAllowed = hasCamera
        IntercomFileLogger.i(TAG, "updatePermissions microphone=$hasMicrophone camera=$hasCamera")
        onCoreThread { core ->
            updateMediaCapabilities(core, hasMicrophone, hasCamera)
            applyCurrentVideoWindows(core)
        }
    }

    fun attachVideoWindows(remoteWindow: Any?, previewWindow: Any?) {
        remoteVideoWindow = remoteWindow
        previewVideoWindow = previewWindow
        IntercomFileLogger.i(
            TAG,
            "attachVideoWindows remote=${remoteWindow?.javaClass?.simpleName} preview=${previewWindow?.javaClass?.simpleName}",
        )
        onCoreThread(::applyCurrentVideoWindows)
    }

    fun answerIncomingCall() {
        onCoreThread { core ->
            val call = findCurrentManagedCall(core) ?: return@onCoreThread
            IntercomFileLogger.i(
                TAG,
                "answerIncomingCall state=${call.state} remote=${call.remoteAddress.asStringUriOnly()} accountId=$activeCallAccountId microphoneAllowed=$microphoneAllowed",
            )
            val params = core.createCallParams(call)
            if (params == null) {
                IntercomFileLogger.w(TAG, "answerIncomingCall createCallParams returned null, using accept()")
                call.accept()
                return@onCoreThread
            }

            params.isMicEnabled = microphoneAllowed
            params.isVideoEnabled = true
            params.videoDirection = MediaDirection.RecvOnly


            updateMediaCapabilities(core, microphoneAllowed, false)
            call.acceptWithParams(params)
            IntercomFileLogger.i(TAG, "acceptWithParams invoked videoEnabled=${params.isVideoEnabled} videoDirection=${params.videoDirection}")
        }
    }

    fun terminateCurrentCall() {
        onCoreThread { core ->
            val call = findCurrentManagedCall(core) ?: return@onCoreThread
            IntercomFileLogger.i(
                TAG,
                "terminateCurrentCall state=${call.state} dir=${call.dir} remote=${call.remoteAddress.asStringUriOnly()} accountId=$activeCallAccountId",
            )
            if (call.dir == Call.Dir.Incoming &&
                (call.state == Call.State.IncomingReceived || call.state == Call.State.IncomingEarlyMedia)
            ) {
                call.decline(Reason.Declined)
                IntercomFileLogger.i(TAG, "Incoming ringing call declined")
            } else {
                call.terminate()
                IntercomFileLogger.i(TAG, "Active call terminated")
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
        val call = activeCall
            ?: core.currentCall
            ?: core.calls.firstOrNull { call ->
                call.state != Call.State.End &&
                    call.state != Call.State.Error &&
                    call.state != Call.State.Released
            }
        IntercomFileLogger.d(
            TAG,
            "findCurrentManagedCall result=${call?.remoteAddress?.asStringUriOnly()} state=${call?.state}",
        )
        return call
    }


    private fun registerInternal(credentials: SipCredentials) {
        accountIdByUsername[credentials.username] = credentials.id
        val registrationUid = ensureRegistrationUid(credentials.id)
        IntercomFileLogger.i(
            TAG,
            "registerInternal sipAccountId=${credentials.id} username=${credentials.username} registrationUid=$registrationUid",
        )
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
        accountParams.addCustomParam(ACCOUNT_LINK_CUSTOM_PARAM, registrationUid)
        accountParams.natPolicy = createNatPolicy(credentials)

        val account = core.createAccount(accountParams)
        account.addCustomParam(ACCOUNT_LINK_CUSTOM_PARAM, registrationUid)
        core.addAuthInfo(authInfo)
        core.addAccount(account)
        IntercomFileLogger.i(
            TAG,
            "Account added sipAccountId=${credentials.id} idkey=${account.params.idkey} customParam=${account.getCustomParam(ACCOUNT_LINK_CUSTOM_PARAM)}",
        )
    }

    private fun createNatPolicy(credentials: SipCredentials) = core.createNatPolicy().apply {
        setIceEnabled(credentials.iceEnabled)
        val stunServer = credentials.stunServer.trim()
        setStunEnabled(stunServer.isNotEmpty())
        setStunServer(stunServer.ifEmpty { null })
        IntercomFileLogger.d(
            TAG,
            "createNatPolicy sipAccountId=${credentials.id} stunEnabled=${stunServer.isNotEmpty()} stunServer=${stunServer.ifEmpty { "<empty>" }} iceEnabled=${credentials.iceEnabled}",
        )
    }


    private fun applyCurrentVideoWindows(core: Core) {
        val call = activeCall ?: core.currentCall
        if (call == null) {
            core.nativeVideoWindowId = null
            core.nativePreviewWindowId = null
            IntercomFileLogger.d(TAG, "applyCurrentVideoWindows no active call, windows cleared")
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
        IntercomFileLogger.d(
            TAG,
            "applyCurrentVideoWindows state=${call.state} remoteWindow=${remoteVideoWindow?.javaClass?.simpleName} previewWindow=${previewVideoWindow?.javaClass?.simpleName} cameraAllowed=$cameraAllowed isSendingVideo=$isSendingVideo",
        )
    }

    private fun updateMediaCapabilities(core: Core, hasMicrophone: Boolean, hasCamera: Boolean) {
        core.isMicEnabled = hasMicrophone
        core.isVideoCaptureEnabled = hasCamera
        core.isVideoDisplayEnabled = true
        IntercomFileLogger.d(TAG, "updateMediaCapabilities mic=$hasMicrophone camera=$hasCamera")
    }

    private fun onCoreThread(block: (Core) -> Unit) {
        if (!::coreHandler.isInitialized || !::core.isInitialized) {
            IntercomFileLogger.w(TAG, "onCoreThread skipped because core handler or core is not initialized")
            return
        }
        coreHandler.post {
            block(core)
        }
    }

    private fun dispatchCoreStarted() {
        IntercomFileLogger.i(TAG, "dispatchCoreStarted listeners=${listeners.size}")
        mainHandler.post {
            listeners.forEach { it.onCoreStarted() }
        }
    }

    private fun dispatchRegistrationChanged(snapshot: RegistrationSnapshot) {
        IntercomFileLogger.d(TAG, "dispatchRegistrationChanged state=${snapshot.state} identity=${snapshot.identity} message=${snapshot.message}")
        mainHandler.post {
            listeners.forEach { it.onRegistrationChanged(snapshot) }
        }
    }

    private fun dispatchCallChanged(snapshot: CallSnapshot?) {
        IntercomFileLogger.d(TAG, "dispatchCallChanged snapshot=${snapshot?.state} remote=${snapshot?.remoteAddress} incoming=${snapshot?.isIncoming}")
        mainHandler.post {
            listeners.forEach { it.onCallChanged(snapshot) }
        }
    }

    private fun dispatchRegistrationError(message: String) {
        IntercomFileLogger.e(TAG, "dispatchRegistrationError message=$message")
    }

    private fun copyAsset(assetName: String, targetFile: File) {
        appContext.assets.open(assetName).use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        IntercomFileLogger.d(TAG, "copyAsset asset=$assetName target=${targetFile.absolutePath}")
    }

    private fun normalizeServerAddress(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith("sip:") || trimmed.startsWith("sips:")) {
            trimmed
        } else {
            "sip:$trimmed"
        }
    }

    private fun ensureRegistrationUid(sipAccountId: String): String {
        synchronized(registrationUidBySipAccountId) {
            return registrationUidBySipAccountId.getOrPut(sipAccountId) {
                UUID.randomUUID().toString().also { registrationUid ->
                    sipAccountIdByRegistrationUid[registrationUid] = sipAccountId
                    IntercomFileLogger.i(TAG, "Generated registrationUid=$registrationUid for sipAccountId=$sipAccountId")
                }
            }
        }
    }

    private fun resolveSipAccountIdForCall(core: Core, call: Call): String? {
        val localAddress = call.toAddress
        IntercomFileLogger.d(
            TAG,
            "resolveSipAccountIdForCall start to=${localAddress.asStringUriOnly()} username=${localAddress.username ?: "<none>"} domain=${localAddress.domain ?: "<none>"} accountListSize=${core.accountList.size}",
        )
        core.accountList.forEachIndexed { index, account ->
            val identityAddress = account.params.identityAddress
            IntercomFileLogger.d(
                TAG,
                "resolveSipAccountIdForCall inspect[$index] idkey=${account.params.idkey ?: "<none>"} identity=${identityAddress?.asStringUriOnly() ?: "<none>"} accountCustom=${account.getCustomParam(ACCOUNT_LINK_CUSTOM_PARAM) ?: "<none>"} paramsCustom=${account.params.getCustomParam(ACCOUNT_LINK_CUSTOM_PARAM) ?: "<none>"}",
            )
        }
        val matchedAccount = core.accountList.firstOrNull { account ->
            val identityAddress = account.params.identityAddress ?: return@firstOrNull false
            val sameDomain = identityAddress.domain.equals(localAddress.domain, ignoreCase = true)
            val localUsername = localAddress.username
            val sameUsername = localUsername.isNullOrBlank() || identityAddress.username.equals(localUsername, ignoreCase = true)
            IntercomFileLogger.d(
                TAG,
                "resolveSipAccountIdForCall compare identity=${identityAddress.asStringUriOnly()} sameDomain=$sameDomain sameUsername=$sameUsername",
            )
            sameDomain && sameUsername
        }
        IntercomFileLogger.d(
            TAG,
            "resolveSipAccountIdForCall matchedAccount=${matchedAccount?.params?.identityAddress?.asStringUriOnly() ?: "<none>"} matchedIdkey=${matchedAccount?.params?.idkey ?: "<none>"}",
        )

        val registrationUid = matchedAccount
            ?.getCustomParam(ACCOUNT_LINK_CUSTOM_PARAM)
            ?.takeIf { it.isNotBlank() }
            ?: matchedAccount
                ?.params
                ?.getCustomParam(ACCOUNT_LINK_CUSTOM_PARAM)
                ?.takeIf { it.isNotBlank() }
        IntercomFileLogger.d(
            TAG,
            "resolveSipAccountIdForCall extracted registrationUid=${registrationUid ?: "<none>"}",
        )

        if (!registrationUid.isNullOrBlank()) {
            synchronized(registrationUidBySipAccountId) {
                sipAccountIdByRegistrationUid[registrationUid]?.let {
                    IntercomFileLogger.i(
                        TAG,
                        "resolveSipAccountIdForCall matched registrationUid=$registrationUid sipAccountId=$it to=${localAddress.asStringUriOnly()}",
                    )
                    return it
                }
            }
            IntercomFileLogger.w(
                TAG,
                "resolveSipAccountIdForCall registrationUid=$registrationUid not found in sipAccountIdByRegistrationUid map keys=${sipAccountIdByRegistrationUid.keys}",
            )
        }

        val fallbackAccountId = localAddress.username?.let(accountIdByUsername::get)
        IntercomFileLogger.w(
            TAG,
            "resolveSipAccountIdForCall fell back to username mapping to=${localAddress.asStringUriOnly()} registrationUid=${registrationUid ?: "<none>"} fallbackAccountId=${fallbackAccountId ?: "<none>"}",
        )
        return fallbackAccountId
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
            IntercomFileLogger.i(TAG, "Launching IncomingCallActivity accountId=$activeCallAccountId")
            appContext.startActivity(intent)
        }
    }

    private const val ACCOUNT_LINK_CUSTOM_PARAM = "intercom_account_uid"
}
