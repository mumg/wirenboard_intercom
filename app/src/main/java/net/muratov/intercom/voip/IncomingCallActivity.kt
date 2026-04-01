package net.muratov.intercom.voip

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import net.muratov.intercom.video.createRtspPlaybackView
import net.muratov.intercom.video.playRtspOnView
import net.muratov.intercom.video.releaseRtspPlaybackView
import org.linphone.core.Call
import java.io.File

class IncomingCallActivity : AppCompatActivity() {
    private lateinit var binding: ActivityIncomingCallBinding

    private var microphoneGranted = false
    private var cameraGranted = false
    private var incomingCallSound: IncomingCallSound? = null
    private var incomingCallSoundAccountId: String? = null
    private var previewPlaybackView: View? = null

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
        SipCoreManager.addListener(coreListener)
    }

    override fun onResume() {
        super.onResume()
        refreshVideoWindows()
    }

    override fun onPause() {
        incomingCallSound?.stop()
        clearRemotePreviewPlayback()
        SipCoreManager.attachVideoWindows(null, null)
        super.onPause()
    }

    override fun onStop() {
        incomingCallSound?.stop()
        SipCoreManager.removeListener(coreListener)
        super.onStop()
    }

    override fun onDestroy() {
        incomingCallSound?.release()
        incomingCallSound = null
        clearRemotePreviewPlayback()
        super.onDestroy()
    }

    private fun setupListeners() {
        binding.answerButton.setOnClickListener {
            incomingCallSound?.stop()
            SipCoreManager.answerIncomingCall()
        }

        binding.hangupButton.setOnClickListener {
            incomingCallSound?.stop()
            SipCoreManager.terminateCurrentCall()
        }

        binding.openButton.setOnClickListener {
            val accountId = SipCoreManager.getCurrentCallAccountId() ?: return@setOnClickListener
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
        if (snapshot == null) {
            incomingCallSound?.stop()
            finish()
            return
        }

        ensureIncomingCallSoundForCurrentAccount()

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

        if (!activeCall) {
            incomingCallSound?.stop()
            finish()
            return
        }

        refreshVideoWindows()
    }

    private fun refreshVideoWindows() {
        val currentAccount = getCurrentAccount()
        val previewRtspUrl = currentAccount?.incomingPreviewRtspUrl
        if (!previewRtspUrl.isNullOrBlank()) {
            binding.remoteVideoSurface.visibility = View.GONE
            binding.remotePreviewContainer.visibility = View.VISIBLE
            SipCoreManager.attachVideoWindows(null, null)
            val playbackView = previewPlaybackView ?: createRtspPlaybackView(
                context = this,
                playbackEngine = currentAccount.incomingPreviewPlaybackEngine,
            ).also { view ->
                previewPlaybackView = view
                binding.remotePreviewContainer.removeAllViews()
                binding.remotePreviewContainer.addView(view)
            }
            playRtspOnView(
                view = playbackView,
                url = previewRtspUrl,
                headers = currentAccount.incomingPreviewHeaders,
                muted = true,
            )
            return
        }

        clearRemotePreviewPlayback()
        binding.remotePreviewContainer.visibility = View.GONE
        binding.remoteVideoSurface.visibility = View.VISIBLE
        SipCoreManager.attachVideoWindows(binding.remoteVideoSurface, null)
    }

    private fun clearRemotePreviewPlayback() {
        previewPlaybackView?.let { view ->
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
            return
        }
        incomingCallSound?.release()
        incomingCallSoundAccountId = accountId
        incomingCallSound = resolveIncomingCallSoundForAccount(accountId)
    }

    private fun getCurrentAccount() = SipCoreManager.getCurrentCallAccountId()?.let { accountId ->
        appContainer.sipAccountRepository.accounts.value.firstOrNull { it.id == accountId }
    }

    private fun resolveIncomingCallSoundForAccount(accountId: String?): IncomingCallSound? {
        val ringtoneAsset = accountId?.let { id ->
            appContainer.sipAccountRepository.accounts.value.firstOrNull { it.id == id }?.ringtoneAsset
        }
        if (ringtoneAsset.isNullOrBlank()) {
            Log.d("IncomingCallActivity", "No ringtoneAsset configured for account=$accountId")
            return null
        }
        if (!ringtoneAsset.endsWith(".mp3", ignoreCase = true)) {
            Log.w(
                "IncomingCallActivity",
                "ringtoneAsset must point to an mp3 file, got=$ringtoneAsset for account=$accountId",
            )
            return null
        }
        return createAssetSound(ringtoneAsset)?.also {
            Log.d("IncomingCallActivity", "Using mp3 ringtoneAsset=$ringtoneAsset for account=$accountId")
        } ?: run {
            Log.w(
                "IncomingCallActivity",
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
        }.getOrNull()
        if (assetSound != null) {
            return assetSound
        }

        val configDirectory = appContainer.configFilePath
            .takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.parentFile
            ?: return null
        val candidateFile = File(configDirectory, assetPath)
        if (!candidateFile.isFile) {
            return null
        }

        return runCatching {
            createMediaPlayerSound(dataSourcePath = candidateFile.absolutePath)
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
