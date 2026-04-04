package net.muratov.intercom.voip

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.muratov.intercom.databinding.ActivityIncomingCallBinding
import net.muratov.intercom.logging.IntercomFileLogger
import net.muratov.intercom.video.createRtspPlaybackView
import net.muratov.intercom.video.playRtspOnView
import net.muratov.intercom.video.releaseRtspPlaybackView
import org.linphone.core.Call
import java.io.File

class IncomingCallActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "IncomingCallActivity"
    }

    private lateinit var binding: ActivityIncomingCallBinding

    private var microphoneGranted = false
    private var cameraGranted = false
    private var incomingCallSound: IncomingCallSound? = null
    private var incomingCallSoundAccountId: String? = null
    private var previewPlaybackView: View? = null
    private var previewRefreshRequestId: Long = 0L

    private val appContainer: net.muratov.intercom.AppContainer
        get() = (application as net.muratov.intercom.MainApplication).appContainer

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateGrantedPermissions()
        SipCoreManager.updatePermissions(microphoneGranted, cameraGranted)
        refreshVideoWindows()
    }

    private val coreListener = object : SipCoreManager.Listener {
        override fun onCallChanged(snapshot: CallSnapshot?) {
            renderCall(snapshot)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IntercomFileLogger.i(TAG, "onCreate")
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        setupListeners()
        updateGrantedPermissions()
        requestPermissionsIfNeeded()
        renderCall(SipCoreManager.getCurrentCallSnapshot())
    }

    override fun onStart() {
        super.onStart()
        IntercomFileLogger.i(TAG, "onStart")
        SipCoreManager.addListener(coreListener)
    }

    override fun onResume() {
        super.onResume()
        IntercomFileLogger.i(TAG, "onResume")
        refreshVideoWindows()
    }

    override fun onPause() {
        IntercomFileLogger.i(TAG, "onPause")
        incomingCallSound?.stop()
        clearRemotePreviewPlayback()
        SipCoreManager.attachVideoWindows(null, null)
        super.onPause()
    }

    override fun onStop() {
        IntercomFileLogger.i(TAG, "onStop")
        incomingCallSound?.stop()
        SipCoreManager.removeListener(coreListener)
        super.onStop()
    }

    override fun onDestroy() {
        IntercomFileLogger.i(TAG, "onDestroy")
        incomingCallSound?.release()
        incomingCallSound = null
        clearRemotePreviewPlayback()
        super.onDestroy()
    }

    private fun setupListeners() {
        binding.answerButton.setOnClickListener {
            IntercomFileLogger.i(TAG, "answerButton clicked accountId=${SipCoreManager.getCurrentCallAccountId()}")
            incomingCallSound?.stop()
            SipCoreManager.answerIncomingCall()
        }

        binding.hangupButton.setOnClickListener {
            IntercomFileLogger.i(TAG, "hangupButton clicked accountId=${SipCoreManager.getCurrentCallAccountId()}")
            incomingCallSound?.stop()
            SipCoreManager.terminateCurrentCall()
        }

        binding.openButton.setOnClickListener {
            val accountId = SipCoreManager.getCurrentCallAccountId() ?: return@setOnClickListener
            IntercomFileLogger.i(TAG, "openButton clicked accountId=$accountId")
            val action = appContainer.sipAccountRepository.accounts.value
                .firstOrNull { it.id == accountId }
                ?.openAction
                ?: return@setOnClickListener
            lifecycleScope.launch {
                appContainer.open(action)
            }
        }
    }

    private fun renderCall(snapshot: CallSnapshot?) {
        IntercomFileLogger.d(
            TAG,
            "renderCall snapshotState=${snapshot?.state} remote=${snapshot?.remoteAddress} accountId=${SipCoreManager.getCurrentCallAccountId()}",
        )
        if (snapshot == null) {
            incomingCallSound?.stop()
            IntercomFileLogger.i(TAG, "renderCall snapshot is null, finishing activity")
            finish()
            return
        }

        ensureIncomingCallSoundForCurrentAccount()
        renderProviderTitle()

        val incomingPending = snapshot.state == Call.State.IncomingReceived ||
                snapshot.state == Call.State.IncomingEarlyMedia
        val activeCall = snapshot.state != Call.State.End &&
                snapshot.state != Call.State.Error &&
                snapshot.state != Call.State.Released

        if (activeCall && !incomingPending) {
            incomingCallSound?.stop()
            binding.answerButton.visibility = View.GONE
            binding.hangupButton.visibility = View.VISIBLE
            val accountId = SipCoreManager.getCurrentCallAccountId()
            val openAction = accountId?.let { id ->
                appContainer.sipAccountRepository.accounts.value.firstOrNull { it.id == id }?.openAction
            }
            binding.openButton.visibility = if (openAction != null) View.VISIBLE else View.GONE
            IntercomFileLogger.d(TAG, "renderCall active non-pending call openVisible=${openAction != null}")
            refreshVideoWindows()
            return
        }

        binding.answerButton.visibility = if (incomingPending) View.VISIBLE else View.GONE
        binding.hangupButton.visibility = if (activeCall) View.VISIBLE else View.GONE
        if (incomingPending) {
            incomingCallSound?.playLooping()
        } else {
            incomingCallSound?.stop()
        }
        val accountId = SipCoreManager.getCurrentCallAccountId()
        val openAction = accountId?.let { id ->
            appContainer.sipAccountRepository.accounts.value.firstOrNull { it.id == id }?.openAction
        }
        binding.openButton.visibility = if (openAction != null) View.VISIBLE else View.GONE
        IntercomFileLogger.d(
            TAG,
            "renderCall incomingPending=$incomingPending activeCall=$activeCall openVisible=${openAction != null}",
        )

        if (!activeCall) {
            incomingCallSound?.stop()
            IntercomFileLogger.i(TAG, "renderCall call is not active, finishing activity")
            finish()
            return
        }

        refreshVideoWindows()
    }

    private fun renderProviderTitle() {
        val accountId = SipCoreManager.getCurrentCallAccountId()
        val providerTitle = accountId
            ?.let { id -> appContainer.sipAccountRepository.accounts.value.firstOrNull { it.id == id } }
            ?.title
            .orEmpty()
        binding.providerTitleText.text = providerTitle
        binding.providerTitleText.visibility = if (providerTitle.isNotBlank()) View.VISIBLE else View.GONE
        IntercomFileLogger.d(
            TAG,
            "renderProviderTitle accountId=${accountId ?: "<none>"} title=${providerTitle.ifBlank { "<none>" }}",
        )
    }

    private fun refreshVideoWindows() {
        val currentAccount = getCurrentAccount()
        val requestId = ++previewRefreshRequestId
        IntercomFileLogger.d(
            TAG,
            "refreshVideoWindows accountId=${currentAccount?.id ?: "<none>"} requestId=$requestId",
        )
        if (currentAccount == null) {
            clearRemotePreviewPlayback()
            binding.remotePreviewContainer.visibility = View.GONE
            binding.remoteVideoSurface.visibility = View.VISIBLE
            IntercomFileLogger.i(TAG, "No current account, binding SIP video surface instead")
            SipCoreManager.attachVideoWindows(binding.remoteVideoSurface, null)
            return
        }
        lifecycleScope.launch {
            val preview = appContainer.sipAccountRepository.resolveIncomingPreview(currentAccount.id)
            if (requestId != previewRefreshRequestId) {
                IntercomFileLogger.d(
                    TAG,
                    "Ignoring stale preview resolution accountId=${currentAccount.id} requestId=$requestId latestRequestId=$previewRefreshRequestId",
                )
                return@launch
            }
            IntercomFileLogger.d(
                TAG,
                "refreshVideoWindows resolved accountId=${currentAccount.id} previewRtspUrl=${preview?.rtspUrl ?: "<none>"} headers=${preview?.headers?.keys ?: emptySet<String>()}",
            )
            if (preview != null) {
                binding.remoteVideoSurface.visibility = View.GONE
                binding.remotePreviewContainer.visibility = View.VISIBLE
                SipCoreManager.attachVideoWindows(null, null)
                val playbackView = previewPlaybackView ?: createRtspPlaybackView(
                    context = this@IncomingCallActivity,
                    playbackEngine = preview.playbackEngine,
                ).also { view ->
                    previewPlaybackView = view
                    binding.remotePreviewContainer.removeAllViews()
                    binding.remotePreviewContainer.addView(view)
                    IntercomFileLogger.i(TAG, "Created RTSP preview playback view engine=${preview.playbackEngine}")
                }
                playRtspOnView(
                    view = playbackView,
                    url = preview.rtspUrl,
                    headers = preview.headers,
                    muted = true,
                )
                IntercomFileLogger.i(TAG, "Started RTSP preview url=${preview.rtspUrl}")
                return@launch
            }

            clearRemotePreviewPlayback()
            binding.remotePreviewContainer.visibility = View.GONE
            binding.remoteVideoSurface.visibility = View.VISIBLE
            IntercomFileLogger.i(TAG, "No live RTSP preview, binding SIP video surface instead")
            SipCoreManager.attachVideoWindows(binding.remoteVideoSurface, null)
        }
    }

    private fun clearRemotePreviewPlayback() {
        previewPlaybackView?.let { view ->
            IntercomFileLogger.i(TAG, "Releasing RTSP preview playback view")
            releaseRtspPlaybackView(view)
            binding.remotePreviewContainer.removeView(view)
        }
        previewPlaybackView = null
    }

    private fun applyWindowInsets() {
        val actionsLayoutParams = binding.callActionsBar.layoutParams as ViewGroup.MarginLayoutParams
        val openButtonLayoutParams = binding.openButton.layoutParams as ViewGroup.MarginLayoutParams
        val baseActionsBottomMargin = actionsLayoutParams.bottomMargin
        val baseOpenButtonBottomMargin = openButtonLayoutParams.bottomMargin
        val baseOpenButtonEndMargin = openButtonLayoutParams.marginEnd
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val navigationBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            actionsLayoutParams.bottomMargin = baseActionsBottomMargin + navigationBarInsets.bottom
            binding.callActionsBar.layoutParams = actionsLayoutParams
            openButtonLayoutParams.bottomMargin = baseOpenButtonBottomMargin + navigationBarInsets.bottom
            openButtonLayoutParams.marginEnd = baseOpenButtonEndMargin + navigationBarInsets.right
            binding.openButton.layoutParams = openButtonLayoutParams
            insets
        }
    }

    private fun ensureIncomingCallSoundForCurrentAccount() {
        val accountId = SipCoreManager.getCurrentCallAccountId()
        if (incomingCallSound != null && incomingCallSoundAccountId == accountId) {
            IntercomFileLogger.d(TAG, "Incoming call sound already matches accountId=$accountId")
            return
        }
        incomingCallSound?.release()
        incomingCallSoundAccountId = accountId
        IntercomFileLogger.i(TAG, "Resolving incoming call sound for accountId=$accountId")
        incomingCallSound = resolveIncomingCallSoundForAccount(accountId)
    }

    private fun getCurrentAccount() = SipCoreManager.getCurrentCallAccountId()?.let { accountId ->
        appContainer.sipAccountRepository.accounts.value.firstOrNull { it.id == accountId }
    }.also { account ->
        IntercomFileLogger.d(
            TAG,
            "getCurrentAccount resolved accountId=${account?.id ?: "<none>"}",
        )
    }

    private fun resolveIncomingCallSoundForAccount(accountId: String?): IncomingCallSound? {
        val ringtoneAsset = accountId?.let { id ->
            appContainer.sipAccountRepository.accounts.value.firstOrNull { it.id == id }?.ringtoneAsset
        }
        if (ringtoneAsset.isNullOrBlank()) {
            IntercomFileLogger.d(TAG, "No ringtoneAsset configured for account=$accountId")
            return null
        }
        if (!ringtoneAsset.endsWith(".mp3", ignoreCase = true)) {
            IntercomFileLogger.w(
                TAG,
                "ringtoneAsset must point to an mp3 file, got=$ringtoneAsset for account=$accountId",
            )
            return null
        }
        return createAssetSound(ringtoneAsset)?.also {
            IntercomFileLogger.i(TAG, "Using mp3 ringtoneAsset=$ringtoneAsset for account=$accountId")
        } ?: run {
            IntercomFileLogger.w(
                TAG,
                "Failed to load mp3 ringtoneAsset=$ringtoneAsset for account=$accountId",
            )
            null
        }
    }

    private fun createAssetSound(assetPath: String): IncomingCallSound? {
        val assetSound = runCatching {
            val cacheFile = createCachedAssetSoundFile(assetPath)
            createMediaPlayerSound(
                dataSourcePath = cacheFile.absolutePath,
                cleanup = { cacheFile.delete() },
            )
        }.onFailure { error ->
            IntercomFileLogger.w(TAG, "Failed to create sound from assets path=$assetPath", error)
        }.getOrNull()
        if (assetSound != null) {
            IntercomFileLogger.i(TAG, "Created sound from bundled asset path=$assetPath")
            return assetSound
        }

        val configDirectory = appContainer.configFilePath
            .takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.parentFile
            ?: return null
        val candidateFile = File(configDirectory, assetPath)
        if (!candidateFile.isFile) {
            IntercomFileLogger.w(TAG, "Ringtone file not found in config directory path=${candidateFile.absolutePath}")
            return null
        }

        return runCatching {
            createMediaPlayerSound(dataSourcePath = candidateFile.absolutePath)
        }.onFailure { error ->
            IntercomFileLogger.w(TAG, "Failed to create sound from config file path=${candidateFile.absolutePath}", error)
        }.getOrNull()
    }

    private fun createCachedAssetSoundFile(assetPath: String): File {
        val extension = assetPath.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            ?: ".mp3"
        val cacheFile = File.createTempFile("incoming_ringtone_", extension, cacheDir)
        applicationContext.assets.open(assetPath).use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        IntercomFileLogger.d(TAG, "Copied ringtone asset=$assetPath to cache=${cacheFile.absolutePath}")
        return cacheFile
    }

    private fun createMediaPlayerSound(
        dataSourcePath: String,
        cleanup: (() -> Unit)? = null,
    ): IncomingCallSound {
        val mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(dataSourcePath)
            isLooping = true
            prepare()
        }
        IntercomFileLogger.d(TAG, "Prepared MediaPlayer dataSourcePath=$dataSourcePath")
        return MediaPlayerIncomingCallSound(
            mediaPlayer = mediaPlayer,
            cleanup = cleanup,
        )
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = buildList {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionsLauncher.launch(permissions.toTypedArray())
        } else {
            SipCoreManager.updatePermissions(microphoneGranted, cameraGranted)
        }
    }

    private fun updateGrantedPermissions() {
        microphoneGranted = hasPermission(Manifest.permission.RECORD_AUDIO)
        cameraGranted = hasPermission(Manifest.permission.CAMERA)
        IntercomFileLogger.i(TAG, "updateGrantedPermissions microphone=$microphoneGranted camera=$cameraGranted")
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}

private interface IncomingCallSound {
    fun playLooping()

    fun stop()

    fun release()
}

private class MediaPlayerIncomingCallSound(
    private val mediaPlayer: MediaPlayer,
    private val cleanup: (() -> Unit)? = null,
) : IncomingCallSound {
    override fun playLooping() {
        runCatching {
            if (!mediaPlayer.isPlaying) {
                mediaPlayer.start()
            }
        }
    }

    override fun stop() {
        runCatching {
            if (mediaPlayer.isPlaying) {
                mediaPlayer.pause()
            }
            mediaPlayer.seekTo(0)
        }
    }

    override fun release() {
        runCatching {
            mediaPlayer.stop()
        }
        runCatching {
            mediaPlayer.release()
        }
        runCatching {
            cleanup?.invoke()
        }
    }
}
