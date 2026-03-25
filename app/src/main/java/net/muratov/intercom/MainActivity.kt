package net.muratov.intercom

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import net.muratov.intercom.data.model.RtspStream
import net.muratov.intercom.ui.navigation.IntercomNavGraph
import net.muratov.intercom.ui.screens.ActiveCallOverlay
import net.muratov.intercom.ui.screens.FullscreenStreamScreen
import net.muratov.intercom.ui.screens.IncomingCallOverlay
import net.muratov.intercom.ui.screens.MyHomeContextSelectionDialog
import net.muratov.intercom.ui.screens.MyHomeVerificationDialog
import net.muratov.intercom.ui.screens.ProptechRegistrationWizardScreen
import net.muratov.intercom.ui.theme.IntercomTheme
import net.muratov.intercom.ui.viewmodel.MainViewModel
import net.muratov.intercom.ui.viewmodel.MainViewModelFactory

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory((application as MainApplication).appContainer)
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionsLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
            ),
        )
        setContent {
            IntercomTheme {
                IntercomApp(viewModel = viewModel, appContainer = (application as MainApplication).appContainer)
            }
        }
    }
}

@Composable
private fun IntercomApp(
    viewModel: MainViewModel,
    appContainer: AppContainer,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    var selectedStreamId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedStream = uiState.streams.firstOrNull { it.id == selectedStreamId }
    val showWizard = uiState.proptechWizardRequired && !uiState.canEnterMainUi

    DisposableEffect(activity, view) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        controller?.show(WindowInsetsCompat.Type.navigationBars())
        controller?.hide(WindowInsetsCompat.Type.statusBars())

        onDispose {
            controller?.show(WindowInsetsCompat.Type.statusBars())
            controller?.show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    LaunchedEffect(uiState.proptechWizardRequired) {
        if (uiState.proptechWizardRequired) {
            viewModel.startRegistrationIfNeeded()
        } else {
            viewModel.startMainIfNeeded()
        }
    }

    LaunchedEffect(uiState.canEnterMainUi) {
        if (uiState.canEnterMainUi) {
            viewModel.startMainIfNeeded()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (showWizard) {
            ProptechRegistrationWizardScreen(
                providerState = uiState.myHomeProviderState,
                onStart = viewModel::restartRegistration,
                onRetry = viewModel::restartRegistration,
            )
        } else {
            IntercomNavGraph(
                navController = navController,
                webViewUrl = appContainer.webViewUrl,
                streams = uiState.streams,
                browserVisible = selectedStream == null,
                onStreamSelected = { stream -> selectedStreamId = stream.id },
                modifier = Modifier.fillMaxSize(),
            )

            if (selectedStream != null) {
                FullscreenStreamOverlay(
                    stream = selectedStream,
                    onDismiss = { selectedStreamId = null },
                )
            }

            uiState.incomingCall?.let { call ->
                IncomingCallOverlay(
                    callSession = call,
                    sipService = appContainer.sipService,
                    onAccept = viewModel::answerIncomingCall,
                    onReject = viewModel::rejectIncomingCall,
                )
            }

            if (uiState.incomingCall == null) {
                uiState.activeCall?.let { call ->
                    ActiveCallOverlay(
                        callSession = call,
                        sipService = appContainer.sipService,
                        onHangup = viewModel::endActiveCall,
                    )
                }
            }
        }

        uiState.contextSelectionPrompt?.let { prompt ->
            MyHomeContextSelectionDialog(
                prompt = prompt,
                message = uiState.myHomeProviderState.message,
                onDismiss = viewModel::dismissVerificationPrompt,
                onConfirm = viewModel::selectLoginContext,
            )
        }

        uiState.verificationPrompt?.let { prompt ->
            MyHomeVerificationDialog(
                prompt = prompt,
                message = uiState.myHomeProviderState.message,
                onDismiss = viewModel::dismissVerificationPrompt,
                onSubmit = viewModel::submitVerificationCode,
            )
        }
    }
}

@Composable
private fun FullscreenStreamOverlay(
    stream: RtspStream,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Box(modifier = Modifier.fillMaxSize()) {
        FullscreenStreamScreen(stream = stream)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
