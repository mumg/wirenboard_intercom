package net.muratov.intercom.voip

import android.Manifest
import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
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
import org.linphone.core.Call
import kotlinx.coroutines.launch
import net.muratov.intercom.databinding.ActivityIncomingCallBinding
import java.io.File
class IncomingCallActivity : AppCompatActivity() {
    private lateinit var binding: ActivityIncomingCallBinding

    private var microphoneGranted = false
    private var cameraGranted = false
    private var incomingCallSound: IncomingCallSound? = null
    private var incomingCallSoundAccountId: String? = null

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
        SipCoreManager.attachVideoWindows(binding.remoteVideoSurface, null)
    }

    private fun applyWindowInsets() {
        val layoutParams = binding.callActionsBar.layoutParams as ViewGroup.MarginLayoutParams
        val baseBottomMargin = layoutParams.bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val navigationBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            layoutParams.bottomMargin = baseBottomMargin + navigationBarInsets.bottom
            binding.callActionsBar.layoutParams = layoutParams
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

    private fun resolveIncomingCallSoundForAccount(accountId: String?): IncomingCallSound? {
        val ringtoneAsset = accountId?.let { id ->
            appContainer.sipAccountRepository.accounts.value.firstOrNull { it.id == id }?.ringtoneAsset
        }
        val assetSound = ringtoneAsset?.let(::createAssetSound)
        if (assetSound != null) {
            return assetSound
        }

        val ringtoneUris = runCatching {
            val manager = RingtoneManager(this).apply {
                setType(RingtoneManager.TYPE_RINGTONE)
            }
            buildList {
                val cursor = manager.cursor ?: return@buildList
                while (cursor.moveToNext()) {
                    add(manager.getRingtoneUri(cursor.position))
                }
            }
        }.getOrDefault(emptyList())

        val selectedUri = when {
            ringtoneUris.isNotEmpty() -> {
                val index = Math.floorMod(accountId?.hashCode() ?: 0, ringtoneUris.size)
                ringtoneUris[index]
            }
            else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }

        return selectedUri?.let { uri ->
            runCatching { RingtoneManager.getRingtone(applicationContext, uri) }
                .getOrNull()
                ?.let(::RingtoneIncomingCallSound)
        }
    }

    private fun createAssetSound(assetPath: String): IncomingCallSound? {
        val assetSound = runCatching {
            val assetFileDescriptor = applicationContext.assets.openFd(assetPath)
            val mediaPlayer = MediaPlayer().apply {
                setDataSource(
                    assetFileDescriptor.fileDescriptor,
                    assetFileDescriptor.startOffset,
                    assetFileDescriptor.length,
                )
                isLooping = true
                prepare()
            }
            assetFileDescriptor.close()
            MediaPlayerIncomingCallSound(mediaPlayer)
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
            val mediaPlayer = MediaPlayer().apply {
                setDataSource(candidateFile.absolutePath)
                isLooping = true
                prepare()
            }
            MediaPlayerIncomingCallSound(mediaPlayer)
        }.getOrNull()
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

private class RingtoneIncomingCallSound(
    private val ringtone: Ringtone,
) : IncomingCallSound {
    override fun playLooping() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.isLooping = true
            }
            if (!ringtone.isPlaying) {
                ringtone.play()
            }
        }
    }

    override fun stop() {
        runCatching { ringtone.stop() }
    }

    override fun release() {
        stop()
    }
}

private class MediaPlayerIncomingCallSound(
    private val mediaPlayer: MediaPlayer,
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
    }
}
