package net.muratov.intercom

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import net.muratov.intercom.data.model.RtspStream
import net.muratov.intercom.ui.navigation.IntercomNavGraph
import net.muratov.intercom.ui.screens.ConfigRequiredScreen
import net.muratov.intercom.ui.screens.FullscreenStreamScreen
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

    private lateinit var composeHost: ComposeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        composeHost = findViewById(R.id.composeHost)
        composeHost.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        permissionsLauncher.launch(
            buildList {
                add(Manifest.permission.CAMERA)
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray(),
        )
        composeHost.setContent {
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
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context.findActivity()
    var selectedStreamId by rememberSaveable { mutableStateOf<String?>(null) }
    var fullscreenStream by remember { mutableStateOf<RtspStream?>(null) }
    var isFullscreenStreamLoading by remember { mutableStateOf(false) }
    val showConfigRequired = !uiState.isConfigValid
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

    LaunchedEffect(uiState.isConfigValid, uiState.proptechWizardRequired) {
        if (!uiState.isConfigValid) {
            return@LaunchedEffect
        }
        if (uiState.proptechWizardRequired) {
            viewModel.startRegistrationIfNeeded()
        } else {
            viewModel.startMainIfNeeded()
        }
    }

    LaunchedEffect(uiState.isConfigValid, uiState.canEnterMainUi) {
        if (uiState.isConfigValid && uiState.canEnterMainUi) {
            viewModel.startMainIfNeeded()
        }
    }

    LaunchedEffect(uiState.isConfigValid) {
        if (uiState.isConfigValid) {
            return@LaunchedEffect
        }
        while (isActive) {
            delay(5_000)
            val application = activity?.application as? MainApplication ?: continue
            application.reloadAppContainer()
            if (application.appContainer.isConfigValid) {
                activity.recreate()
                break
            }
        }
    }

    LaunchedEffect(selectedStreamId) {
        val streamId = selectedStreamId
        if (streamId == null) {
            fullscreenStream = null
            isFullscreenStreamLoading = false
            return@LaunchedEffect
        }
        isFullscreenStreamLoading = true
        fullscreenStream = viewModel.resolveFullscreenStream(streamId)
        isFullscreenStreamLoading = false
        if (fullscreenStream == null) {
            selectedStreamId = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (showConfigRequired) {
            ConfigRequiredScreen(
                configFilePath = uiState.configFilePath,
                message = uiState.configErrorMessage ?: "Необходима конфигурация для приложения",
            )
        } else if (showWizard) {
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
                browserVisible = selectedStreamId == null,
                stopTileVideoPlayback = uiState.stopTileVideoPlayback,
                onStreamSelected = { stream -> selectedStreamId = stream.id },
                modifier = Modifier.fillMaxSize(),
            )

            if (selectedStreamId != null && isFullscreenStreamLoading) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            if (fullscreenStream != null) {
                val openAction = fullscreenStream?.openAction?.takeIf(viewModel::canOpen)
                FullscreenStreamOverlay(
                    stream = fullscreenStream!!,
                    onDismiss = {
                        selectedStreamId = null
                        fullscreenStream = null
                    },
                    onOpen = openAction?.let { action ->
                        { viewModel.open(action) }
                    },
                )
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
    onOpen: (() -> Unit)? = null,
) {
    BackHandler(onBack = onDismiss)
    Box(modifier = Modifier.fillMaxSize()) {
        FullscreenStreamScreen(stream = stream, onOpen = onOpen)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
