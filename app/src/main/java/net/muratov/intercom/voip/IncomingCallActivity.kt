package net.muratov.intercom.voip

import android.Manifest
import android.media.Ringtone
import android.media.RingtoneManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import org.linphone.core.Call
import kotlinx.coroutines.launch
import net.muratov.intercom.databinding.ActivityIncomingCallBinding
class IncomingCallActivity : AppCompatActivity() {
    private lateinit var binding: ActivityIncomingCallBinding

    private var microphoneGranted = false
    private var cameraGranted = false
    private var ringtone: Ringtone? = null
    private var ringtoneAccountId: String? = null

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
        ringtone?.stopSafely()
        SipCoreManager.attachVideoWindows(null, null)
        super.onPause()
    }

    override fun onStop() {
        ringtone?.stopSafely()
        SipCoreManager.removeListener(coreListener)
        super.onStop()
    }

    override fun onDestroy() {
        ringtone?.stopSafely()
        super.onDestroy()
    }

    private fun setupListeners() {
        binding.answerButton.setOnClickListener {
            ringtone?.stopSafely()
            SipCoreManager.answerIncomingCall()
        }

        binding.hangupButton.setOnClickListener {
            ringtone?.stopSafely()
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
            ringtone?.stopSafely()
            finish()
            return
        }

        ensureRingtoneForCurrentAccount()

        val incomingPending = snapshot.state == Call.State.IncomingReceived ||
                snapshot.state == Call.State.IncomingEarlyMedia
        val activeCall = snapshot.state != Call.State.End &&
                snapshot.state != Call.State.Error &&
                snapshot.state != Call.State.Released

        if (activeCall && !incomingPending) {
            ringtone?.stopSafely()
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
            ringtone?.playLooping()
        } else {
            ringtone?.stopSafely()
        }
        val accountId = SipCoreManager.getCurrentCallAccountId()
        val openAction = accountId?.let { id ->
            appContainer.sipAccountRepository.accounts.value.firstOrNull { it.id == id }?.openAction
        }
        binding.openButton.visibility = if (openAction != null) View.VISIBLE else View.GONE

        if (!activeCall) {
            ringtone?.stopSafely()
            finish()
            return
        }

        refreshVideoWindows()
    }

    private fun refreshVideoWindows() {
        SipCoreManager.attachVideoWindows(binding.remoteVideoSurface, null)
    }

    private fun ensureRingtoneForCurrentAccount() {
        val accountId = SipCoreManager.getCurrentCallAccountId()
        if (ringtone != null && ringtoneAccountId == accountId) {
            return
        }
        ringtone?.stopSafely()
        ringtoneAccountId = accountId
        ringtone = resolveRingtoneForAccount(accountId)
    }

    private fun resolveRingtoneForAccount(accountId: String?): Ringtone? {
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
            runCatching { RingtoneManager.getRingtone(applicationContext, uri) }.getOrNull()
        }
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

private fun Ringtone.playLooping() {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            isLooping = true
        }
        if (!isPlaying) {
            play()
        }
    }
}

private fun Ringtone.stopSafely() {
    runCatching { stop() }
}
