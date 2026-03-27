package net.muratov.intercom.voip

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.linphone.core.Call
import net.muratov.intercom.databinding.ActivityIncomingCallBinding
class IncomingCallActivity : AppCompatActivity() {
    private lateinit var binding: ActivityIncomingCallBinding

    private var microphoneGranted = false
    private var cameraGranted = false

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
        SipCoreManager.attachVideoWindows(null, null)
        super.onPause()
    }

    override fun onStop() {
        SipCoreManager.removeListener(coreListener)
        super.onStop()
    }

    private fun setupListeners() {
        binding.answerButton.setOnClickListener {
            SipCoreManager.answerIncomingCall()
        }

        binding.hangupButton.setOnClickListener {
            SipCoreManager.terminateCurrentCall()
        }
    }

    private fun renderCall(snapshot: CallSnapshot?) {
        if (snapshot == null) {
            finish()
            return
        }


        val incomingPending = snapshot.state == Call.State.IncomingReceived ||
                snapshot.state == Call.State.IncomingEarlyMedia
        val activeCall = snapshot.state != Call.State.End &&
                snapshot.state != Call.State.Error &&
                snapshot.state != Call.State.Released

        if (activeCall && !incomingPending) {
            return
        }

        binding.answerButton.visibility = if (incomingPending) View.VISIBLE else View.GONE
        binding.hangupButton.visibility = if (activeCall) View.VISIBLE else View.GONE

        if (!activeCall) {
            finish()
            return
        }

        refreshVideoWindows()
    }

    private fun refreshVideoWindows() {
        SipCoreManager.attachVideoWindows(binding.remoteVideoSurface, null)
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